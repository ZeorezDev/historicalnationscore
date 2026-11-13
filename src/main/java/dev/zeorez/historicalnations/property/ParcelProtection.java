package dev.zeorez.historicalnations.property;

import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.TownTerritories;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.*;
import net.minecraftforge.event.level.*;
import net.minecraftforge.eventbus.api.*;
import java.util.*;
import static dev.zeorez.historicalnations.property.Parcel.*;

/** Vetoes position-specific actions after OPAC. No handler grants a Forge or OPAC permission. */
public final class ParcelProtection {
    private ParcelProtection() {}
    private record Site(Parcel parcel, UUID town, UUID mayor, WorldRegistry data, boolean locked) {}
    private static Site site(ServerLevel level, BlockPos pos) {
        if (level.getServer().overworld() == null || !level.getServer().isSameThread()) return null;
        var data = WorldRegistry.get(level.getServer());
        var loc = PropertyBook.location(level.dimension().location(),pos);
        var plot = data.properties.plots.get(loc);
        if (plot == null) return null;
        var link = new TownTerritories(level.getServer()).verified(loc);
        if (link == null) return null;
        var parcel = plot.quarter(pos);
        if (parcel == null) parcel = plot.land;
        return new Site(parcel, link.town(), link.owner(), data, plot.unreadable != null);
    }

    private static boolean managed(Parcel p) {
        return !p.boxes.isEmpty() || p.owner != null || !p.permissions.isEmpty() || !p.group.isEmpty()
                || p.kind != Kind.RESIDENTIAL;
    }
    private static UUID boundaryId(ServerLevel world, BlockPos pos) {
        var s = site(world, pos);
        return s != null && (s.locked || managed(s.parcel)) ? s.parcel.id : null;
    }
    public static boolean crosses(BlockGetter level, BlockPos from, BlockPos to) {
        return level instanceof ServerLevel world && !Objects.equals(boundaryId(world,from), boundaryId(world,to));
    }

    private static boolean permits(ServerLevel level, BlockPos pos, Entity actor, Action action, BlockState state) {
        Site s = site(level,pos);
        if (s == null) return true;
        if (s.locked) return false;
        if (!managed(s.parcel)) return true;
        if (!(actor instanceof ServerPlayer player)) return false;
        var p = s.parcel;
        UUID id = player.getUUID();
        var home = s.data.getPlayerTown(id);
        boolean citizen = home.map(t -> t.id().equals(s.town)).orElse(false);
        boolean eligible = citizen || p.kind == Kind.EMBASSY && home.isPresent();
        boolean construction = action == Action.BUILD || action == Action.DESTROY;
        if (construction && p.kind == Kind.FARM && !(state.getBlock() instanceof CropBlock
                || state.is(Blocks.FARMLAND) || state.is(Blocks.PUMPKIN) || state.is(Blocks.MELON)
                || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.NETHER_WART) || state.is(Blocks.COCOA))) return false;
        if (construction && p.kind == Kind.WILDS && !(state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS))) return false;
        if (eligible && id.equals(p.owner) || p.owner == null && id.equals(s.mayor)) return true;
        var group = eligible && p.trusted.contains(id) ? Group.TRUSTED : citizen ? Group.CITIZEN : Group.OUTSIDER;
        String rule = action + "." + group;
        Boolean explicit = s.data.properties.shared(s.town,p).get(rule);
        if (explicit == null) explicit = p.permissions.get(rule);
        if (explicit != null) return explicit;
        if (group == Group.TRUSTED) return true;
        return switch (p.kind) {
            case INN -> action == Action.INTERACT && state.is(BlockTags.BEDS);
            case SHOP -> action == Action.INTERACT && (state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof DoorBlock);
            case PUBLIC -> action == Action.INTERACT;
            case INDUSTRIAL -> citizen && !construction;
            case STATION -> citizen && (action == Action.INTERACT || action == Action.ITEM_USE && state.is(BlockTags.RAILS));
            case BANK -> citizen && action == Action.INTERACT && state.is(Blocks.ENDER_CHEST);
            case JAIL, EMBASSY, APARTMENT -> false;
            default -> citizen && p.owner == null;
        };
    }

    private static BlockPos partner(BlockPos pos, BlockState s) {
        if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            return pos.relative(ChestBlock.getConnectedDirection(s));
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF))
            return s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos.above();
        if (s.getBlock() instanceof BedBlock)
            return pos.relative(s.getValue(BedBlock.PART) == BedPart.FOOT ? s.getValue(BedBlock.FACING) : s.getValue(BedBlock.FACING).getOpposite());
        return pos;
    }
    private static boolean allowed(ServerLevel level, BlockPos pos, Entity actor, Action action, BlockState state) {
        if (!permits(level,pos,actor,action,state)) return false;
        BlockPos other = partner(pos,state);
        return other.equals(pos) || !crosses(level,pos,other) && permits(level,other,actor,action,level.getBlockState(other));
    }
    @SubscribeEvent public static void destroy(BlockEvent.BreakEvent e) {
        if (e.getLevel() instanceof ServerLevel l && !allowed(l,e.getPos(),e.getPlayer(),Action.DESTROY,e.getState())) e.setCanceled(true);
    }
    @SubscribeEvent public static void place(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof ServerLevel l)) return;
        if (e instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (var snap : multi.getReplacedBlockSnapshots())
                if (!allowed(l,snap.getPos(),e.getEntity(),Action.BUILD,snap.getCurrentBlock())) { e.setCanceled(true); return; }
        } else if (!allowed(l,e.getPos(),e.getEntity(),Action.BUILD,e.getPlacedBlock())) e.setCanceled(true);
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel l)) return;
        var pos = e.getPos();
        var actor = e.getEntity();
        var state = l.getBlockState(pos);
        if (!allowed(l,pos,actor,Action.INTERACT,state)) e.setUseBlock(Event.Result.DENY);
        var stack = e.getItemStack();
        if (stack.isEmpty()) return;
        if (stack.getItem() instanceof BlockItem block) {
            var target = new BlockPlaceContext(actor,e.getHand(),stack,e.getHitVec()).getClickedPos();
            if (!permits(l,target,actor,Action.BUILD,block.getBlock().defaultBlockState())) e.setUseItem(Event.Result.DENY);
        } else if (!allowed(l,pos,actor,Action.ITEM_USE,state) || !permits(l,pos.relative(e.getHitVec().getDirection()),
                actor,Action.ITEM_USE,l.getBlockState(pos.relative(e.getHitVec().getDirection())))) e.setUseItem(Event.Result.DENY);
    }
    @SubscribeEvent public static void use(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getLevel() instanceof ServerLevel l)) return;
        var player = e.getEntity();
        var hit = player.pick(player.getBlockReach(), 1, true);
        if (!permits(l,player.blockPosition(),player,Action.ITEM_USE,l.getBlockState(player.blockPosition())))
            e.setCanceled(true);
        else if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            var pos = block.getBlockPos();
            var next = pos.relative(block.getDirection());
            if (!allowed(l,pos,player,Action.ITEM_USE,l.getBlockState(pos)) || !permits(l,next,player,Action.ITEM_USE,l.getBlockState(next)))
                e.setCanceled(true);
        }
    }
    @SubscribeEvent public static void bucket(FillBucketEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getTarget() instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return;
        var pos = hit.getBlockPos();
        var adjacent = pos.relative(hit.getDirection());
        if (!permits(level, pos, e.getEntity(), Action.ITEM_USE, level.getBlockState(pos))
                || !permits(level, adjacent, e.getEntity(), Action.ITEM_USE, level.getBlockState(adjacent)))
            e.setCanceled(true);
    }
    @SubscribeEvent public static void entity(PlayerInteractEvent.EntityInteract e) {
        if (e.getLevel() instanceof ServerLevel l && !permits(l,e.getTarget().blockPosition(),e.getEntity(),Action.INTERACT,
                l.getBlockState(e.getTarget().blockPosition()))) e.setCanceled(true);
    }
    @SubscribeEvent public static void specific(PlayerInteractEvent.EntityInteractSpecific e) {
        if (e.getLevel() instanceof ServerLevel l && !permits(l,e.getTarget().blockPosition(),e.getEntity(),Action.INTERACT,
                l.getBlockState(e.getTarget().blockPosition()))) e.setCanceled(true);
    }
    @SubscribeEvent public static void combat(net.minecraftforge.event.entity.living.LivingAttackEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer target) || !(e.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        Site a = site(attacker.serverLevel(),attacker.blockPosition());
        Site b = site(target.serverLevel(),target.blockPosition());
        boolean inArena = a != null && a.parcel.kind == Kind.ARENA;
        boolean targetInArena = b != null && b.parcel.kind == Kind.ARENA;
        if ((inArena || targetInArena) && !(inArena && targetInArena && a.parcel.id.equals(b.parcel.id))) e.setCanceled(true);
    }
    @SubscribeEvent public static void explosions(ExplosionEvent.Detonate e) {
        if (e.getLevel() instanceof ServerLevel l) e.getAffectedBlocks().removeIf(p -> boundaryId(l,p) != null);
    }
    @SubscribeEvent public static void piston(PistonEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel l)) return;
        var base = e.getPos();
        var head = base.relative(e.getDirection());
        if (crosses(l,base,head)) { e.setCanceled(true); return; }
        var resolver = e.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        for (var pos : resolver.getToPush()) {
            if (crosses(l,base,pos) || crosses(l,pos,pos.relative(resolver.getPushDirection()))) { e.setCanceled(true); return; }
        }
        for (var pos : resolver.getToDestroy()) if (crosses(l,base,pos)) { e.setCanceled(true); return; }
    }

    private record Visit(ResourceLocationKey position, UUID quarter) {}
    private record ResourceLocationKey(net.minecraft.resources.ResourceLocation dimension, BlockPos pos) {}
    private static final Map<ServerPlayer,Visit> visits = new WeakHashMap<>();
    @SubscribeEvent public static void entry(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer p) || p.tickCount % 10 != 0
                || !TerritoryConfig.ENTRY.get()) return;
        var position = new ResourceLocationKey(p.serverLevel().dimension().location(),p.blockPosition());
        var previous = visits.get(p);
        if (previous != null && position.equals(previous.position)) return;
        var s = site(p.serverLevel(),p.blockPosition());
        var quarter = s == null || s.parcel.boxes.isEmpty() ? null : s.parcel;
        if (quarter != null && (previous == null || !quarter.id.equals(previous.quarter)))
            p.displayClientMessage(PropertyCommands.msg("entry",quarter.name),true);
        visits.put(p,new Visit(position,quarter == null ? null : quarter.id));
    }
}
