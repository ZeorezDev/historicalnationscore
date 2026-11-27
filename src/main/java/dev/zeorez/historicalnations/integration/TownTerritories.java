package dev.zeorez.historicalnations.integration;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.core.GoldCosts;
import dev.zeorez.historicalnations.core.SpawnPoint;
import dev.zeorez.historicalnations.core.Territory;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.claims.result.api.ClaimResult;
import xaero.pac.common.server.ServerData;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;
import xaero.pac.common.server.config.ServerConfig;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;
import xaero.pac.common.server.player.config.util.ServerPlayerConfigUtils;

import java.util.UUID;

/** All writes run on the logical server thread with the mayor's own OPAC identity. */
public final class TownTerritories {
    private final MinecraftServer server;
    private final WorldRegistry data;
    private final IServerClaimsManagerAPI claims;

    public TownTerritories(MinecraftServer server) {
        this.server = server;
        data = WorldRegistry.get(server);
        claims = OpenPACServerAPI.get(server).getServerClaimsManager();
    }

    static String profileName(UUID town) {
        return "hn_" + town.toString().replace("-", "").substring(0, 13);
    }

    public static Location location(ServerPlayer player) {
        var pos = player.chunkPosition();
        return new Location(player.serverLevel().dimension().location(), pos.x, pos.z);
    }

    static boolean matches(Territory link, IPlayerChunkClaimAPI claim) {
        return claim != null && link.owner().equals(claim.getPlayerId()) && link.subIndex() == claim.getSubConfigIndex();
    }

    /** Reconciliation removes only the HNC link, never an OPAC claim. */
    public Territory verified(Location pos) {
        Territory link = data.territory(pos);
        if (link == null) return null;
        if (matches(link, claims.get(pos.dimension(), pos.x(), pos.z()))) {
            var owner = OpenPACServerAPI.get(server).getPlayerConfigManager().getLoadedConfig(link.owner());
            var profile = owner.getSubConfig(profileName(link.town()));
            if (profile != null && !profile.isBeingDeleted() && profile.getSubIndex() == link.subIndex()) return link;
        }
        data.unlink(pos);
        LogUtils.getLogger().warn("Detached stale town territory {} at {}", link.town(), pos);
        return null;
    }

    public void reconcile() {
        data.territoryLocations().forEach(this::verified);
    }

    public int count(UUID town) {
        return (int) data.territoryLocations(town).stream().filter(pos -> verified(pos) != null).count();
    }

    public void transferMayor(ServerPlayer player, UUID successor) throws CommandSyntaxException {
        Town town = mayor(player);
        data.territoryLocations(town.id()).forEach(this::verified);
        data.transferMayor(player.getUUID(), successor);
    }

    private Town mayor(ServerPlayer player) throws CommandSyntaxException {
        Town town = data.home(player.getUUID());
        if (!town.mayor().equals(player.getUUID())) throw error("mayor_only");
        return town;
    }

    public Town createTown(ServerPlayer player, String name) throws CommandSyntaxException {
        String clean = data.checkCreateTown(player.getUUID(), name);
        Location pos = location(player);
        if (!dev.zeorez.historicalnations.map.WorldBoundsEnforcer.isChunkInsideBounds(server, pos.dimension(), pos.x(), pos.z())) {
            throw error("outside_world_bounds");
        }
        if (verified(pos) != null || claims.get(pos.dimension(), pos.x(), pos.z()) != null) throw error("land_occupied");
        if (TerritoryConfig.LIMIT.get() < 1) throw error("land_limit");

        int cost = GoldCosts.townCreationCost();
        if (cost > 0 && !GoldCosts.hasGold(player, cost)) {
            throw GoldCosts.insufficientGold(cost, GoldCosts.countGold(player));
        }

        UUID townId = UUID.randomUUID();
        int subIndex = claimInOpac(player, townId, pos);
        var link = new Territory(townId, player.getUUID(), subIndex, 0);
        if (!matches(link, claims.get(pos.dimension(), pos.x(), pos.z()))) throw error("land_changed");

        if (cost > 0 && !GoldCosts.takeGold(player, cost)) {
            rollbackOpacClaim(player, pos);
            throw GoldCosts.insufficientGold(cost, GoldCosts.countGold(player));
        }
        try {
            Town created = data.createTownWithTerritory(townId, player.getUUID(), clean, pos, link);
            data.putTownSpawn(created.id(), SpawnPoint.fromPlayer(player));
            return created;
        } catch (RuntimeException ex) {
            if (cost > 0) GoldCosts.giveGold(player, cost);
            rollbackOpacClaim(player, pos);
            throw ex;
        }
    }

    public void claim(ServerPlayer player) throws CommandSyntaxException {
        Town town = mayor(player);
        Location pos = location(player);
        if (!dev.zeorez.historicalnations.map.WorldBoundsEnforcer.isChunkInsideBounds(server, pos.dimension(), pos.x(), pos.z())) {
            throw error("outside_world_bounds");
        }
        if (verified(pos) != null || claims.get(pos.dimension(), pos.x(), pos.z()) != null) throw error("land_occupied");
        if (count(town.id()) >= TerritoryConfig.LIMIT.get()) throw error("land_limit");

        int cost = GoldCosts.claimCost();
        if (cost > 0 && !GoldCosts.hasGold(player, cost)) {
            throw GoldCosts.insufficientGold(cost, GoldCosts.countGold(player));
        }

        int subIndex = claimInOpac(player, town.id(), pos);
        var link = new Territory(town.id(), player.getUUID(), subIndex, cost);
        if (!matches(link, claims.get(pos.dimension(), pos.x(), pos.z()))) throw error("land_changed");

        if (cost > 0 && !GoldCosts.takeGold(player, cost)) {
            rollbackOpacClaim(player, pos);
            throw GoldCosts.insufficientGold(cost, GoldCosts.countGold(player));
        }
        try {
            data.link(pos, link);
        } catch (RuntimeException ex) {
            if (cost > 0) GoldCosts.giveGold(player, cost);
            rollbackOpacClaim(player, pos);
            throw ex;
        }
    }

    private int claimInOpac(ServerPlayer player, UUID townId, Location pos) throws CommandSyntaxException {
        // The public tryToClaim overload does not run this online-player permission check.
        // Use exactly the same check as OPAC 0.33.0's regular player claim command.
        var opac = ServerData.from(server);
        var denial = opac.getServerClaimsManager().getPermissionHandler().checkClaimingPermission(player, opac);
        if (denial != null) throw new SimpleCommandExceptionType(denial.message).create();
        if (!ServerConfig.CONFIG.claimsEnabled.get())
            throw new SimpleCommandExceptionType(ClaimResult.Type.CLAIMS_ARE_DISABLED.message).create();
        if (!opac.getServerClaimsManager().isClaimable(pos.dimension()))
            throw new SimpleCommandExceptionType(ClaimResult.Type.UNCLAIMABLE_DIMENSION.message).create();
        var owner = opac.getPlayerConfigManager().getLoadedConfig(player.getUUID());
        if (claims.getPlayerInfo(player.getUUID()).getClaimCount() >= claims.getPlayerFullClaimLimit(player)) {
            throw new SimpleCommandExceptionType(ClaimResult.Type.CLAIM_LIMIT_REACHED.message).create();
        }
        String name = profileName(townId);
        var profile = owner.getSubConfig(name);
        if (profile == null) {
            if (owner.getSubCount() >= owner.getSubConfigLimit()
                    || ServerPlayerConfigUtils.isOverEitherClaimingLimit(owner)) throw error("land_profile");
            profile = owner.createSubConfig(name);
        }
        if (profile == null || profile.isBeingDeleted() || !profile.getEffective(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS))
            throw error("land_profile");
        var result = claims.tryToClaim(pos.dimension(), player.getUUID(), profile.getSubIndex(),
                pos.dimension(), pos.x(), pos.z(), pos.x(), pos.z(), false);
        if (result.getResultType() != ClaimResult.Type.SUCCESSFUL_CLAIM)
            throw new SimpleCommandExceptionType(result.getMessage()).create();
        return profile.getSubIndex();
    }

    private void rollbackOpacClaim(ServerPlayer player, Location pos) {
        claims.tryToUnclaim(pos.dimension(), player.getUUID(), pos.dimension(),
                pos.x(), pos.z(), pos.x(), pos.z(), false);
    }

    /** Test-only hook to simulate an OPAC claim failure mid-annexation and prove atomic rollback. */
    private static volatile Location simulatedAnnexFailureChunk = null;

    public static void setSimulatedAnnexFailureChunk(Location loc) {
        simulatedAnnexFailureChunk = loc;
    }

    public void unclaim(ServerPlayer player) throws CommandSyntaxException {
        Town town = mayor(player);
        Location pos = location(player);
        if (data.wars.flagAtChunk(pos) != null || data.wars.occupationAt(pos) != null || data.wars.isTownInOngoingWar(town.id())) {
            throw error("war_chunk_locked");
        }
        Territory link = verified(pos);
        if (link == null || !town.id().equals(link.town()) || !player.getUUID().equals(link.owner()))
            throw error("land_not_yours");
        if (!release(player, town, pos)) throw error("land_not_yours");
        int refund = GoldCosts.unclaimRefund(link.paidGold());
        if (refund > 0) GoldCosts.giveGold(player, refund);
    }

    /**
     * Two-phase transactional wartime annexation across OPAC + WorldRegistry + PropertyBook (Phases 7 & 16).
     * - Charges ZERO Gold Ingots (war conquest transfer, not a vanilla claim purchase).
     * - Preserves all Plots, 3D Quarters, boxes, permissions, and CityEvaluation scores.
     * - Rolls back every modified chunk atomically if any OPAC or validation step fails.
     */
    public void annexChunksTransactionally(java.util.Map<Location, UUID> annexations) {
        if (annexations == null || annexations.isEmpty()) return;
        if (!server.isSameThread()) throw new IllegalStateException("Annexation must run on the server thread");
        if (!ServerConfig.CONFIG.claimsEnabled.get()) {
            throw new dev.zeorez.historicalnations.core.RuleViolation("annexation_failed");
        }

        var opac = ServerData.from(server);
        var configMgr = opac.getPlayerConfigManager();

        // Phase 1: Full pre-flight validation before any write
        java.util.Map<UUID, Integer> incomingPerTown = new java.util.HashMap<>();
        java.util.Map<UUID, Integer> incomingPerMayor = new java.util.HashMap<>();
        for (var entry : annexations.entrySet()) {
            Location loc = entry.getKey();
            UUID targetTownId = entry.getValue();
            Territory oldLink = verified(loc);
            if (oldLink == null) {
                throw new dev.zeorez.historicalnations.core.RuleViolation("annexation_failed");
            }
            Town targetTown = data.getTownById(targetTownId)
                    .orElseThrow(() -> new dev.zeorez.historicalnations.core.RuleViolation("unknown_town"));
            if (oldLink.town().equals(targetTown.id())) continue;
            if (!data.properties.canTransferPlot(loc)) {
                throw new dev.zeorez.historicalnations.core.RuleViolation("annexation_failed");
            }
            if (!opac.getServerClaimsManager().isClaimable(loc.dimension())) {
                throw new dev.zeorez.historicalnations.core.RuleViolation("annexation_failed");
            }

            incomingPerTown.merge(targetTown.id(), 1, Integer::sum);
            incomingPerMayor.merge(targetTown.mayor(), 1, Integer::sum);
        }

        int maxTownChunks = TerritoryConfig.LIMIT.get();
        for (var e : incomingPerTown.entrySet()) {
            if (count(e.getKey()) + e.getValue() > maxTownChunks) {
                throw new dev.zeorez.historicalnations.core.RuleViolation("land_limit");
            }
        }

        java.util.Map<UUID, Integer> targetSubIndexByTown = new java.util.HashMap<>();
        for (UUID townId : incomingPerTown.keySet()) {
            Town targetTown = data.getTownById(townId).orElseThrow();
            var mayorCfg = configMgr.getLoadedConfig(targetTown.mayor());
            if (mayorCfg == null || ServerPlayerConfigUtils.isOverEitherClaimingLimit(mayorCfg)) {
                throw new dev.zeorez.historicalnations.core.RuleViolation("annexation_failed");
            }
            int currentOpacClaims = claims.hasPlayerInfo(targetTown.mayor())
                    ? claims.getPlayerInfo(targetTown.mayor()).getClaimCount() : 0;
            int maxOpacClaims = ServerConfig.CONFIG.maxPlayerClaims.get()
                    + mayorCfg.getEffective(PlayerConfigOptions.BONUS_CHUNK_CLAIMS);
            if (currentOpacClaims + incomingPerMayor.getOrDefault(targetTown.mayor(), 0) > maxOpacClaims) {
                throw new dev.zeorez.historicalnations.core.RuleViolation("land_limit");
            }
            String subName = profileName(townId);
            var sub = mayorCfg.getSubConfig(subName);
            if (sub == null) {
                if (mayorCfg.getSubCount() >= mayorCfg.getSubConfigLimit()) {
                    throw new dev.zeorez.historicalnations.core.RuleViolation("land_profile");
                }
                sub = mayorCfg.createSubConfig(subName);
            }
            if (sub == null || sub.isBeingDeleted() || !sub.getEffective(PlayerConfigOptions.PROTECT_CLAIMED_CHUNKS)) {
                throw new dev.zeorez.historicalnations.core.RuleViolation("land_profile");
            }
            targetSubIndexByTown.put(townId, sub.getSubIndex());
        }

        // Phase 2: Atomic staged execution with full rollback journal
        record JournalEntry(Location loc, Territory oldLink) {}
        java.util.List<JournalEntry> committed = new java.util.ArrayList<>();

        TerritoryHooks.runInAnnexationTransfer(() -> {
            try {
                for (var entry : annexations.entrySet()) {
                    Location loc = entry.getKey();
                    UUID targetTownId = entry.getValue();
                    Territory oldLink = data.territory(loc);
                    Town targetTown = data.getTownById(targetTownId).orElseThrow();
                    if (oldLink.town().equals(targetTown.id())) continue;

                    if (loc.equals(simulatedAnnexFailureChunk)) {
                        throw new IllegalStateException("Simulated OPAC annexation failure at " + loc);
                    }

                    int newSubIdx = targetSubIndexByTown.get(targetTown.id());
                    claims.unclaim(loc.dimension(), loc.x(), loc.z());
                    IPlayerChunkClaimAPI claimed = claims.claim(
                            loc.dimension(), targetTown.mayor(), newSubIdx, loc.x(), loc.z(), false);
                    Territory newLink = new Territory(targetTown.id(), targetTown.mayor(), newSubIdx, 0);
                    if (claimed == null || !matches(newLink, claims.get(loc.dimension(), loc.x(), loc.z()))) {
                        // Restore current chunk in OPAC before rolling back prior entries
                        claims.unclaim(loc.dimension(), loc.x(), loc.z());
                        claims.claim(loc.dimension(), oldLink.owner(), oldLink.subIndex(), loc.x(), loc.z(), false);
                        throw new IllegalStateException("OPAC claim transfer verification failed at " + loc);
                    }

                    data.properties.copyReferencedPlotGroups(loc, oldLink.town(), targetTown.id());
                    data.replaceTerritoryForAnnexation(loc, newLink);
                    committed.add(new JournalEntry(loc, oldLink));
                }
            } catch (RuntimeException ex) {
                LogUtils.getLogger().warn("Rolling back {} annexed chunk(s) due to error: {}", committed.size(), ex.getMessage());
                for (int i = committed.size() - 1; i >= 0; i--) {
                    JournalEntry je = committed.get(i);
                    claims.unclaim(je.loc().dimension(), je.loc().x(), je.loc().z());
                    claims.claim(je.loc().dimension(), je.oldLink().owner(), je.oldLink().subIndex(),
                            je.loc().x(), je.loc().z(), false);
                    data.replaceTerritoryForAnnexation(je.loc(), je.oldLink());
                }
                if (ex instanceof dev.zeorez.historicalnations.core.RuleViolation rv) throw rv;
                throw new dev.zeorez.historicalnations.core.RuleViolation("annexation_failed");
            }
        });
    }

    private boolean release(ServerPlayer player, Town town, Location pos) throws CommandSyntaxException {
        var link = verified(pos);
        if (link == null || !town.id().equals(link.town()) || !player.getUUID().equals(link.owner())) return false;
        var from = location(player);
        var result = claims.tryToUnclaim(pos.dimension(), player.getUUID(), from.dimension(),
                from.x(), from.z(), pos.x(), pos.z(), false);
        if (result.getResultType() != ClaimResult.Type.SUCCESSFUL_UNCLAIM)
            throw new SimpleCommandExceptionType(result.getMessage()).create();
        data.unlink(pos);
        return true;
    }

    public void disband(ServerPlayer player) throws CommandSyntaxException {
        // Capital and authority checks precede the first physical write.
        Town town = data.checkDisband(player.getUUID());
        for (var pos : data.territoryLocations(town.id())) release(player, town, pos);
        data.disbandTown(player.getUUID());
    }

    public SpawnPoint setTownSpawn(ServerPlayer player) throws CommandSyntaxException {
        Town town = mayor(player);
        validateStandingSpot(player);
        Location pos = location(player);
        Territory link = verified(pos);
        if (link == null || !link.town().equals(town.id())) throw error("spawn_outside_town");
        SpawnPoint sp = SpawnPoint.fromPlayer(player);
        data.putTownSpawn(town.id(), sp);
        return sp;
    }

    public SpawnPoint setNationSpawn(ServerPlayer player) throws CommandSyntaxException {
        Nation nation = data.country(player.getUUID());
        if (!data.isNationLeader(player.getUUID())) throw error("leader_only");
        validateStandingSpot(player);
        Location pos = location(player);
        Territory link = verified(pos);
        if (link == null || !data.getTownNation(link.town()).map(n -> n.id().equals(nation.id())).orElse(false)) {
            throw error("spawn_outside_nation");
        }
        SpawnPoint sp = SpawnPoint.fromPlayer(player);
        data.putNationSpawn(nation.id(), sp);
        return sp;
    }

    public void initDefaultNationSpawn(ServerPlayer founder, Nation nation) {
        Location pos = location(founder);
        Territory link = verified(pos);
        if (link != null && data.getTownNation(link.town()).map(n -> n.id().equals(nation.id())).orElse(false)) {
            data.putNationSpawn(nation.id(), SpawnPoint.fromPlayer(founder));
            return;
        }
        SpawnPoint capSpawn = data.townSpawn(nation.capitalTown());
        if (capSpawn != null) {
            Territory capLink = verified(capSpawn.chunkLocation());
            if (capLink != null && capLink.town().equals(nation.capitalTown())) {
                data.putNationSpawn(nation.id(), capSpawn);
            }
        }
    }

    public void teleportToTownSpawn(ServerPlayer player, Town targetTown, boolean adminBypass) throws CommandSyntaxException {
        boolean sameTown = data.getPlayerTown(player.getUUID()).map(t -> t.id().equals(targetTown.id())).orElse(false);
        boolean sameNation = !sameTown && data.getTownNation(targetTown.id())
                .map(n -> data.isPlayerCitizenOfNation(player.getUUID(), n.id()))
                .orElse(false);
        if (!sameTown && !sameNation && !adminBypass) {
            throw error("spawn_restricted");
        }
        SpawnPoint sp = data.townSpawn(targetTown.id());
        if (sp == null) throw error("no_spawn");
        Territory link = verified(sp.chunkLocation());
        if (link == null || !link.town().equals(targetTown.id())) {
            throw error("spawn_invalid");
        }
        safeTeleport(player, sp);
    }

    public void teleportToNationSpawn(ServerPlayer player, Nation targetNation, boolean adminBypass) throws CommandSyntaxException {
        if (!data.isPlayerCitizenOfNation(player.getUUID(), targetNation.id()) && !adminBypass) {
            throw error("spawn_restricted");
        }
        SpawnPoint sp = data.nationSpawn(targetNation.id());
        if (sp == null) {
            sp = data.townSpawn(targetNation.capitalTown());
        }
        if (sp == null) throw error("no_spawn");
        Territory link = verified(sp.chunkLocation());
        if (link == null || !data.getTownNation(link.town()).map(n -> n.id().equals(targetNation.id())).orElse(false)) {
            throw error("spawn_invalid");
        }
        safeTeleport(player, sp);
    }

    private void validateStandingSpot(ServerPlayer player) throws CommandSyntaxException {
        if (!player.isAlive() || player.isSpectator()) throw error("invalid_actor");
        ServerLevel level = player.serverLevel();
        BlockPos feet = player.blockPosition();
        BlockPos head = feet.above();
        if (feet.getY() < level.getMinBuildHeight() || head.getY() >= level.getMaxBuildHeight()) {
            throw error("spawn_obstructed");
        }
        if (isHazardous(level, feet, head)) {
            throw error("spawn_obstructed");
        }
    }

    private void safeTeleport(ServerPlayer player, SpawnPoint sp) throws CommandSyntaxException {
        if (!player.isAlive() || player.isSpectator()) throw error("invalid_actor");
        ServerLevel targetLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION, sp.dimension()));
        if (targetLevel == null) throw error("spawn_invalid");
        BlockPos feet = sp.blockPos();
        BlockPos head = feet.above();
        if (feet.getY() < targetLevel.getMinBuildHeight() || head.getY() >= targetLevel.getMaxBuildHeight()) {
            throw error("spawn_obstructed");
        }
        if (isHazardous(targetLevel, feet, head)) {
            throw error("spawn_obstructed");
        }
        if (player.isPassenger()) {
            player.stopRiding();
        }
        player.fallDistance = 0.0F;
        if (player instanceof net.minecraftforge.common.util.FakePlayer || player.connection == null) {
            if (player.serverLevel() == targetLevel) {
                player.moveTo(sp.x(), sp.y(), sp.z(), sp.yaw(), sp.pitch());
            } else {
                throw error("spawn_invalid");
            }
        } else {
            player.teleportTo(targetLevel, sp.x(), sp.y(), sp.z(), sp.yaw(), sp.pitch());
        }
    }

    private static boolean isHazardous(ServerLevel level, BlockPos feet, BlockPos head) {
        return level.getFluidState(feet).is(FluidTags.LAVA)
                || level.getFluidState(head).is(FluidTags.LAVA)
                || level.getFluidState(feet.below()).is(FluidTags.LAVA)
                || level.getBlockState(feet).isSuffocating(level, feet)
                || level.getBlockState(head).isSuffocating(level, head);
    }

    private static CommandSyntaxException error(String key) {
        return new SimpleCommandExceptionType(Component.translatable("historical_nations." + key)).create();
    }
}
