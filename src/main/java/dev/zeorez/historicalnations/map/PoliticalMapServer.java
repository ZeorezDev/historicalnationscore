package dev.zeorez.historicalnations.map;

import com.mojang.authlib.GameProfile;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.core.Territory;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.TownTerritories;
import dev.zeorez.historicalnations.map.MapModels.ClaimEntry;
import dev.zeorez.historicalnations.map.MapModels.DeltaPayload;
import dev.zeorez.historicalnations.map.MapModels.NationSummary;
import dev.zeorez.historicalnations.map.MapModels.ServerMapSettings;
import dev.zeorez.historicalnations.map.MapModels.TownSummary;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-side authoritative Political World Map coordinator.
 *
 * - Builds verified snapshots directly from WorldRegistry + OPAC without creating a second database.
 * - Never loads world chunks; tile PNGs are streamed asynchronously from disk.
 * - Computes incremental revision deltas whenever WorldRegistry changes and pushes bounded packets.
 */
public final class PoliticalMapServer {
    private static final int NATIONS_PER_BATCH = 200;
    private static final int TOWNS_PER_BATCH = 350;
    private static final int CLAIMS_PER_BATCH = 2000;
    private static final int MAX_DELTA_CLAIMS_BEFORE_RESYNC = 4000;

    private static final Map<MinecraftServer, PoliticalMapServer> INSTANCES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ExecutorService TILE_IO_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "HNC-MapTile-Reader");
        t.setDaemon(true);
        return t;
    });

    public record Snapshot(
            UUID mapId,
            long revision,
            ResourceLocation dimension,
            Map<UUID, NationSummary> nations,
            Map<UUID, TownSummary> towns,
            Map<Long, UUID> claims
    ) {}

    private final MinecraftServer server;
    private final Set<UUID> subscribers = new HashSet<>();
    private final Map<UUID, RateWindow> tileBudgets = new HashMap<>();
    private Snapshot current;
    private long nextRevision = 1L;

    private static final class RateWindow {
        int startTick;
        int requests;
    }

    private PoliticalMapServer(MinecraftServer server) {
        this.server = server;
    }

    public static PoliticalMapServer get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, PoliticalMapServer::new);
    }

    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 4 != 0) return;
        PoliticalMapServer mgr = get(server);
        mgr.syncIfDirty();
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && sp.server != null) {
            PoliticalMapServer mgr = INSTANCES.get(sp.server);
            if (mgr != null) {
                mgr.subscribers.remove(sp.getUUID());
                mgr.tileBudgets.remove(sp.getUUID());
            }
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        INSTANCES.remove(event.getServer());
    }

    public Snapshot ensureSnapshot() {
        WorldRegistry data = WorldRegistry.get(server);
        if (current == null || data.consumeMapDirty()) {
            Snapshot fresh = capture(data, nextRevision++);
            current = fresh;
        }
        return current;
    }

    public void syncIfDirty() {
        if (!TerritoryConfig.MAP_ENABLED.get()) return;
        WorldRegistry data = WorldRegistry.get(server);
        if (!data.consumeMapDirty()) return;

        Snapshot previous = current;
        Snapshot fresh = capture(data, nextRevision++);
        current = fresh;

        if (subscribers.isEmpty() || previous == null) return;
        subscribers.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        if (subscribers.isEmpty()) return;

        if (!TerritoryConfig.ENABLE_CLAIM_DELTA_UPDATES.get() || !previous.mapId().equals(fresh.mapId())) {
            for (UUID sub : List.copyOf(subscribers)) {
                ServerPlayer sp = server.getPlayerList().getPlayer(sub);
                if (sp != null) sendFullSnapshot(sp, fresh);
            }
            return;
        }

        DeltaPayload delta = computeDelta(previous, fresh);
        if (delta.isEmpty()) return;

        if (delta.changedClaims().size() > MAX_DELTA_CLAIMS_BEFORE_RESYNC) {
            for (UUID sub : List.copyOf(subscribers)) {
                ServerPlayer sp = server.getPlayerList().getPlayer(sub);
                if (sp != null) sendFullSnapshot(sp, fresh);
            }
            return;
        }

        byte[] encoded = delta.encodeCompressed();
        var pkt = new MapNetwork.MapDeltaPacket(fresh.mapId(), previous.revision(), fresh.revision(), encoded);
        for (UUID sub : List.copyOf(subscribers)) {
            ServerPlayer sp = server.getPlayerList().getPlayer(sub);
            if (sp != null && MapNetwork.canSend(sp)) {
                MapNetwork.sendToPlayer(sp, pkt);
            }
        }
    }

    public void handleSubscribe(ServerPlayer player, UUID knownMapId, long knownRevision, boolean subscribe) {
        if (player == null || player.hasDisconnected()) return;
        if (!TerritoryConfig.MAP_ENABLED.get()) return;
        if (!subscribe) {
            subscribers.remove(player.getUUID());
            return;
        }
        subscribers.add(player.getUUID());
        Snapshot snap = ensureSnapshot();
        if (snap.mapId().equals(knownMapId) && snap.revision() == knownRevision) {
            MapNetwork.sendToPlayer(player, new MapNetwork.MapUpToDatePacket(
                    snap.mapId(), snap.revision(), MapCalibration.fromServer(server), ServerMapSettings.fromConfig()));
            return;
        }
        sendFullSnapshot(player, snap);
    }

    public void sendFullSnapshot(ServerPlayer player, Snapshot snap) {
        if (!MapNetwork.canSend(player)) return;
        MapCalibration cal = MapCalibration.fromServer(server);
        ServerMapSettings settings = ServerMapSettings.fromConfig();

        List<NationSummary> nations = List.copyOf(snap.nations().values());
        List<TownSummary> towns = List.copyOf(snap.towns().values());
        List<ClaimEntry> claims = new ArrayList<>(snap.claims().size());
        snap.claims().forEach((packed, townId) ->
                claims.add(new ClaimEntry(MapModels.unpackChunkX(packed), MapModels.unpackChunkZ(packed), townId)));

        int nationBatches = ceilDiv(nations.size(), NATIONS_PER_BATCH);
        int townBatches = ceilDiv(towns.size(), TOWNS_PER_BATCH);
        int claimBatches = ceilDiv(claims.size(), CLAIMS_PER_BATCH);

        MapNetwork.sendToPlayer(player, new MapNetwork.MapSnapshotHeaderPacket(
                snap.mapId(), snap.revision(), cal, settings, nationBatches, townBatches, claimBatches));

        for (int b = 0; b < nationBatches; b++) {
            int from = b * NATIONS_PER_BATCH;
            int to = Math.min(from + NATIONS_PER_BATCH, nations.size());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeVarInt(to - from);
                for (int i = from; i < to; i++) nations.get(i).write(buf);
                MapNetwork.sendToPlayer(player, new MapNetwork.MapSnapshotPartPacket(
                        snap.mapId(), snap.revision(), MapNetwork.KIND_NATIONS, b, MapModels.compress(buf)));
            } finally {
                buf.release();
            }
        }

        for (int b = 0; b < townBatches; b++) {
            int from = b * TOWNS_PER_BATCH;
            int to = Math.min(from + TOWNS_PER_BATCH, towns.size());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeVarInt(to - from);
                for (int i = from; i < to; i++) towns.get(i).write(buf);
                MapNetwork.sendToPlayer(player, new MapNetwork.MapSnapshotPartPacket(
                        snap.mapId(), snap.revision(), MapNetwork.KIND_TOWNS, b, MapModels.compress(buf)));
            } finally {
                buf.release();
            }
        }

        for (int b = 0; b < claimBatches; b++) {
            int from = b * CLAIMS_PER_BATCH;
            int to = Math.min(from + CLAIMS_PER_BATCH, claims.size());
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeVarInt(to - from);
                for (int i = from; i < to; i++) claims.get(i).write(buf);
                MapNetwork.sendToPlayer(player, new MapNetwork.MapSnapshotPartPacket(
                        snap.mapId(), snap.revision(), MapNetwork.KIND_CLAIMS, b, MapModels.compress(buf)));
            } finally {
                buf.release();
            }
        }

        MapNetwork.sendToPlayer(player, new MapNetwork.MapSnapshotPartPacket(
                snap.mapId(), snap.revision(), MapNetwork.KIND_COMPLETE, 0, new byte[0]));
    }

    public void handleTileRequest(ServerPlayer player, UUID mapId, int lod, int tx, int tz) {
        if (player == null || player.hasDisconnected() || !TerritoryConfig.MAP_ENABLED.get()) return;
        if (lod < MapCalibration.MIN_LOD || lod > MapCalibration.MAX_LOD) return;

        WorldRegistry data = WorldRegistry.get(server);
        if (!data.mapId().equals(mapId)) return;

        RateWindow win = tileBudgets.computeIfAbsent(player.getUUID(), k -> new RateWindow());
        int now = server.getTickCount();
        if (now - win.startTick >= 20) {
            win.startTick = now;
            win.requests = 0;
        }
        if (++win.requests > 48) {
            return; // Silently drop excess burst; client will retry missing visible tiles smoothly.
        }

        MapCalibration cal = MapCalibration.fromServer(server);
        Path tileRoot = MapCalibration.resolveTileRoot(server);
        Path tileFile = tileRoot.resolve(MapCalibration.dimensionFolder(cal.dimension()))
                .resolve("z" + lod)
                .resolve(tx + "_" + tz + ".png");
        UUID playerId = player.getUUID();
        UUID expectedMapId = data.mapId();

        CompletableFuture.runAsync(() -> {
            byte[] bytes = null;
            boolean exists = false;
            try {
                if (Files.isRegularFile(tileFile)) {
                    long size = Files.size(tileFile);
                    if (size > 0 && size <= MapModels.MAX_COMPRESSED_PACKET_BYTES) {
                        bytes = Files.readAllBytes(tileFile);
                        exists = true;
                    }
                }
            } catch (Exception ignored) {
                exists = false;
            }
            final boolean found = exists;
            final byte[] payload = found ? bytes : new byte[0];
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online != null && !online.hasDisconnected() && MapNetwork.canSend(online)) {
                    MapNetwork.sendToPlayer(online, new MapNetwork.TileResponsePacket(
                            expectedMapId, lod, tx, tz, found, payload));
                }
            });
        }, TILE_IO_POOL);
    }

    private Snapshot capture(WorldRegistry data, long revision) {
        MapCalibration cal = MapCalibration.fromServer(server);
        ResourceLocation targetDim = cal.dimension();
        TownTerritories territories = new TownTerritories(server);

        Map<UUID, Integer> townPop = new HashMap<>();
        for (Town t : data.towns()) {
            townPop.put(t.id(), data.members(t.id()).size());
        }

        Map<Long, UUID> chunkOwners = new LinkedHashMap<>();
        Map<UUID, Integer> townClaimCounts = new HashMap<>();
        Map<UUID, long[]> townFirstChunk = new HashMap<>();

        for (Territory.Location loc : data.territoryLocations()) {
            Territory verified = territories.verified(loc);
            if (verified == null) continue;
            townClaimCounts.merge(verified.town(), 1, Integer::sum);
            if (!targetDim.equals(loc.dimension())) continue;
            long key = MapModels.packChunk(loc.x(), loc.z());
            chunkOwners.put(key, verified.town());
            townFirstChunk.putIfAbsent(verified.town(), new long[]{loc.x(), loc.z()});
        }

        Map<UUID, TownSummary> townMap = new LinkedHashMap<>();
        for (Town t : data.towns()) {
            UUID nationId = data.getTownNation(t.id()).map(Nation::id).orElse(null);
            int pop = townPop.getOrDefault(t.id(), 0);
            int claims = townClaimCounts.getOrDefault(t.id(), 0);
            long[] home = townFirstChunk.get(t.id());
            int homeX = home != null ? (int) (home[0] * 16 + 8) : 0;
            int homeZ = home != null ? (int) (home[1] * 16 + 8) : 0;
            townMap.put(t.id(), new TownSummary(
                    t.id(),
                    t.name(),
                    playerName(t.mayor()),
                    nationId,
                    pop,
                    claims,
                    homeX,
                    homeZ,
                    home != null
            ));
        }

        Map<UUID, NationSummary> nationMap = new LinkedHashMap<>();
        for (Nation n : data.nations()) {
            List<Town> memberTowns = data.getNationTowns(n.id());
            int pop = 0;
            int claims = 0;
            for (Town mt : memberTowns) {
                pop += townPop.getOrDefault(mt.id(), 0);
                claims += townClaimCounts.getOrDefault(mt.id(), 0);
            }
            Town capital = data.getTownById(n.capitalTown()).orElse(null);
            String leader = capital != null ? playerName(capital.mayor()) : "";
            String currency = data.officialTownCurrency(n.capitalTown());
            nationMap.put(n.id(), new NationSummary(
                    n.id(),
                    n.name(),
                    n.capitalTown(),
                    leader,
                    data.nationColor(n.id()),
                    memberTowns.size(),
                    pop,
                    claims,
                    currency
            ));
        }

        return new Snapshot(data.mapId(), revision, targetDim, nationMap, townMap, chunkOwners);
    }

    public static DeltaPayload computeDelta(Snapshot oldSnap, Snapshot newSnap) {
        List<NationSummary> upNations = new ArrayList<>();
        List<UUID> remNations = new ArrayList<>();
        for (var e : newSnap.nations().entrySet()) {
            if (!Objects.equals(oldSnap.nations().get(e.getKey()), e.getValue())) {
                upNations.add(e.getValue());
            }
        }
        for (UUID oldId : oldSnap.nations().keySet()) {
            if (!newSnap.nations().containsKey(oldId)) remNations.add(oldId);
        }

        List<TownSummary> upTowns = new ArrayList<>();
        List<UUID> remTowns = new ArrayList<>();
        for (var e : newSnap.towns().entrySet()) {
            if (!Objects.equals(oldSnap.towns().get(e.getKey()), e.getValue())) {
                upTowns.add(e.getValue());
            }
        }
        for (UUID oldId : oldSnap.towns().keySet()) {
            if (!newSnap.towns().containsKey(oldId)) remTowns.add(oldId);
        }

        List<ClaimEntry> changedClaims = new ArrayList<>();
        for (var e : newSnap.claims().entrySet()) {
            UUID prevTown = oldSnap.claims().get(e.getKey());
            if (!Objects.equals(prevTown, e.getValue())) {
                long key = e.getKey();
                changedClaims.add(new ClaimEntry(MapModels.unpackChunkX(key), MapModels.unpackChunkZ(key), e.getValue()));
            }
        }
        for (Long oldKey : oldSnap.claims().keySet()) {
            if (!newSnap.claims().containsKey(oldKey)) {
                changedClaims.add(new ClaimEntry(MapModels.unpackChunkX(oldKey), MapModels.unpackChunkZ(oldKey), null));
            }
        }

        return new DeltaPayload(upNations, remNations, upTowns, remTowns, changedClaims);
    }

    private String playerName(UUID playerId) {
        return server.getProfileCache().get(playerId).map(GameProfile::getName).orElse(playerId.toString());
    }

    private static int ceilDiv(int a, int b) {
        return a <= 0 ? 0 : (a + b - 1) / b;
    }
}
