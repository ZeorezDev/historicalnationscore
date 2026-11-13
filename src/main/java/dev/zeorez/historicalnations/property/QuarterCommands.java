package dev.zeorez.historicalnations.property;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.ParcelView.Box;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3f;
import java.util.*;
import static dev.zeorez.historicalnations.property.PropertyCommands.*;
import static net.minecraft.commands.Commands.*;

public final class QuarterCommands {
    private static final Map<ServerPlayer, Corners> selections = new WeakHashMap<>();
    private record Corners(ResourceLocation dimension, BlockPos first, BlockPos second) {}
    private static final String WAND = "historical_nations_quarter_wand";
    private QuarterCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(literal("wand").executes(run(true, c -> {
            c.manage();
            var wand = new ItemStack(Items.STICK);
            wand.getOrCreateTag().putBoolean(WAND, true);
            wand.setHoverName(msg("wand"));
            require(c.player.getInventory().add(wand), "inventory_full");
            return msg("wand_help");
        })));
        for (int corner : List.of(1,2)) root.then(literal("pos" + corner).executes(run(true, c -> {
            c.manage();
            select(c.player, c.player.blockPosition(), corner == 1);
            return msg("corner", corner, c.player.blockPosition().toShortString());
        })));
        root.then(named(true, "create", c -> {
            c.manage();
            var box = selected(c, null);
            String name = text(c.word("value"), 48);
            require(c.plot.quarters.stream().noneMatch(p -> p.name.equalsIgnoreCase(name)), "name_taken");
            int count = c.data.territoryLocations(c.link.town()).stream().mapToInt(l -> c.book.plots.get(l).quarters.size()).sum();
            require(count < TerritoryConfig.QUARTERS.get(), "quarter_limit");
            var p = new Parcel(UUID.randomUUID(), name, Parcel.Kind.APARTMENT);
            p.boxes.add(box);
            c.plot.quarters.add(p);
            return c.changed();
        }));
        root.then(literal("addselection").executes(run(true, c -> {
            c.manage();
            var p = c.parcel();
            require(p.boxes.size() < TerritoryConfig.PARTS.get(), "quarter_limit");
            var box = selected(c, null);
            p.boxes.add(box);
            return c.changed();
        })));
        root.then(literal("replace").then(argument("part", IntegerArgumentType.integer(1)).executes(run(true, c -> {
            c.manage();
            var p = c.parcel();
            int i = IntegerArgumentType.getInteger(c.cmd, "part") - 1;
            require(i < p.boxes.size(), "invalid_selection");
            var box = selected(c, p);
            for (int j = 0; j < p.boxes.size(); j++)
                require(i == j || !box.intersects(p.boxes.get(j)), "quarter_overlap");
            p.boxes.set(i, box);
            return c.changed();
        }))));
        root.then(named(true, "merge", c -> {
            c.manage();
            var dest = c.parcel();
            var source = c.plot.quarters.stream().filter(p -> p != dest && p.name.equalsIgnoreCase(c.word("value")))
                    .findFirst().orElseThrow(() -> new dev.zeorez.historicalnations.core.RuleViolation("quarter_missing"));
            require(dest.boxes.size() + source.boxes.size() <= TerritoryConfig.PARTS.get(), "quarter_limit");
            dest.boxes.addAll(source.boxes);
            c.plot.quarters.remove(source);
            return c.changed();
        }));
        root.then(literal("delete").executes(run(true, c -> {
            c.manage();
            c.plot.quarters.remove(c.parcel());
            return c.changed();
        })));
        root.then(literal("show").executes(run(true,c -> {
            var p = c.parcel();
            for (Box box : p.boxes) outline(c.player, box, p.color);
            return msg("boundary", p.name);
        })));
    }
    static void settings(LiteralArgumentBuilder<CommandSourceStack> set) {
        set.then(named(true,"color",c -> {
            String hex = c.word("value");
            require(hex.matches("#?[0-9a-fA-F]{6}"), "invalid_text");
            c.edit().color = Integer.parseInt(hex.replace("#",""), 16);
            return c.changed();
        }));
        set.then(named(true,"description",c -> {
            c.edit().description = text(c.word("value"), 256);
            return c.changed();
        }));
    }
    private static Box selected(Cursor c, Parcel except) {
        var corners = selections.get(c.player);
        require(corners != null && c.loc.dimension().equals(corners.dimension)
                && corners.first != null && corners.second != null, "invalid_selection");
        var box = Box.between(corners.first, corners.second);
        var level = c.player.serverLevel();
        require(box.inChunk(c.loc.x(), c.loc.z()) && box.y1() >= level.getMinBuildHeight()
                && box.y2() < level.getMaxBuildHeight() && box.volume() <= TerritoryConfig.VOLUME.get(), "invalid_selection");
        require(!c.plot.intersects(box, except), "quarter_overlap");
        return box;
    }
    private static void select(ServerPlayer player, BlockPos pos, boolean first) {
        var dim = player.serverLevel().dimension().location();
        var previous = selections.get(player);
        if (previous == null || !previous.dimension.equals(dim)) previous = new Corners(dim,null,null);
        selections.put(player, new Corners(dim, first ? pos.immutable() : previous.first, first ? previous.second : pos.immutable()));
    }
    @SubscribeEvent public static void left(PlayerInteractEvent.LeftClickBlock event) { wand(event, true); }
    @SubscribeEvent public static void right(PlayerInteractEvent.RightClickBlock event) { wand(event, false); }
    private static void wand(PlayerInteractEvent event, boolean first) {
        var stack = event.getItemStack();
        if (!(event.getEntity() instanceof ServerPlayer player) || !stack.is(Items.STICK)
                || stack.getTag() == null || !stack.getTag().getBoolean(WAND)) return;
        event.setCanceled(true);
        var loc = PropertyBook.location(player.serverLevel().dimension().location(), event.getPos());
        var link = new dev.zeorez.historicalnations.integration.TownTerritories(player.server).verified(loc);
        if (link == null || !(link.owner().equals(player.getUUID()) || player.hasPermissions(2))) return;
        select(player, event.getPos(), first);
        player.displayClientMessage(msg("corner",first ? 1 : 2,event.getPos().toShortString()), true);
    }
    private static void outline(ServerPlayer player, Box b, int rgb) {
        var dust = new DustParticleOptions(new Vector3f((rgb >> 16 & 255)/255f, (rgb >> 8 & 255)/255f, (rgb & 255)/255f), 1f);
        int[] lo = {b.x1(), b.y1(), b.z1()};
        int[] hi = {b.x2()+1, b.y2()+1, b.z2()+1};
        for (int axis = 0; axis < 3; axis++) {
            int a = (axis+1)%3, c = (axis+2)%3;
            int samples = Math.min(24, hi[axis]-lo[axis]);
            double[] xyz = new double[3];
            for (int edge = 0; edge < 4; edge++) {
                xyz[a] = (edge & 1) == 0 ? lo[a] : hi[a];
                xyz[c] = (edge & 2) == 0 ? lo[c] : hi[c];
                for (int i = 0; i <= samples; i++) {
                    xyz[axis] = lo[axis] + (hi[axis]-lo[axis]) * (double)i/samples;
                    player.serverLevel().sendParticles(player,dust,true,xyz[0],xyz[1],xyz[2],1,0,0,0,0);
                }
            }
        }
    }
}
