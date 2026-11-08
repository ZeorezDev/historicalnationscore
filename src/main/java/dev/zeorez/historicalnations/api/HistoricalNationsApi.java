package dev.zeorez.historicalnations.api;

import dev.zeorez.historicalnations.core.WorldRegistry;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Obtain and use on the logical server thread. Returned records and lists are snapshots. */
public interface HistoricalNationsApi {
    static HistoricalNationsApi get(MinecraftServer server) {
        return WorldRegistry.get(server);
    }

    Optional<Town> getPlayerTown(UUID player);
    Optional<Nation> getPlayerNation(UUID player);
    Optional<Town> getTownById(UUID id);
    Optional<Nation> getNationById(UUID id);
    boolean isTownMayor(UUID player);
    boolean isNationLeader(UUID player);
    List<Town> getNationTowns(UUID nation);
    boolean isPlayerCitizenOfNation(UUID player, UUID nation);
}
