package dev.zeorez.historicalnations.integration;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.core.Territory;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

    public void claim(ServerPlayer player) throws CommandSyntaxException {
        Town town = mayor(player);
        Location pos = location(player);
        if (verified(pos) != null || claims.get(pos.dimension(), pos.x(), pos.z()) != null) throw error("land_occupied");
        if (count(town.id()) >= TerritoryConfig.LIMIT.get()) throw error("land_limit");

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
        String name = profileName(town.id());
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
        var link = new Territory(town.id(), player.getUUID(), profile.getSubIndex());
        if (!matches(link, claims.get(pos.dimension(), pos.x(), pos.z()))) throw error("land_changed");
        data.link(pos, link);
    }

    public void unclaim(ServerPlayer player) throws CommandSyntaxException {
        Town town = mayor(player);
        Location pos = location(player);
        if (!release(player, town, pos)) throw error("land_not_yours");
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

    private static CommandSyntaxException error(String key) {
        return new SimpleCommandExceptionType(Component.translatable("historical_nations." + key)).create();
    }
}
