package dev.zeorez.historicalnations.war;

import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.core.Territory;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.TownTerritories;
import dev.zeorez.historicalnations.map.WorldBoundsEnforcer;
import dev.zeorez.historicalnations.war.WarModels.OccupiedChunk;
import dev.zeorez.historicalnations.war.WarModels.WarRecord;
import dev.zeorez.historicalnations.war.WarModels.WarSide;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * Spatial frontline & wilderness-staging validator (Phase 6).
 *
 * Purely queries in-memory WorldRegistry, WarBook, and OPAC claim indexes — NEVER loads world chunks.
 */
public final class WarFrontier {
    private static final int[][] CARDINAL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] EIGHT_DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private WarFrontier() {}

    /**
     * Checks whether attackingNation (fighting on attackerSide of war) may legally initiate a
     * capture flag on targetChunk.
     */
    public static boolean canAttackChunk(
            MinecraftServer server,
            WorldRegistry data,
            TownTerritories territories,
            WarRecord war,
            UUID attackingNationId,
            WarSide attackerSide,
            Location targetChunk
    ) {
        if (!TerritoryConfig.WAR_BORDER_ONLY.get()) {
            return true;
        }

        int[][] dirs = TerritoryConfig.WAR_ALLOW_DIAGONAL_ADVANCE.get() ? EIGHT_DIRS : CARDINAL_DIRS;
        ResourceLocation dim = targetChunk.dimension();
        IServerClaimsManagerAPI opacClaims = OpenPACServerAPI.get(server).getServerClaimsManager();

        // 1. Direct frontline adjacency: immediately adjacent to attackingNation's own territory,
        //    its occupied chunks, or an allied co-belligerent's territory/occupied chunks.
        for (int[] d : dirs) {
            Location neighbor = new Location(dim, targetChunk.x() + d[0], targetChunk.z() + d[1]);
            if (isFriendlyStagingOrigin(data, territories, war, attackingNationId, attackerSide, neighbor)) {
                return true;
            }
        }

        // 2. Initial frontier staging across unclaimed wilderness (bounded BFS, default max 3 wilderness chunks).
        //    First verify that targetChunk itself is on the outer border of the enemy territory (adjacent to wilderness)
        //    so attackers can never jump over an outer enemy ring into an interior chunk.
        int maxWildernessChunks = Math.max(0, TerritoryConfig.WAR_FRONTIER_STAGING_DISTANCE_CHUNKS.get());
        if (maxWildernessChunks <= 0) {
            return false;
        }

        boolean isOuterEnemyBorder = false;
        for (int[] d : CARDINAL_DIRS) {
            Location neighbor = new Location(dim, targetChunk.x() + d[0], targetChunk.z() + d[1]);
            if (isPassableUnclaimedWilderness(server, territories, opacClaims, neighbor)) {
                isOuterEnemyBorder = true;
                break;
            }
        }
        if (!isOuterEnemyBorder) {
            return false;
        }

        record Step(int cx, int cz, int wildernessSteps) {}
        Queue<Step> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        visited.add(pack(targetChunk.x(), targetChunk.z()));

        // Seed BFS with adjacent unclaimed wilderness chunks (wildernessSteps = 1)
        for (int[] d : dirs) {
            int nx = targetChunk.x() + d[0];
            int nz = targetChunk.z() + d[1];
            Location nLoc = new Location(dim, nx, nz);
            if (isPassableUnclaimedWilderness(server, territories, opacClaims, nLoc) && visited.add(pack(nx, nz))) {
                queue.add(new Step(nx, nz, 1));
            }
        }

        while (!queue.isEmpty()) {
            Step cur = queue.poll();
            for (int[] d : dirs) {
                int nx = cur.cx() + d[0];
                int nz = cur.cz() + d[1];
                Location nLoc = new Location(dim, nx, nz);
                if (isFriendlyStagingOrigin(data, territories, war, attackingNationId, attackerSide, nLoc)) {
                    return true;
                }
                if (cur.wildernessSteps() < maxWildernessChunks
                        && visited.add(pack(nx, nz))
                        && isPassableUnclaimedWilderness(server, territories, opacClaims, nLoc)) {
                    queue.add(new Step(nx, nz, cur.wildernessSteps() + 1));
                }
            }
        }

        return false;
    }

    /**
     * Returns true if loc is controlled by attackingNation or by a nation that is BOTH allied with
     * attackingNation AND actively participating on the same side of this war.
     */
    public static boolean isFriendlyStagingOrigin(
            WorldRegistry data,
            TownTerritories territories,
            WarRecord war,
            UUID attackingNationId,
            WarSide attackerSide,
            Location loc
    ) {
        // Check active occupation first: if enemy occupied our border chunk, we cannot stage *from* it until liberated;
        // conversely, if we (or an allied co-belligerent) occupied an enemy chunk, we CAN advance from it!
        OccupiedChunk occ = data.wars.occupationAt(loc);
        if (occ != null) {
            return canStageFromNation(data, war, attackingNationId, attackerSide, occ.occupyingNationId());
        }

        Territory link = territories.verified(loc);
        if (link == null) return false;
        UUID ownerNationId = data.getTownNation(link.town()).map(Nation::id).orElse(null);
        if (ownerNationId == null) return false;
        return canStageFromNation(data, war, attackingNationId, attackerSide, ownerNationId);
    }

    private static boolean canStageFromNation(
            WorldRegistry data,
            WarRecord war,
            UUID attackingNationId,
            WarSide attackerSide,
            UUID candidateNationId
    ) {
        if (candidateNationId == null) return false;
        if (attackingNationId.equals(candidateNationId)) return true;
        // Allied territory staging requires BOTH active alliance AND war participation on the same side
        return war.sideOfNation(candidateNationId) == attackerSide
                && data.wars.areAllied(attackingNationId, candidateNationId);
    }

    private static boolean isPassableUnclaimedWilderness(
            MinecraftServer server,
            TownTerritories territories,
            IServerClaimsManagerAPI opacClaims,
            Location loc
    ) {
        if (!WorldBoundsEnforcer.isChunkInsideBounds(server, loc.dimension(), loc.x(), loc.z())) {
            return false;
        }
        if (territories.verified(loc) != null) {
            return false;
        }
        // Any personal/admin OPAC claim is not unclaimed wilderness
        return opacClaims.get(loc.dimension(), loc.x(), loc.z()) == null;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
