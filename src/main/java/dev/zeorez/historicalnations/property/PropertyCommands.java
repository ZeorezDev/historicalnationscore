package dev.zeorez.historicalnations.property;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.*;
import dev.zeorez.historicalnations.core.*;
import dev.zeorez.historicalnations.integration.TownTerritories;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import static net.minecraft.commands.Commands.*;

public final class PropertyCommands {
    private PropertyCommands() {}
    static Component msg(String key, Object... args) { return Component.translatable("historical_nations." + key, args); }
    static void require(boolean condition, String key) { if (!condition) throw new RuleViolation(key); }
    interface Operation { Component apply(Cursor c) throws CommandSyntaxException; }

    static final class Cursor {
        final CommandContext<CommandSourceStack> cmd;
        final ServerPlayer player;
        final WorldRegistry data;
        final PropertyBook book;
        final Territory.Location loc;
        final Territory link;
        final PropertyBook.Plot plot;
        final boolean quarter;
        Cursor(CommandContext<CommandSourceStack> cmd, boolean quarter) throws CommandSyntaxException {
            this.cmd = cmd;
            player = cmd.getSource().getPlayerOrException();
            data = WorldRegistry.get(player.server);
            book = data.properties;
            loc = TownTerritories.location(player);
            link = new TownTerritories(player.server).verified(loc);
            require(link != null, "parcel_land");
            plot = book.plots.get(loc);
            require(plot != null && plot.unreadable == null, "parcel_locked");
            this.quarter = quarter;
        }
        boolean manager() { return link.owner().equals(player.getUUID()) || cmd.getSource().hasPermission(2); }
        void manage() { require(manager(), "parcel_manage"); }
        Parcel parcel() {
            if (!quarter) return plot.land;
            var p = plot.quarter(player.blockPosition());
            require(p != null, "quarter_missing");
            return p;
        }
        Parcel edit() {
            var p = parcel();
            require(manager() || player.getUUID().equals(p.owner), "parcel_manage");
            return p;
        }
        UUID target() throws CommandSyntaxException {
            var profiles = GameProfileArgument.getGameProfiles(cmd, "player");
            require(profiles.size() == 1, "single_player");
            return profiles.iterator().next().getId();
        }
        boolean eligible(UUID playerId, Parcel.Kind kind) {
            return data.getPlayerTown(playerId).map(t -> t.id().equals(link.town()) || kind == Parcel.Kind.EMBASSY).orElse(false);
        }
        String word(String key) { return StringArgumentType.getString(cmd, key); }
        Component changed() { data.setDirty(); return msg("updated"); }
    }
    static Command<CommandSourceStack> run(boolean quarter, Operation operation) {
        return cmd -> {
            try {
                Component result = operation.apply(new Cursor(cmd, quarter));
                cmd.getSource().sendSuccess(() -> result, false);
                return 1;
            } catch (RuleViolation error) {
                throw new SimpleCommandExceptionType(msg(error.getMessage())).create();
            }
        };
    }
    static String text(String input, int limit) {
        String value = input.strip();
        require(!value.isEmpty() && value.length() <= limit && value.chars().noneMatch(Character::isISOControl), "invalid_text");
        return value;
    }
    static LiteralArgumentBuilder<CommandSourceStack> named(boolean quarter, String verb, Operation action) {
        return literal(verb).then(argument("value", StringArgumentType.greedyString()).executes(run(quarter, action)));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> player(boolean quarter, String verb, Operation action) {
        return literal(verb).then(argument("player", GameProfileArgument.gameProfile()).executes(run(quarter, action)));
    }

    public static void register(CommandDispatcher<CommandSourceStack> commands) {
        var plot = common(false);
        var groups = literal("group");
        for (String verb : List.of("create","join","delete")) groups.then(named(false, verb, c -> {
            c.manage();
            var name = text(c.word("value"), 32);
            var key = new PropertyBook.GroupKey(c.link.town(), name);
            if (verb.equals("create")) require(c.book.groups.putIfAbsent(key, new HashMap<>()) == null, "name_taken");
            else if (verb.equals("delete")) {
                require(c.book.groups.remove(key) != null, "group_missing");
                for (var location : c.data.territoryLocations(c.link.town())) {
                    var p = c.book.plots.get(location).land;
                    if (p.group.equals(name)) p.group = "";
                }
            } else {
                require(c.book.groups.containsKey(key), "group_missing");
                c.plot.land.group = name;
            }
            return c.changed();
        }));
        groups.then(literal("leave").executes(run(false, c -> { c.manage(); c.plot.land.group = ""; return c.changed(); })));
        plot.then(groups);
        commands.register(plot);
        var quarter = common(true);
        QuarterCommands.register(quarter);
        commands.register(quarter);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> common(boolean q) {
        var root = literal(q ? "quarter" : "plot");
        root.then(literal("info").executes(run(q,c -> {
            var p = c.parcel();
            var view = p.view(c.link.town(), c.loc, c.book.shared(c.link.town(), p));
            return msg("parcel_info", p.name, p.kind.name(), p.owner == null ? msg("none") :
                    c.player.server.getProfileCache().get(p.owner).map(com.mojang.authlib.GameProfile::getName).orElse(p.owner.toString()),
                    p.price == null ? msg("none") : p.price, p.group, p.description, p.id.toString(),
                    p.trusted.size(), view.permissions().toString(), p.boxes.size());
        })));
        var set = literal("set");
        set.then(named(q, "name", c -> {
            var p = c.edit();
            var name = text(c.word("value"), 48);
            require(!q || c.plot.quarters.stream().noneMatch(x -> x != p && x.name.equalsIgnoreCase(name)), "name_taken");
            p.name = name;
            return c.changed();
        }));
        var types = literal("type");
        for (var kind : Parcel.Kind.values()) {
            if (q && !Parcel.QUARTER_TYPES.contains(kind) || !q && (kind == Parcel.Kind.APARTMENT || kind == Parcel.Kind.STATION)) continue;
            Operation change = c -> {
                c.manage();
                var p = c.parcel();
                require(p.owner == null || c.eligible(p.owner, kind), "parcel_membership");
                if (kind == Parcel.Kind.JAIL) {
                    require(p.owner == null, "municipal_facility");
                    p.price = null;
                }
                p.kind = kind;
                return c.changed();
            };
            types.then(literal(kind.name().toLowerCase(Locale.ROOT)).executes(run(q, change)));
            if (kind == Parcel.Kind.SHOP) types.then(literal("commercial").executes(run(q, change)));
            if (kind == Parcel.Kind.WILDS) types.then(literal("wilderness").executes(run(q, change)));
        }
        set.then(types);
        set.then(player(q, "owner", c -> {
            c.manage();
            var p = c.parcel();
            var target = c.target();
            require(p.kind != Parcel.Kind.JAIL, "municipal_facility");
            require(c.eligible(target, p.kind), "parcel_membership");
            p.assign(target);
            return c.changed();
        }));

        if (q) QuarterCommands.settings(set);
        root.then(set);
        root.then(literal("evict").executes(run(q,c -> { c.manage(); c.parcel().assign(null); return c.changed(); })));
        root.then(literal("unclaim").executes(run(q,c -> { c.edit().assign(null); return c.changed(); })));
        root.then(literal("forsale").then(argument("price", LongArgumentType.longArg(0)).executes(run(q,c -> {
            var p = c.edit();
            require(p.kind != Parcel.Kind.JAIL, "municipal_facility");
            p.price = LongArgumentType.getLong(c.cmd, "price");
            return c.changed();
        }))));
        root.then(literal("notforsale").executes(run(q,c -> { c.edit().price = null; return c.changed(); })));
        root.then(literal("claim").executes(run(q,c -> {
            var p = c.parcel();
            require(p.price != null, "no_offer");
            require(p.price == 0L, "no_economy");
            require(c.eligible(c.player.getUUID(), p.kind), "parcel_membership");
            p.assign(c.player.getUUID());
            return c.changed();
        })));
        var trust = literal("trust");
        for (String verb : List.of("add", "remove")) trust.then(player(q, verb, c -> {
            var p = c.edit();
            var target = c.target();
            if (verb.equals("add")) {
                require(c.eligible(target, p.kind), "parcel_membership");
                p.trusted.add(target);
            } else p.trusted.remove(target);
            return c.changed();
        }));
        root.then(trust);
        var perm = literal("perm");
        for (var action : Parcel.Action.values()) {
            var verb = literal(action.name().toLowerCase(Locale.ROOT).replace('_','-'));
            for (var group : Parcel.Group.values()) verb.then(literal(group.name().toLowerCase(Locale.ROOT))
                    .then(argument("allowed", BoolArgumentType.bool()).executes(run(q,c -> {
                        var p = c.edit();
                        Map<String,Boolean> rules = p.permissions;
                        if (!p.group.isEmpty()) {
                            c.manage();
                            rules = c.book.groups.get(new PropertyBook.GroupKey(c.link.town(), p.group));
                            require(rules != null, "group_missing");
                        }
                        rules.put(action + "." + group, BoolArgumentType.getBool(c.cmd,"allowed"));
                        return c.changed();
                    }))));
            perm.then(verb);
        }
        root.then(perm);
        return root;
    }
}
