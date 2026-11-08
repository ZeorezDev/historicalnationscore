package dev.zeorez.historicalnations.integration;

import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import xaero.pac.common.claims.action.api.ClaimingAction;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.claims.tracker.api.IClaimsManagerListenerAPI;
import xaero.pac.common.event.api.v2.OPACServerAddonRegisterEvent;
import xaero.pac.common.server.ServerData;
import xaero.pac.common.server.claims.action.listener.api.IClaimActionListenerAPI;
import xaero.pac.common.server.claims.action.listener.override.api.ClaimActionPermissionOverride;
import xaero.pac.common.server.claims.action.listener.override.api.ClaimActionPermissionOverrideType;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;
import xaero.pac.common.server.claims.protection.override.api.*;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.util.ServerPlayerConfigUtils;
import xaero.pac.common.server.player.data.api.ServerPlayerDataAPI;

import java.util.UUID;

/** OPAC calls these hooks from its existing protection and claim pipelines. */
public final class TerritoryHooks implements IClaimsManagerListenerAPI, IChunkAccessOverriderAPI, IClaimActionListenerAPI {
    private static final ChunkAccessOverride CITIZEN = new ChunkAccessOverride(ChunkAccessOverrideType.ALLOW);
    private static final ClaimActionPermissionOverride OCCUPIED = new ClaimActionPermissionOverride(
            ClaimActionPermissionOverrideType.FORBID, Component.translatable("historical_nations.land_occupied"));
    private final MinecraftServer server;

    private TerritoryHooks(MinecraftServer server) { this.server = server; }

    public static void register(OPACServerAddonRegisterEvent event) {
        var context = event.getContext();
        var hooks = new TerritoryHooks(context.getServer());
        context.getClaimsManagerTrackerAPI().register(hooks);
        context.getChunkAccessOverriderManagerAPI().register(hooks);
        context.getClaimActionListenerManagerAPI().register(hooks);
    }

    @Override public String getName() { return "Historical Nations town territories"; }

    @Override
    public ChunkAccessOverride overrideChunkAccess(ResourceLocation dim, int x, int z, IPlayerConfigAPI config,
            Entity entity, UUID accessor, MinecraftServer server, ChunkAccessOverride current) {
        if (dim == null || config.getSubId() == null || !config.getSubId().startsWith("hn_")
                || !(entity instanceof ServerPlayer player) || !accessor.equals(player.getUUID())
                || !server.isSameThread() || current.getType() != ChunkAccessOverrideType.PASS) return current;
        var data = WorldRegistry.get(server);
        var link = data.territory(new Location(dim, x, z));
        if (link == null || !link.owner().equals(config.getPlayerId()) || link.subIndex() != config.getSubIndex()
                || !TownTerritories.profileName(link.town()).equals(config.getSubId())
                || config.isBeingDeleted() || link.owner().equals(accessor)
                || !data.getPlayerTown(accessor).map(t -> t.id().equals(link.town())).orElse(false)) return current;

        var opac = ServerData.from(server);
        if (!TownTerritories.matches(link, opac.getServerClaimsManager().get(dim, x, z))
                || ServerPlayerDataAPI.from(player).isClaimsNonallyMode()) return current;
        // ALLOW skips OPAC's usual full-access checks. Under quota pressure leave the
        // decision (including admin exceptions and cooldowns) entirely to OPAC.
        var configs = opac.getPlayerConfigManager();
        if (ServerPlayerConfigUtils.isOverEitherClaimingLimit(configs.getLoadedConfig(accessor), dim)
                || ServerPlayerConfigUtils.isOverEitherClaimingLimit(configs.getLoadedConfig(link.owner()), dim)) return current;
        return CITIZEN;
    }

    @Override
    public ClaimActionPermissionOverride overrideClaimingActionPermission(UUID actor, ResourceLocation dim,
            int x, int z, ClaimingAction action, IServerClaimsManagerAPI claims,
            ClaimActionPermissionOverride current, MinecraftServer server) {
        var link = WorldRegistry.get(server).territory(new Location(dim, x, z));
        if (link != null && !link.owner().equals(actor) && TownTerritories.matches(link, claims.get(dim, x, z)))
            return OCCUPIED;
        return current;
    }

    @Override
    public void onChunkChange(ResourceLocation dim, int x, int z, IPlayerChunkClaimAPI claim) {
        var pos = new Location(dim, x, z);
        var data = WorldRegistry.get(server);
        var link = data.territory(pos);
        if (link != null && !TownTerritories.matches(link, claim)) data.unlink(pos);
    }

    @Override public void onWholeRegionChange(ResourceLocation dim, int x, int z) {}
    @Override public void onDimensionChange(ResourceLocation dim) { new TownTerritories(server).reconcile(); }
    @Override public void handleSuccessfulClaimingAction(UUID actor, ResourceLocation dim, int x, int z,
            ClaimingAction action, IServerClaimsManagerAPI claims, MinecraftServer server) {}
}
