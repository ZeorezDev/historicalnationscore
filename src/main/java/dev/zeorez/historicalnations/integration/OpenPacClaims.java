package dev.zeorez.historicalnations.integration;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.util.Optional;
import java.util.UUID;

public final class OpenPacClaims {
    private OpenPacClaims() {}

    public record Owner(UUID id, String displayName) {}

    public static Optional<Owner> owner(MinecraftServer server, ResourceLocation dimension, ChunkPos position) {
        var manager = OpenPACServerAPI.get(server).getServerClaimsManager();
        var claim = manager.get(dimension, position);
        if (claim == null) return Optional.empty();
        UUID id = claim.getPlayerId();
        // getPlayerInfo is a get-or-create call. Only read an existing profile.
        String name = manager.hasPlayerInfo(id) ? manager.getPlayerInfo(id).getPlayerUsername() : null;
        return Optional.of(new Owner(id, name == null || name.isBlank() ? id.toString() : name));
    }
}
