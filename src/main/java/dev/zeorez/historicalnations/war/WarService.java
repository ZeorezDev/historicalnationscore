package dev.zeorez.historicalnations.war;

import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.core.RuleViolation;
import dev.zeorez.historicalnations.core.SpawnPoint;
import dev.zeorez.historicalnations.core.Territory;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.TownTerritories;
import dev.zeorez.historicalnations.map.PoliticalMapServer;
import dev.zeorez.historicalnations.map.WorldBoundsEnforcer;
import dev.zeorez.historicalnations.war.WarModels.ActiveCaptureFlag;
import dev.zeorez.historicalnations.war.WarModels.FlagRunState;
import dev.zeorez.historicalnations.war.WarModels.OccupiedChunk;
import dev.zeorez.historicalnations.war.WarModels.PendingFlagCleanup;
import dev.zeorez.historicalnations.war.WarModels.TruceRecord;
import dev.zeorez.historicalnations.war.WarModels.WarRecord;
import dev.zeorez.historicalnations.war.WarModels.WarSide;
import dev.zeorez.historicalnations.war.WarModels.WarState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Authoritative server-thread orchestrator for Historical Nations Core v11.0.0 Flag War:
 * - War declarations, 24h preparation countdown, scheduled war windows, and defender logout grace
 * - Fence-based 3-block War Capture Flags (fence + 10-stage wool + torch) & nearby BossBar telemetry
 * - Temporary Occupation layer vs transactional OPAC Peace Treaty Annexation & 48h Truces
 */
public final class WarService {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int BOSSBAR_RADIUS_BLOCKS = 48;

    private static final Block[] PROGRESS_WOOLS = {
            Blocks.WHITE_WOOL,
            Blocks.LIGHT_GRAY_WOOL,
            Blocks.CYAN_WOOL,
            Blocks.LIGHT_BLUE_WOOL,
            Blocks.BLUE_WOOL,
            Blocks.PURPLE_WOOL,
            Blocks.MAGENTA_WOOL,
            Blocks.PINK_WOOL,
            Blocks.ORANGE_WOOL,
            Blocks.RED_WOOL
    };

    private static final Map<MinecraftServer, Map<UUID, Long>> NATION_LAST_ONLINE_MS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<MinecraftServer, Map<UUID, Long>> TOWN_LAST_ONLINE_MS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private WarService() {}

    public static boolean isWarSystemEnabled(WorldRegistry data) {
        return TerritoryConfig.WAR_ENABLED.get() && data.wars.isRuntimeEnabled();
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        NATION_LAST_ONLINE_MS.remove(event.getServer());
        TOWN_LAST_ONLINE_MS.remove(event.getServer());
    }

    /** Records online player heartbeats for defender offline-protection & logout-grace evaluation. */
    public static void recordDefenderOnline(MinecraftServer server, UUID nationId, UUID townId, long nowMs) {
        if (nationId != null) {
            NATION_LAST_ONLINE_MS.computeIfAbsent(server, s -> new HashMap<>()).put(nationId, nowMs);
        }
        if (townId != null) {
            TOWN_LAST_ONLINE_MS.computeIfAbsent(server, s -> new HashMap<>()).put(townId, nowMs);
        }
    }

    private static void refreshOnlineHeartbeats(MinecraftServer server, WorldRegistry data, long nowMs) {
        Map<UUID, Long> natMap = NATION_LAST_ONLINE_MS.computeIfAbsent(server, s -> new HashMap<>());
        Map<UUID, Long> townMap = TOWN_LAST_ONLINE_MS.computeIfAbsent(server, s -> new HashMap<>());
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.hasDisconnected()) continue;
            data.getPlayerTown(sp.getUUID()).ifPresent(t -> townMap.put(t.id(), nowMs));
            data.getPlayerNation(sp.getUUID()).ifPresent(n -> natMap.put(n.id(), nowMs));
        }
    }

    public static boolean hasOnlineOrGraceDefender(MinecraftServer server, WorldRegistry data, WarRecord war,
                                                   WarSide defenderSide, UUID defendingTownId, long nowMs) {
        if (!TerritoryConfig.WAR_REQUIRE_ONLINE_DEFENDERS_TO_START.get()) {
            return true;
        }
        long graceMs = Math.max(0L, TerritoryConfig.WAR_DEFENDER_LOGOUT_GRACE_SECONDS.get()) * 1000L;

        if (war.independentDefenderTown && defenderSide == WarSide.DEFENDER) {
            for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                if (!sp.hasDisconnected() && data.getPlayerTown(sp.getUUID()).map(t -> t.id().equals(defendingTownId)).orElse(false)) {
                    return true;
                }
            }
            Long last = TOWN_LAST_ONLINE_MS.getOrDefault(server, Map.of()).get(defendingTownId);
            return last != null && (nowMs - last) <= graceMs;
        }

        var defenderNations = defenderSide == WarSide.DEFENDER ? war.defenderNations : war.attackerNations;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.hasDisconnected()) continue;
            UUID pNat = data.getPlayerNation(sp.getUUID()).map(Nation::id).orElse(null);
            if (pNat != null && defenderNations.contains(pNat)) {
                return true;
            }
        }
        Map<UUID, Long> natMap = NATION_LAST_ONLINE_MS.getOrDefault(server, Map.of());
        for (UUID defNat : defenderNations) {
            Long last = natMap.get(defNat);
            if (last != null && (nowMs - last) <= graceMs) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // 1. WAR DECLARATION, ALLY PARTICIPATION & ADMIN CONTROLS
    // =========================================================================

    public static WarRecord declareWar(ServerPlayer actor, String targetName) {
        MinecraftServer server = actor.server;
        WorldRegistry data = WorldRegistry.get(server);
        if (!isWarSystemEnabled(data)) throw new RuleViolation("war_disabled");

        Nation attacker = data.country(actor.getUUID());
        if (!data.isNationLeader(actor.getUUID())) throw new RuleViolation("leader_only");

        long nowMs = System.currentTimeMillis();
        refreshOnlineHeartbeats(server, data, nowMs);

        Optional<Nation> targetNationOpt = data.nations().stream()
                .filter(n -> n.name().equalsIgnoreCase(targetName.strip()))
                .findFirst();

        UUID defenderId;
        boolean independentTown = false;
        String defenderDisplayName;

        if (targetNationOpt.isPresent()) {
            Nation defender = targetNationOpt.get();
            if (attacker.id().equals(defender.id())) throw new RuleViolation("same_nation");
            if (data.wars.areAllied(attacker.id(), defender.id())) throw new RuleViolation("war_target_allied");
            Optional<TruceRecord> truce = data.wars.activeTruce(attacker.id(), defender.id(), nowMs);
            if (truce.isPresent()) throw new RuleViolation("war_truce_active");
            if (data.wars.findOngoingWarBetween(attacker.id(), defender.id(), false).isPresent()) {
                throw new RuleViolation("war_already_active");
            }
            defenderId = defender.id();
            defenderDisplayName = defender.name();
        } else if (TerritoryConfig.WAR_ALLOW_INDEPENDENT_TOWNS.get()) {
            Town targetTown = data.namedTown(targetName);
            if (data.getTownNation(targetTown.id()).isPresent()) {
                throw new RuleViolation("war_declare_nation_instead");
            }
            if (data.wars.findOngoingWarBetween(attacker.id(), targetTown.id(), true).isPresent()) {
                throw new RuleViolation("war_already_active");
            }
            defenderId = targetTown.id();
            independentTown = true;
            defenderDisplayName = targetTown.name();
        } else {
            throw new RuleViolation("unknown_nation");
        }

        int prepHours = Math.max(0, TerritoryConfig.WAR_PREPARATION_HOURS.get());
        long prepEndMs = nowMs + prepHours * 3_600_000L;
        WarState initialState = prepHours == 0 ? WarState.ACTIVE : WarState.PREPARING;

        WarRecord war = new WarRecord(
                UUID.randomUUID(),
                attacker.id(),
                defenderId,
                independentTown,
                nowMs,
                prepEndMs,
                initialState
        );
        data.wars.addWar(war);
        PoliticalMapServer.get(server).markWarOverlayDirty();

        Component broadcast = prepHours > 0
                ? Component.translatable("historical_nations.war_declared_prep", attacker.name(), defenderDisplayName, prepHours)
                : Component.translatable("historical_nations.war_declared_live", attacker.name(), defenderDisplayName);
        broadcastToServer(server, broadcast);
        return war;
    }

    public static WarRecord joinWar(ServerPlayer actor, String allyNationName) {
        MinecraftServer server = actor.server;
        WorldRegistry data = WorldRegistry.get(server);
        if (!isWarSystemEnabled(data)) throw new RuleViolation("war_disabled");

        Nation joiner = data.country(actor.getUUID());
        if (!data.isNationLeader(actor.getUUID())) throw new RuleViolation("leader_only");

        Nation ally = data.namedNation(allyNationName);
        if (joiner.id().equals(ally.id())) throw new RuleViolation("same_nation");
        if (!data.wars.areAllied(joiner.id(), ally.id())) throw new RuleViolation("not_allied");

        List<WarRecord> allyWars = data.wars.ongoingWarsOfNation(ally.id());
        if (allyWars.isEmpty()) throw new RuleViolation("no_active_war");
        WarRecord war = allyWars.get(0);
        if (war.involvesNation(joiner.id())) throw new RuleViolation("war_already_active");

        WarSide allySide = war.sideOfNation(ally.id());
        if (allySide == null) throw new RuleViolation("no_active_war");

        long nowMs = System.currentTimeMillis();
        var opposingNations = allySide == WarSide.ATTACKER ? war.defenderNations : war.attackerNations;
        for (UUID opp : opposingNations) {
            if (data.wars.areAllied(joiner.id(), opp)) throw new RuleViolation("war_target_allied");
            if (data.wars.activeTruce(joiner.id(), opp, nowMs).isPresent()) throw new RuleViolation("war_truce_active");
        }

        if (allySide == WarSide.ATTACKER) {
            war.attackerNations.add(joiner.id());
        } else {
            war.defenderNations.add(joiner.id());
        }
        war.peaceDraft.resetApprovals();
        data.setDirty();
        PoliticalMapServer.get(server).markWarOverlayDirty();

        broadcastToWarParticipants(server, data, war,
                Component.translatable("historical_nations.war_ally_joined", joiner.name(), ally.name()));
        return war;
    }

    // =========================================================================
    // 2. WAR CAPTURE FLAG PLACEMENT, DEFENSE & TICK ENGINE
    // =========================================================================

    public static boolean isAllowedFlagItem(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) return false;
        Block block = bi.getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) return false;
        String idStr = id.toString();
        if (idStr.equals(TerritoryConfig.WAR_FLAG_ITEM.get())) return true;
        for (String allowed : TerritoryConfig.WAR_ALLOWED_FLAG_ITEMS.get()) {
            if (idStr.equals(allowed)) return true;
        }
        return false;
    }

    public static boolean isPlayerTargetingActiveFlag(ServerPlayer player, ActiveCaptureFlag flag) {
        if (player == null || flag == null) return false;
        HitResult hit = player.pick(player.getBlockReach() + 1.0D, 1.0F, false);
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            return flag.ownsBlock(bhr.getBlockPos());
        }
        return false;
    }

    public static boolean canDestroyFlag(WorldRegistry data, ActiveCaptureFlag flag, ServerPlayer player) {
        if (player == null || flag == null) return false;
        if (player.hasPermissions(2)) return true;
        WarRecord war = data.wars.warById(flag.warId).orElse(null);
        if (war == null) return true;

        // If independent town war, citizens of the defending town can destroy the flag
        if (war.independentDefenderTown) {
            if (data.getPlayerTown(player.getUUID()).map(t -> t.id().equals(flag.defendingTownId)).orElse(false)) {
                return true;
            }
        }
        UUID playerNation = data.getPlayerNation(player.getUUID()).map(Nation::id).orElse(null);
        if (playerNation == null) return false;
        WarSide attackerSide = war.sideOfNation(flag.attackingNationId);
        WarSide playerSide = war.sideOfNation(playerNation);
        return attackerSide != null && playerSide == attackerSide.opposite();
    }

    /**
     * Validates all war, schedule, defender-presence, frontline, quota, sky-access, and building rules,
     * then constructs the 3-block War Capture Flag (Fence + Wool + Torch) on the server thread.
     */
    public static ActiveCaptureFlag placeCaptureFlag(
            ServerPlayer player,
            ServerLevel level,
            BlockPos basePos,
            Block fenceBlock
    ) {
        MinecraftServer server = level.getServer();
        WorldRegistry data = WorldRegistry.get(server);
        if (!isWarSystemEnabled(data)) throw new RuleViolation("war_disabled");
        if (!player.isAlive() || player.isSpectator()) throw new RuleViolation("invalid_actor");

        Nation attackingNation = data.country(player.getUUID());
        ResourceLocation dim = level.dimension().location();
        int cx = basePos.getX() >> 4;
        int cz = basePos.getZ() >> 4;
        Location chunkLoc = new Location(dim, cx, cz);

        if (!WorldBoundsEnforcer.isChunkInsideBounds(server, dim, cx, cz)) {
            throw new RuleViolation("outside_world_bounds");
        }
        if (isInsideSpawnProtection(server, level, basePos)) {
            throw new RuleViolation("war_spawn_protected");
        }

        TownTerritories territories = new TownTerritories(server);
        Territory link = territories.verified(chunkLoc);
        if (link == null) {
            // Unclaimed or personal OPAC claim: never treat personal claims as war targets
            throw new RuleViolation("war_not_enemy_territory");
        }

        Town targetTown = data.getTownById(link.town()).orElseThrow(() -> new RuleViolation("war_not_enemy_territory"));
        UUID targetTownNationId = data.getTownNation(targetTown.id()).map(Nation::id).orElse(null);
        OccupiedChunk existingOcc = data.wars.occupationAt(chunkLoc);

        // Determine war & whether this is an offensive capture or a counter-attack liberation of occupied homeland
        WarRecord war = null;
        WarSide attackerSide = null;
        UUID effectiveDefenderNationId = targetTownNationId;

        if (existingOcc != null) {
            if (attackingNation.id().equals(existingOcc.occupyingNationId())) {
                throw new RuleViolation("war_already_occupied");
            }
            WarRecord occWar = data.wars.warById(existingOcc.warId()).orElse(null);
            if (occWar != null && occWar.isOngoing() && occWar.involvesNation(attackingNation.id())) {
                WarSide mySide = occWar.sideOfNation(attackingNation.id());
                WarSide occSide = occWar.sideOfNation(existingOcc.occupyingNationId());
                if (mySide != null && occSide == mySide.opposite()) {
                    war = occWar;
                    attackerSide = mySide;
                    effectiveDefenderNationId = existingOcc.occupyingNationId();
                }
            }
        } else {
            if (targetTownNationId != null) {
                if (attackingNation.id().equals(targetTownNationId)) {
                    throw new RuleViolation("war_not_enemy_territory");
                }
                for (WarRecord candidate : data.wars.ongoingWarsOfNation(attackingNation.id())) {
                    WarSide mySide = candidate.sideOfNation(attackingNation.id());
                    WarSide theirSide = candidate.sideOfNation(targetTownNationId);
                    if (mySide != null && theirSide == mySide.opposite()) {
                        war = candidate;
                        attackerSide = mySide;
                        break;
                    }
                }
            } else if (TerritoryConfig.WAR_ALLOW_INDEPENDENT_TOWNS.get()) {
                war = data.wars.findOngoingWarBetween(attackingNation.id(), targetTown.id(), true).orElse(null);
                if (war != null) attackerSide = WarSide.ATTACKER;
            }
        }

        if (war == null || attackerSide == null) {
            throw new RuleViolation("war_not_at_war");
        }
        if (war.state == WarState.PREPARING) {
            long nowMs = System.currentTimeMillis();
            if (nowMs < war.preparationEndsAtEpochMs) {
                throw new RuleViolation("war_prep_active");
            }
            war.state = WarState.ACTIVE;
            data.setDirty();
        }
        if (war.state != WarState.ACTIVE) {
            throw new RuleViolation("war_paused");
        }

        if (TerritoryConfig.WAR_PAUSE_WHEN_WINDOW_CLOSED.get() && !WarSchedule.isWarWindowOpen()) {
            throw new RuleViolation("war_window_closed");
        }

        long nowMs = System.currentTimeMillis();
        refreshOnlineHeartbeats(server, data, nowMs);
        if (!hasOnlineOrGraceDefender(server, data, war, attackerSide.opposite(), targetTown.id(), nowMs)) {
            throw new RuleViolation("war_defender_offline");
        }

        if (data.wars.flagAtChunk(chunkLoc) != null) {
            throw new RuleViolation("war_chunk_already_attacked");
        }
        if (data.wars.countActiveFlagsByPlayer(player.getUUID()) >= TerritoryConfig.WAR_MAX_ACTIVE_FLAGS_PER_PLAYER.get()) {
            throw new RuleViolation("war_player_flag_limit");
        }
        if (data.wars.countActiveFlagsByNation(attackingNation.id()) >= TerritoryConfig.WAR_MAX_ACTIVE_FLAGS_PER_NATION.get()) {
            throw new RuleViolation("war_nation_flag_limit");
        }

        // Frontline / staging validation (for counter-attack liberation of homeland chunk, also allow adjacent homeland/occupied)
        if (!WarFrontier.canAttackChunk(server, data, territories, war, attackingNation.id(), attackerSide, chunkLoc)) {
            throw new RuleViolation("war_not_border");
        }

        // Validate physical flag site (ground support, not underwater, clear space for fence+wool+torch, direct sky access)
        validateFlagPlacementSite(level, data, basePos);

        Block baseBlock = fenceBlock instanceof FenceBlock ? fenceBlock : Blocks.OAK_FENCE;
        ResourceLocation fenceId = BuiltInRegistries.BLOCK.getKey(baseBlock);

        level.setBlock(basePos, baseBlock.defaultBlockState(), 3);
        level.setBlock(basePos.above(), PROGRESS_WOOLS[0].defaultBlockState(), 3);
        level.setBlock(basePos.above(2), Blocks.TORCH.defaultBlockState(), 3);

        int reqTicks = Math.max(5, TerritoryConfig.WAR_CAPTURE_TIME_SECONDS.get()) * 20;
        ActiveCaptureFlag flag = new ActiveCaptureFlag(
                UUID.randomUUID(),
                war.warId,
                chunkLoc,
                basePos,
                fenceId,
                player.getUUID(),
                attackingNation.id(),
                targetTown.id(),
                effectiveDefenderNationId,
                reqTicks,
                0,
                0,
                FlagRunState.CAPTURING
        );
        flag.lastWoolStage = 0;
        data.wars.registerFlag(flag);
        war.stats.flagsPlaced++;
        data.setDirty();

        ensureBossBar(server, data, flag);
        PoliticalMapServer.get(server).markWarOverlayDirty();

        broadcastToWarParticipants(server, data, war, Component.translatable(
                "historical_nations.war_flag_placed",
                attackingNation.name(), targetTown.name(), basePos.getX(), basePos.getZ()));
        return flag;
    }

    private static void validateFlagPlacementSite(ServerLevel level, WorldRegistry data, BlockPos basePos) {
        if (basePos.getY() <= level.getMinBuildHeight() + 1 || basePos.getY() + 3 >= level.getMaxBuildHeight()) {
            throw new RuleViolation("war_invalid_flag_spot");
        }
        // Prevent overlapping another nearby flag's no-build buffer
        int noBuild = TerritoryConfig.WAR_FLAG_NO_BUILD_RADIUS.get();
        ResourceLocation dim = level.dimension().location();
        for (ActiveCaptureFlag other : data.wars.activeFlags()) {
            if (!other.chunk.dimension().equals(dim)) continue;
            if (Math.abs(other.basePos.getX() - basePos.getX()) <= noBuild * 2
                    && Math.abs(other.basePos.getZ() - basePos.getZ()) <= noBuild * 2) {
                throw new RuleViolation("war_invalid_flag_spot");
            }
        }

        BlockPos below = basePos.below();
        BlockState belowState = level.getBlockState(below);
        if (belowState.isAir() || !belowState.getFluidState().isEmpty() || belowState.is(BlockTags.LEAVES)) {
            throw new RuleViolation("war_invalid_flag_spot");
        }

        for (int dy = 0; dy <= 2; dy++) {
            BlockPos p = basePos.above(dy);
            BlockState st = level.getBlockState(p);
            if (!st.getFluidState().isEmpty()) {
                throw new RuleViolation("war_no_sky"); // Reject underwater placement
            }
            if (!st.isAir() && !st.canBeReplaced()) {
                throw new RuleViolation("war_invalid_flag_spot");
            }
        }

        // Prevent placing inside enclosed 3D quarters (apartments/jails/embassies/buildings)
        if (dev.zeorez.historicalnations.property.PropertyBook.lookup(level.getServer(), dim, basePos, true).isPresent()) {
            throw new RuleViolation("war_inside_building");
        }

        if (TerritoryConfig.WAR_REQUIRE_SKY_ACCESS.get()) {
            int maxY = level.getMaxBuildHeight();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int y = basePos.getY() + 3; y < maxY; y++) {
                cursor.set(basePos.getX(), y, basePos.getZ());
                BlockState above = level.getBlockState(cursor);
                if (!above.isAir() || !above.getFluidState().isEmpty()) {
                    throw new RuleViolation("war_no_sky");
                }
            }
        }
    }

    private static boolean isInsideSpawnProtection(MinecraftServer server, ServerLevel level, BlockPos pos) {
        if (level != server.overworld()) return false;
        BlockPos spawn = level.getSharedSpawnPos();
        int chunkRadius = TerritoryConfig.WAR_SPAWN_PROTECTION_RADIUS_CHUNKS.get();
        if (chunkRadius > 0) {
            int scx = spawn.getX() >> 4;
            int scz = spawn.getZ() >> 4;
            int pcx = pos.getX() >> 4;
            int pcz = pos.getZ() >> 4;
            if (Math.abs(pcx - scx) <= chunkRadius && Math.abs(pcz - scz) <= chunkRadius) {
                // Only treat world spawn chunks as spawn-protected if they are NOT claimed by a player town
                if (WorldRegistry.get(server).territory(new Location(level.dimension().location(), pcx, pcz)) == null) {
                    return true;
                }
            }
        }
        int vanillaRadius = server.getSpawnProtectionRadius();
        if (vanillaRadius > 0) {
            if (Math.max(Math.abs(pos.getX() - spawn.getX()), Math.abs(pos.getZ() - spawn.getZ())) <= vanillaRadius) {
                return WorldRegistry.get(server).territory(new Location(level.dimension().location(), pos.getX() >> 4, pos.getZ() >> 4)) == null;
            }
        }
        return false;
    }

    public static void destroyFlagByDefender(ServerLevel level, ActiveCaptureFlag flag, ServerPlayer defender) {
        MinecraftServer server = level.getServer();
        WorldRegistry data = WorldRegistry.get(server);
        ActiveCaptureFlag removed = data.wars.unregisterFlag(flag.chunk);
        if (removed == null) return;

        clearFlagBlocksOrDefer(server, data, removed);
        WarRecord war = data.wars.warById(removed.warId).orElse(null);
        if (war != null) {
            war.stats.flagsDefended++;
            data.setDirty();
            String townName = data.getTownById(removed.defendingTownId).map(Town::name).orElse("Town");
            String who = defender != null ? defender.getGameProfile().getName() : "Savunma";
            broadcastToWarParticipants(server, data, war, Component.translatable(
                    "historical_nations.war_flag_defended", who, townName, removed.basePos.getX(), removed.basePos.getZ()));
        }
        PoliticalMapServer.get(server).markWarOverlayDirty();
    }

    public static void cancelFlagSystem(MinecraftServer server, WorldRegistry data, ActiveCaptureFlag flag, String reasonKey) {
        ActiveCaptureFlag removed = data.wars.unregisterFlag(flag.chunk);
        if (removed == null) return;
        clearFlagBlocksOrDefer(server, data, removed);
        WarRecord war = data.wars.warById(removed.warId).orElse(null);
        if (war != null && reasonKey != null) {
            String townName = data.getTownById(removed.defendingTownId).map(Town::name).orElse("Town");
            broadcastToWarParticipants(server, data, war, Component.translatable(
                    "historical_nations." + reasonKey, townName, removed.basePos.getX(), removed.basePos.getZ()));
        }
        PoliticalMapServer.get(server).markWarOverlayDirty();
    }

    public static void clearFlagBlocksOrDefer(MinecraftServer server, WorldRegistry data, ActiveCaptureFlag flag) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, flag.chunk.dimension()));
        if (level != null && level.hasChunk(flag.chunk.x(), flag.chunk.z())) {
            removeThreeFlagBlocks(level, flag.basePos);
        } else {
            data.wars.addPendingCleanup(new PendingFlagCleanup(flag.chunk.dimension(), flag.basePos));
        }
    }

    public static void removeThreeFlagBlocks(ServerLevel level, BlockPos basePos) {
        // Remove top-down so torch does not pop off as an item drop when wool disappears!
        BlockPos torch = basePos.above(2);
        BlockPos wool = basePos.above();
        if (level.getBlockState(torch).is(Blocks.TORCH)) {
            level.setBlock(torch, Blocks.AIR.defaultBlockState(), 3);
        }
        if (level.getBlockState(wool).is(BlockTags.WOOL)) {
            level.setBlock(wool, Blocks.AIR.defaultBlockState(), 3);
        }
        if (level.getBlockState(basePos).getBlock() instanceof FenceBlock) {
            level.setBlock(basePos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** Server tick hook: updates ONLY active wars and active capture flags (O(activeFlags), zero world scans). */
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.overworld() == null) return;

        WorldRegistry data = WorldRegistry.get(server);
        int tickCount = server.getTickCount();
        long nowMs = System.currentTimeMillis();

        if (tickCount % 20 == 0) {
            refreshOnlineHeartbeats(server, data, nowMs);
            // Transition PREPARING wars whose preparation timer has completed
            for (WarRecord war : data.wars.ongoingWars()) {
                if (war.state == WarState.PREPARING && nowMs >= war.preparationEndsAtEpochMs) {
                    war.state = WarState.ACTIVE;
                    data.setDirty();
                    PoliticalMapServer.get(server).markWarOverlayDirty();
                    String attName = data.getNationById(war.primaryAttackerId).map(Nation::name).orElse("?");
                    String defName = war.independentDefenderTown
                            ? data.getTownById(war.primaryDefenderId).map(Town::name).orElse("?")
                            : data.getNationById(war.primaryDefenderId).map(Nation::name).orElse("?");
                    broadcastToWarParticipants(server, data, war,
                            Component.translatable("historical_nations.war_prep_finished", attName, defName));
                }
            }
        }

        if (data.wars.activeFlags().isEmpty()) return;

        boolean systemEnabled = isWarSystemEnabled(data);
        boolean windowOpen = !TerritoryConfig.WAR_PAUSE_WHEN_WINDOW_CLOSED.get() || WarSchedule.isWarWindowOpen(Instant.ofEpochMilli(nowMs));
        boolean requireNearby = TerritoryConfig.WAR_REQUIRE_ATTACKER_NEARBY.get();
        double presenceRadiusSq = Math.pow(TerritoryConfig.WAR_ATTACKER_PRESENCE_RADIUS.get(), 2);
        int maxAbandonTicks = TerritoryConfig.WAR_ABANDON_CANCEL_SECONDS.get() * 20;

        List<ActiveCaptureFlag> snapshot = new ArrayList<>(data.wars.activeFlags());
        boolean dirtyProgress = false;

        for (ActiveCaptureFlag flag : snapshot) {
            WarRecord war = data.wars.warById(flag.warId).orElse(null);
            if (war == null || !war.isOngoing()) {
                cancelFlagSystem(server, data, flag, null);
                continue;
            }
            if (!systemEnabled || war.state != WarState.ACTIVE) {
                flag.runState = FlagRunState.PAUSED_SYSTEM;
                if (tickCount % 20 == 0) ensureBossBar(server, data, flag);
                continue;
            }
            if (!windowOpen) {
                flag.runState = FlagRunState.PAUSED_WINDOW_CLOSED;
                if (tickCount % 20 == 0) {
                    ensureBossBar(server, data, flag);
                    PoliticalMapServer.get(server).markWarOverlayDirty();
                }
                continue;
            }

            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, flag.chunk.dimension()));
            boolean attackerPresent = !requireNearby || isAnyAttackerNearby(level, data, war, flag, presenceRadiusSq);

            if (!attackerPresent) {
                flag.runState = FlagRunState.PAUSED_NO_ATTACKER;
                flag.unattendedTicks++;
                if (maxAbandonTicks > 0 && flag.unattendedTicks >= maxAbandonTicks) {
                    cancelFlagSystem(server, data, flag, "war_flag_abandoned");
                    continue;
                }
            } else {
                flag.runState = FlagRunState.CAPTURING;
                flag.unattendedTicks = 0;
                flag.elapsedTicks++;
                dirtyProgress = true;
            }

            if (flag.elapsedTicks >= flag.requiredTicks) {
                completeFlagCapture(server, data, war, flag);
                continue;
            }

            if (tickCount % 20 == 0) {
                updateFlagVisualsAndBossBar(server, level, data, flag);
            }
        }

        if (dirtyProgress) {
            data.setDirty();
            if (tickCount % 20 == 0) {
                PoliticalMapServer.get(server).markWarOverlayDirty();
            }
        }
    }

    private static boolean isAnyAttackerNearby(ServerLevel level, WorldRegistry data, WarRecord war,
                                               ActiveCaptureFlag flag, double radiusSq) {
        if (level == null) return false;
        WarSide flagSide = war.sideOfNation(flag.attackingNationId);
        if (flagSide == null) return false;
        double fx = flag.basePos.getX() + 0.5D;
        double fy = flag.basePos.getY() + 0.5D;
        double fz = flag.basePos.getZ() + 0.5D;
        for (ServerPlayer sp : level.players()) {
            if (!sp.isAlive() || sp.isSpectator() || sp.hasDisconnected()) continue;
            if (sp.distanceToSqr(fx, fy, fz) > radiusSq) continue;
            UUID pNat = data.getPlayerNation(sp.getUUID()).map(Nation::id).orElse(null);
            if (pNat != null && war.sideOfNation(pNat) == flagSide) {
                return true;
            }
        }
        return false;
    }

    private static void updateFlagVisualsAndBossBar(MinecraftServer server, ServerLevel level,
                                                    WorldRegistry data, ActiveCaptureFlag flag) {
        int stage = Math.min(PROGRESS_WOOLS.length - 1,
                (int) Math.floor(flag.progressFraction() * PROGRESS_WOOLS.length));
        if (level != null && level.hasChunk(flag.chunk.x(), flag.chunk.z())) {
            // Verify physical blocks weren't externally removed; update wool progress color
            if (stage != flag.lastWoolStage) {
                level.setBlock(flag.woolPos(), PROGRESS_WOOLS[stage].defaultBlockState(), 3);
                flag.lastWoolStage = stage;
            }
        }
        ensureBossBar(server, data, flag);
    }

    private static void ensureBossBar(MinecraftServer server, WorldRegistry data, ActiveCaptureFlag flag) {
        int pct = flag.progressPercent();
        int rem = flag.remainingSeconds();
        String mmss = String.format(Locale.ROOT, "%02d:%02d", rem / 60, rem % 60);
        String townName = data.getTownById(flag.defendingTownId).map(Town::name).orElse("Territory");

        Component title = switch (flag.runState) {
            case CAPTURING -> Component.translatable("historical_nations.war_bossbar_active", townName, pct, mmss);
            case PAUSED_NO_ATTACKER -> Component.translatable("historical_nations.war_bossbar_no_attacker", townName, pct, mmss);
            case PAUSED_WINDOW_CLOSED -> Component.translatable("historical_nations.war_bossbar_window_closed", townName, pct);
            case PAUSED_SYSTEM -> Component.translatable("historical_nations.war_bossbar_paused", townName, pct);
        };

        BossEvent.BossBarColor color = flag.runState == FlagRunState.CAPTURING
                ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.YELLOW;

        if (flag.bossBar == null) {
            flag.bossBar = new ServerBossEvent(title, color, BossEvent.BossBarOverlay.NOTCHED_10);
        } else {
            flag.bossBar.setName(title);
            flag.bossBar.setColor(color);
        }
        flag.bossBar.setProgress(flag.progressFraction());

        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, flag.chunk.dimension()));
        double maxDistSq = BOSSBAR_RADIUS_BLOCKS * BOSSBAR_RADIUS_BLOCKS;
        double fx = flag.basePos.getX() + 0.5D;
        double fy = flag.basePos.getY() + 0.5D;
        double fz = flag.basePos.getZ() + 0.5D;

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            boolean shouldSee = level != null && sp.serverLevel() == level && !sp.hasDisconnected()
                    && sp.distanceToSqr(fx, fy, fz) <= maxDistSq;
            boolean currentlySeeing = flag.bossBar.getPlayers().contains(sp);
            if (shouldSee && !currentlySeeing) {
                flag.bossBar.addPlayer(sp);
            } else if (!shouldSee && currentlySeeing) {
                flag.bossBar.removePlayer(sp);
            }
        }
    }

    private static void completeFlagCapture(MinecraftServer server, WorldRegistry data, WarRecord war, ActiveCaptureFlag flag) {
        ActiveCaptureFlag removed = data.wars.unregisterFlag(flag.chunk);
        if (removed == null) return;
        clearFlagBlocksOrDefer(server, data, removed);

        TownTerritories territories = new TownTerritories(server);
        Territory link = territories.verified(removed.chunk);
        if (link == null) {
            PoliticalMapServer.get(server).markWarOverlayDirty();
            return;
        }

        Town origTown = data.getTownById(link.town()).orElse(null);
        if (origTown == null) {
            PoliticalMapServer.get(server).markWarOverlayDirty();
            return;
        }
        UUID origNationId = data.getTownNation(origTown.id()).map(Nation::id).orElse(null);
        String attNationName = data.getNationById(removed.attackingNationId).map(Nation::name).orElse("?");

        // Check if this capture liberates our own (or allied co-belligerent's) homeland chunk from enemy occupation!
        WarSide capturerSide = war.sideOfNation(removed.attackingNationId);
        if (origNationId != null && capturerSide != null && war.sideOfNation(origNationId) == capturerSide) {
            data.wars.removeOccupation(war, removed.chunk);
            PoliticalMapServer.get(server).markWarOverlayDirty();
            broadcastToWarParticipants(server, data, war, Component.translatable(
                    "historical_nations.war_chunk_liberated",
                    attNationName, origTown.name(), removed.chunk.x(), removed.chunk.z()));
            return;
        }

        boolean isTownCenter = isTownCenterChunk(data, origTown.id(), removed.chunk);
        OccupiedChunk occ = new OccupiedChunk(
                removed.chunk,
                war.warId,
                origTown.id(),
                origNationId,
                removed.attackingNationId,
                removed.attackerPlayerId,
                System.currentTimeMillis(),
                isTownCenter
        );
        data.wars.putOccupation(war, occ);
        if (capturerSide == WarSide.ATTACKER) {
            war.stats.attackerChunksOccupied++;
        } else {
            war.stats.defenderChunksOccupied++;
        }
        if (isTownCenter) {
            war.stats.townCentersCaptured++;
        }
        data.setDirty();
        PoliticalMapServer.get(server).markWarOverlayDirty();

        broadcastToWarParticipants(server, data, war, Component.translatable(
                isTownCenter ? "historical_nations.war_town_center_occupied" : "historical_nations.war_chunk_occupied",
                attNationName, origTown.name(), removed.chunk.x(), removed.chunk.z()));
    }

    private static boolean isTownCenterChunk(WorldRegistry data, UUID townId, Location chunk) {
        SpawnPoint sp = data.townSpawn(townId);
        if (sp != null && sp.chunkLocation().equals(chunk)) return true;
        List<Location> locs = data.territoryLocations(townId);
        return !locs.isEmpty() && locs.get(0).equals(chunk);
    }

    // =========================================================================
    // 3. PEACE TREATY NEGOTIATION, DUAL RATIFICATION & OPAC ANNEXATION
    // =========================================================================

    private static WarSide requireTreatyNegotiator(WorldRegistry data, WarRecord war, UUID actorId) {
        if (!war.isOngoing()) throw new RuleViolation("no_active_war");
        if (war.independentDefenderTown) {
            Town defTown = data.getTownById(war.primaryDefenderId).orElse(null);
            if (defTown != null && defTown.mayor().equals(actorId)) {
                return WarSide.DEFENDER;
            }
        }
        Nation actorNation = data.country(actorId);
        if (!data.isNationLeader(actorId)) throw new RuleViolation("leader_only");
        if (war.primaryAttackerId.equals(actorNation.id())) return WarSide.ATTACKER;
        if (!war.independentDefenderTown && war.primaryDefenderId.equals(actorNation.id())) return WarSide.DEFENDER;
        throw new RuleViolation("war_primary_leader_only");
    }

    public static void setTreatyChunkTerm(ServerPlayer actor, UUID warId, Location chunk, UUID recipientTownId) {
        WorldRegistry data = WorldRegistry.get(actor.server);
        WarRecord war = data.wars.warById(warId).orElseThrow(() -> new RuleViolation("no_active_war"));
        WarSide side = requireTreatyNegotiator(data, war, actor.getUUID());

        OccupiedChunk occ = war.occupiedChunks.get(chunk);
        if (occ == null) {
            throw new RuleViolation("war_chunk_not_occupied_in_war");
        }

        if (recipientTownId == null) {
            war.peaceDraft.annexations.remove(chunk);
        } else {
            if (!TerritoryConfig.WAR_REQUIRE_PEACE_TREATY_FOR_ANNEXATION.get() && recipientTownId == null) {
                war.peaceDraft.annexations.remove(chunk);
            } else {
                Town recipientTown = data.getTownById(recipientTownId)
                        .orElseThrow(() -> new RuleViolation("unknown_town"));
                UUID recipientNation = data.getTownNation(recipientTown.id()).map(Nation::id).orElse(null);
                if (!occ.occupyingNationId().equals(recipientNation)) {
                    throw new RuleViolation("war_invalid_annex_recipient");
                }
                war.peaceDraft.annexations.put(chunk, recipientTownId);
            }
        }
        war.peaceDraft.recordMutation(side);
        data.setDirty();
    }

    public static void setTreatyBulkMode(ServerPlayer actor, UUID warId, boolean annexAllOccupiedBySide) {
        WorldRegistry data = WorldRegistry.get(actor.server);
        WarRecord war = data.wars.warById(warId).orElseThrow(() -> new RuleViolation("no_active_war"));
        WarSide side = requireTreatyNegotiator(data, war, actor.getUUID());

        if (!annexAllOccupiedBySide) {
            war.peaceDraft.annexations.clear();
        } else {
            for (OccupiedChunk occ : war.occupiedChunks.values()) {
                Nation occNat = data.getNationById(occ.occupyingNationId()).orElse(null);
                if (occNat != null && data.getTownById(occNat.capitalTown()).isPresent()) {
                    war.peaceDraft.annexations.put(occ.location(), occNat.capitalTown());
                }
            }
        }
        war.peaceDraft.recordMutation(side);
        data.setDirty();
    }

    public static void setTreatyTruceHours(ServerPlayer actor, UUID warId, int truceHours) {
        WorldRegistry data = WorldRegistry.get(actor.server);
        WarRecord war = data.wars.warById(warId).orElseThrow(() -> new RuleViolation("no_active_war"));
        WarSide side = requireTreatyNegotiator(data, war, actor.getUUID());
        int clamped = truceHours < 0 ? -1 : Math.min(720, truceHours);
        war.peaceDraft.truceHours = clamped;
        war.peaceDraft.recordMutation(side);
        data.setDirty();
    }

    /**
     * Approves or revokes approval for the exact expectedVersion of the peace treaty.
     * When both sides approve the same version, executes atomic OPAC annexation, clears occupations,
     * concludes the war, and establishes truces.
     */
    public static boolean approvePeaceTreaty(ServerPlayer actor, UUID warId, long expectedVersion, boolean approve) {
        MinecraftServer server = actor.server;
        WorldRegistry data = WorldRegistry.get(server);
        WarRecord war = data.wars.warById(warId).orElseThrow(() -> new RuleViolation("no_active_war"));
        WarSide side = requireTreatyNegotiator(data, war, actor.getUUID());

        if (war.peaceDraft.version != expectedVersion) {
            throw new RuleViolation("war_treaty_version_changed");
        }

        if (side == WarSide.ATTACKER) {
            war.peaceDraft.attackerApproved = approve;
        } else {
            war.peaceDraft.defenderApproved = approve;
        }
        data.setDirty();

        if (!war.peaceDraft.attackerApproved || !war.peaceDraft.defenderApproved) {
            String actorName = actor.getGameProfile().getName();
            broadcastToWarParticipants(server, data, war, Component.translatable(
                    approve ? "historical_nations.war_treaty_side_approved" : "historical_nations.war_treaty_side_unapproved",
                    actorName));
            return false;
        }

        // Both sides approved matching version! Verify all annexed chunks still belong to war.occupiedChunks
        Map<Location, UUID> verifiedAnnexations = new LinkedHashMap<>();
        for (var entry : war.peaceDraft.annexations.entrySet()) {
            OccupiedChunk occ = war.occupiedChunks.get(entry.getKey());
            if (occ == null) {
                war.peaceDraft.resetApprovals();
                data.setDirty();
                throw new RuleViolation("war_chunk_not_occupied_in_war");
            }
            Town targetTown = data.getTownById(entry.getValue())
                    .orElseThrow(() -> {
                        war.peaceDraft.resetApprovals();
                        data.setDirty();
                        return new RuleViolation("unknown_town");
                    });
            UUID targetNat = data.getTownNation(targetTown.id()).map(Nation::id).orElse(null);
            if (!occ.occupyingNationId().equals(targetNat)) {
                war.peaceDraft.resetApprovals();
                data.setDirty();
                throw new RuleViolation("war_invalid_annex_recipient");
            }
            verifiedAnnexations.put(entry.getKey(), entry.getValue());
        }

        // Execute atomic OPAC + WorldRegistry + PropertyBook annexation (throws & rolls back on any failure!)
        try {
            new TownTerritories(server).annexChunksTransactionally(verifiedAnnexations);
        } catch (RuntimeException ex) {
            war.peaceDraft.resetApprovals();
            data.setDirty();
            throw ex;
        }

        // Cancel any active capture flags belonging to this war
        for (ActiveCaptureFlag flag : new ArrayList<>(data.wars.activeFlagsOfWar(war.warId))) {
            cancelFlagSystem(server, data, flag, null);
        }

        int annexedCount = verifiedAnnexations.size();
        int returnedCount = Math.max(0, war.occupiedChunks.size() - annexedCount);
        war.stats.chunksAnnexed += annexedCount;

        data.wars.clearWarOccupations(war);
        war.state = WarState.CONCLUDED;
        war.concludedAtEpochMs = System.currentTimeMillis();

        int truceHours = war.peaceDraft.truceHours >= 0
                ? war.peaceDraft.truceHours
                : TerritoryConfig.WAR_TRUCE_HOURS.get();
        long truceExpiresAt = war.concludedAtEpochMs + Math.max(0L, truceHours) * 3_600_000L;
        if (truceHours > 0 && !war.independentDefenderTown) {
            for (UUID att : war.attackerNations) {
                for (UUID def : war.defenderNations) {
                    data.wars.putTruce(att, def, truceExpiresAt, war.warId);
                }
            }
        }

        data.setDirty();
        PoliticalMapServer.get(server).markWarOverlayDirty();

        String attName = data.getNationById(war.primaryAttackerId).map(Nation::name).orElse("?");
        String defName = war.independentDefenderTown
                ? data.getTownById(war.primaryDefenderId).map(Town::name).orElse("?")
                : data.getNationById(war.primaryDefenderId).map(Nation::name).orElse("?");
        broadcastToServer(server, Component.translatable(
                "historical_nations.war_peace_concluded",
                attName, defName, annexedCount, returnedCount, truceHours));
        return true;
    }

    // =========================================================================
    // 4. ADMIN OPERATIONS (SAFE ENABLE/DISABLE, STOP PRESERVING OCCUPATIONS, LIQUIDATE)
    // =========================================================================

    public static void adminSetEnabled(MinecraftServer server, boolean enabled) {
        WorldRegistry data = WorldRegistry.get(server);
        data.wars.setRuntimeEnabled(enabled);
        if (!enabled) {
            // Safely stop and clean up active capture flags while preserving war & occupation records
            for (ActiveCaptureFlag flag : new ArrayList<>(data.wars.activeFlags())) {
                cancelFlagSystem(server, data, flag, null);
            }
        }
        PoliticalMapServer.get(server).markWarOverlayDirty();
    }

    /** Stops active combat in a war (PAUSED) WITHOUT deleting occupied territory records (Phase 14). */
    public static WarRecord adminStopWar(MinecraftServer server, UUID warId) {
        WorldRegistry data = WorldRegistry.get(server);
        WarRecord war = data.wars.warById(warId).orElseThrow(() -> new RuleViolation("no_active_war"));
        if (!war.isOngoing()) throw new RuleViolation("no_active_war");
        for (ActiveCaptureFlag flag : new ArrayList<>(data.wars.activeFlagsOfWar(warId))) {
            cancelFlagSystem(server, data, flag, null);
        }
        war.state = WarState.PAUSED;
        data.setDirty();
        PoliticalMapServer.get(server).markWarOverlayDirty();
        return war;
    }

    /** Resumes an admin-stopped (PAUSED) war back into ACTIVE state. */
    public static WarRecord adminResumeWar(MinecraftServer server, UUID warId) {
        WorldRegistry data = WorldRegistry.get(server);
        WarRecord war = data.wars.warById(warId).orElseThrow(() -> new RuleViolation("no_active_war"));
        if (war.state != WarState.PAUSED) throw new RuleViolation("no_active_war");
        war.state = System.currentTimeMillis() < war.preparationEndsAtEpochMs ? WarState.PREPARING : WarState.ACTIVE;
        data.setDirty();
        PoliticalMapServer.get(server).markWarOverlayDirty();
        return war;
    }

    /** Explicitly liquidates/terminates a war and returns all occupied chunks to original control. */
    public static WarRecord adminLiquidateWar(MinecraftServer server, UUID warId) {
        WorldRegistry data = WorldRegistry.get(server);
        WarRecord war = data.wars.warById(warId).orElseThrow(() -> new RuleViolation("no_active_war"));
        for (ActiveCaptureFlag flag : new ArrayList<>(data.wars.activeFlagsOfWar(warId))) {
            cancelFlagSystem(server, data, flag, null);
        }
        data.wars.clearWarOccupations(war);
        war.state = WarState.CONCLUDED;
        war.concludedAtEpochMs = System.currentTimeMillis();
        data.setDirty();
        PoliticalMapServer.get(server).markWarOverlayDirty();
        return war;
    }

    // =========================================================================
    // 5. HELPERS
    // =========================================================================

    public static String warTitle(WorldRegistry data, WarRecord war) {
        String att = data.getNationById(war.primaryAttackerId).map(Nation::name).orElse("Unknown");
        String def = war.independentDefenderTown
                ? data.getTownById(war.primaryDefenderId).map(Town::name).orElse("Unknown")
                : data.getNationById(war.primaryDefenderId).map(Nation::name).orElse("Unknown");
        return att + " vs " + def;
    }

    public static void broadcastToWarParticipants(MinecraftServer server, WorldRegistry data, WarRecord war, Component msg) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.hasDisconnected()) continue;
            if (war.independentDefenderTown) {
                boolean inDefTown = data.getPlayerTown(sp.getUUID()).map(t -> t.id().equals(war.primaryDefenderId)).orElse(false);
                if (inDefTown) {
                    sp.sendSystemMessage(msg);
                    continue;
                }
            }
            UUID pNat = data.getPlayerNation(sp.getUUID()).map(Nation::id).orElse(null);
            if (pNat != null && war.involvesNation(pNat)) {
                sp.sendSystemMessage(msg);
            }
        }
    }

    public static void broadcastToServer(MinecraftServer server, Component msg) {
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!sp.hasDisconnected()) {
                sp.sendSystemMessage(msg);
            }
        }
    }
}
