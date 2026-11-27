package dev.zeorez.historicalnations.war;

import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.core.RuleViolation;
import dev.zeorez.historicalnations.core.Territory;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.TerritoryHooks;
import dev.zeorez.historicalnations.integration.TownTerritories;
import dev.zeorez.historicalnations.war.WarModels.ActiveCaptureFlag;
import dev.zeorez.historicalnations.war.WarModels.PendingFlagCleanup;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * High-priority Forge event protection & capture-flag interaction layer (Phases 4, 5, 8, 15, 16).
 *
 * Guarantees:
 * - Placing a fence in enemy territory converts into a War Capture Flag ONLY when all war rules pass.
 * - Normal fence placement outside war works 100% normally.
 * - Attackers NEVER gain chest access or general block build/break rights in enemy cities.
 * - Active flags enforce a no-build buffer around themselves so neither side can entomb/cheese the flag.
 * - Defenders breaking any of the 3 flag blocks cleanly cancel the attack with zero item duplication.
 * - Explosions (TNT, Creepers, Historical Artillery) hitting an active flag cancel it cleanly without drops
 *   and never bypass city building protection.
 */
public final class WarProtection {
    private WarProtection() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;

        WorldRegistry data = WorldRegistry.get(level.getServer());
        ResourceLocation dim = level.dimension().location();
        BlockPos clickedPos = e.getPos();

        // 1. Inspecting an active capture flag shows live telemetry in the action bar
        ActiveCaptureFlag clickedFlag = data.wars.flagAtBlock(dim, clickedPos);
        if (clickedFlag != null) {
            String townName = data.getTownById(clickedFlag.defendingTownId)
                    .map(dev.zeorez.historicalnations.api.Town::name).orElse("Territory");
            int rem = clickedFlag.remainingSeconds();
            player.displayClientMessage(Component.translatable(
                    "historical_nations.war_flag_inspect",
                    townName, clickedFlag.progressPercent(), String.format("%02d:%02d", rem / 60, rem % 60)), true);
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }

        ItemStack stack = e.getItemStack();
        BlockPlaceContext placeCtx = stack.getItem() instanceof BlockItem
                ? new BlockPlaceContext(player, e.getHand(), stack, e.getHitVec())
                : null;
        BlockPos placePos = placeCtx != null ? placeCtx.getClickedPos() : clickedPos.relative(e.getHitVec().getDirection());

        // 2. Enforce No-Build Buffer around every active capture flag (blocks both attackers and defenders from entombing!)
        if (stack.getItem() instanceof BlockItem && isInsideAnyFlagNoBuildZone(data, dim, placePos)) {
            player.displayClientMessage(Component.translatable("historical_nations.war_flag_nobuild"), true);
            e.setUseItem(Event.Result.DENY);
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
            return;
        }

        // 3. Check if target chunk is a verified HNC town territory where player is an outsider
        Location targetChunk = new Location(dim, placePos.getX() >> 4, placePos.getZ() >> 4);
        TownTerritories territories = new TownTerritories(level.getServer());
        Territory targetLink = territories.verified(targetChunk);
        Territory clickedLink = targetChunk.equals(new Location(dim, clickedPos.getX() >> 4, clickedPos.getZ() >> 4))
                ? targetLink
                : territories.verified(new Location(dim, clickedPos.getX() >> 4, clickedPos.getZ() >> 4));

        UUID playerTownId = data.getPlayerTown(player.getUUID()).map(dev.zeorez.historicalnations.api.Town::id).orElse(null);

        // Enforce chest/container protection against non-citizen belligerents on clickedPos
        if (clickedLink != null && !clickedLink.town().equals(playerTownId)) {
            if (TerritoryConfig.WAR_PROTECT_BUILDINGS.get() && !TerritoryConfig.WAR_ALLOW_CHEST_LOOTING.get()) {
                BlockState cs = level.getBlockState(clickedPos);
                if (cs.getMenuProvider(level, clickedPos) != null) {
                    e.setUseBlock(Event.Result.DENY);
                }
            }
        }

        // If not placing in an outsider's verified town chunk, let normal OPAC + municipal rules run untouched!
        if (targetLink == null || targetLink.town().equals(playerTownId)) {
            return;
        }

        // Outsider holding a valid fence item in MAIN_HAND on an enemy/foreign town chunk:
        if (e.getHand() == InteractionHand.MAIN_HAND && WarService.isAllowedFlagItem(stack)
                && stack.getItem() instanceof BlockItem blockItem) {
            UUID playerNationId = data.getPlayerNation(player.getUUID())
                    .map(dev.zeorez.historicalnations.api.Nation::id).orElse(null);
            // Only intercept as a war flag attempt if the player belongs to a nation that has an ongoing war!
            if (playerNationId != null && data.wars.isNationInOngoingWar(playerNationId)) {
                e.setUseBlock(Event.Result.DENY);
                try {
                    WarService.placeCaptureFlag(player, level, placePos, blockItem.getBlock());
                    if (!player.getAbilities().instabuild) {
                        stack.shrink(1);
                    }
                    player.swing(InteractionHand.MAIN_HAND, true);
                    e.setCanceled(true);
                    e.setCancellationResult(InteractionResult.SUCCESS);
                } catch (RuleViolation rv) {
                    player.displayClientMessage(Component.translatable("historical_nations." + rv.getMessage()), true);
                    e.setCanceled(true);
                    e.setCancellationResult(InteractionResult.FAIL);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!(e.getEntity() instanceof ServerPlayer player)) return;

        WorldRegistry data = WorldRegistry.get(level.getServer());
        ActiveCaptureFlag flag = data.wars.flagAtBlock(level.dimension().location(), e.getPos());
        if (flag == null) return;

        if (WarService.canDestroyFlag(data, flag, player)) {
            // Allow sister-town & allied defenders to destroy an enemy capture flag cleanly without OPAC blocking
            WarService.destroyFlagByDefender(level, flag, player);
            e.setCanceled(true);
        } else {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!(e.getPlayer() instanceof ServerPlayer player)) return;

        WorldRegistry data = WorldRegistry.get(level.getServer());
        ResourceLocation dim = level.dimension().location();
        ActiveCaptureFlag flag = data.wars.flagAtBlock(dim, e.getPos());
        if (flag != null) {
            if (WarService.canDestroyFlag(data, flag, player)) {
                WarService.destroyFlagByDefender(level, flag, player);
            }
            // Always cancel raw BreakEvent on flag blocks so wool/torch/fence can never duplicate!
            e.setCanceled(true);
            return;
        }

        // Prevent breaking the ground block directly supporting an active capture flag!
        ActiveCaptureFlag aboveFlag = data.wars.flagAtBlock(dim, e.getPos().above());
        if (aboveFlag != null && aboveFlag.basePos.equals(e.getPos().above())) {
            e.setCanceled(true);
            return;
        }

        // Reinforce wartime city building protection against non-citizen belligerents
        if (TerritoryConfig.WAR_PROTECT_BUILDINGS.get() && !TerritoryConfig.WAR_ALLOW_BLOCK_DESTRUCTION.get()
                && !player.hasPermissions(2)) {
            Location loc = new Location(dim, e.getPos().getX() >> 4, e.getPos().getZ() >> 4);
            Territory link = new TownTerritories(level.getServer()).verified(loc);
            if (link != null) {
                UUID playerTownId = data.getPlayerTown(player.getUUID())
                        .map(dev.zeorez.historicalnations.api.Town::id).orElse(null);
                if (!link.town().equals(playerTownId)) {
                    e.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        WorldRegistry data = WorldRegistry.get(level.getServer());
        ResourceLocation dim = level.dimension().location();

        if (isInsideAnyFlagNoBuildZone(data, dim, e.getPos())) {
            e.setCanceled(true);
            return;
        }

        if (e.getEntity() instanceof ServerPlayer player && !player.hasPermissions(2)
                && TerritoryConfig.WAR_PROTECT_BUILDINGS.get()) {
            Location loc = new Location(dim, e.getPos().getX() >> 4, e.getPos().getZ() >> 4);
            Territory link = new TownTerritories(level.getServer()).verified(loc);
            if (link != null) {
                UUID playerTownId = data.getPlayerTown(player.getUUID())
                        .map(dev.zeorez.historicalnations.api.Town::id).orElse(null);
                if (!link.town().equals(playerTownId)) {
                    e.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onFillBucket(FillBucketEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!(e.getTarget() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        WorldRegistry data = WorldRegistry.get(level.getServer());
        ResourceLocation dim = level.dimension().location();
        BlockPos pos = hit.getBlockPos();
        BlockPos adj = pos.relative(hit.getDirection());
        if (isInsideAnyFlagNoBuildZone(data, dim, pos) || isInsideAnyFlagNoBuildZone(data, dim, adj)) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onExplosionDetonate(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        if (!level.getServer().isSameThread()) return;

        WorldRegistry data = WorldRegistry.get(level.getServer());
        ResourceLocation dim = level.dimension().location();
        TownTerritories territories = new TownTerritories(level.getServer());

        Set<ActiveCaptureFlag> hitFlags = new HashSet<>();
        for (BlockPos pos : e.getAffectedBlocks()) {
            ActiveCaptureFlag flag = data.wars.flagAtBlock(dim, pos);
            if (flag != null) {
                hitFlags.add(flag);
            }
        }

        // Remove flag blocks from raw explosion drops (zero item duplication!) and cleanly neutralize hit flags
        if (!hitFlags.isEmpty()) {
            e.getAffectedBlocks().removeIf(p -> data.wars.flagAtBlock(dim, p) != null
                    || data.wars.flagAtBlock(dim, p.above()) != null);
            for (ActiveCaptureFlag flag : hitFlags) {
                ServerPlayer causer = e.getExplosion().getIndirectSourceEntity() instanceof ServerPlayer sp ? sp : null;
                WarService.destroyFlagByDefender(level, flag, causer);
            }
        }

        // Protect verified town buildings from TNT / modded artillery block destruction when protectBuildings is true
        if (TerritoryConfig.WAR_PROTECT_BUILDINGS.get() && !TerritoryConfig.WAR_ALLOW_BLOCK_DESTRUCTION.get()) {
            e.getAffectedBlocks().removeIf(p -> {
                Location loc = new Location(dim, p.getX() >> 4, p.getZ() >> 4);
                return territories.verified(loc) != null;
            });
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        var chunkPos = e.getChunk().getPos();
        level.getServer().execute(() -> {
            if (level.getServer().overworld() == null) return;
            WorldRegistry data = WorldRegistry.get(level.getServer());
            Location loc = new Location(level.dimension().location(), chunkPos.x, chunkPos.z);
            List<PendingFlagCleanup> pending = data.wars.consumePendingCleanupsForChunk(loc);
            if (!pending.isEmpty() && level.hasChunk(chunkPos.x, chunkPos.z)) {
                for (PendingFlagCleanup item : pending) {
                    WarService.removeThreeFlagBlocks(level, item.basePos());
                }
            }
        });
    }

    public static boolean isInsideAnyFlagNoBuildZone(WorldRegistry data, ResourceLocation dim, BlockPos pos) {
        if (data.wars.activeFlags().isEmpty()) return false;
        int r = TerritoryConfig.WAR_FLAG_NO_BUILD_RADIUS.get();
        for (ActiveCaptureFlag flag : data.wars.activeFlags()) {
            if (!flag.chunk.dimension().equals(dim)) continue;
            if (Math.abs(pos.getX() - flag.basePos.getX()) <= r
                    && Math.abs(pos.getZ() - flag.basePos.getZ()) <= r
                    && pos.getY() >= flag.basePos.getY() - 1) {
                return true;
            }
        }
        return false;
    }

    public static void clearScopedAccess() {
        TerritoryHooks.setScopedWarFlagAccess(false);
    }
}
