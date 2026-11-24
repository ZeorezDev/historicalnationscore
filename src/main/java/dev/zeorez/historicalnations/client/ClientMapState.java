package dev.zeorez.historicalnations.client;

import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.map.MapCalibration;
import dev.zeorez.historicalnations.map.MapModels;
import dev.zeorez.historicalnations.map.MapModels.ClaimEntry;
import dev.zeorez.historicalnations.map.MapModels.DeltaPayload;
import dev.zeorez.historicalnations.map.MapModels.FocusKind;
import dev.zeorez.historicalnations.map.MapModels.NationSummary;
import dev.zeorez.historicalnations.map.MapModels.ServerMapSettings;
import dev.zeorez.historicalnations.map.MapModels.TownSummary;
import dev.zeorez.historicalnations.map.MapNetwork;
import dev.zeorez.historicalnations.map.NationPalette;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-side authoritative cache, spatial index (64x64 chunk region buckets),
 * merged territory polygon builder, and sovereign/municipal border mesher.
 */
public final class ClientMapState implements MapNetwork.ClientHandler {
    public static final ClientMapState INSTANCE = new ClientMapState();
    public static final int REGION_SHIFT = 6; // 64x64 chunks = 1024x1024 blocks per spatial bucket
    public static final int REGION_CHUNKS = 1 << REGION_SHIFT;

    public record FillRect(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, UUID townId, UUID nationId, int colorRgb) {}

    /** Axis-aligned border segment in world block coordinates. */
    public record BorderSegment(int x1, int z1, int x2, int z2, UUID nationId, int colorRgb) {}

    public static final class RegionMesh {
        public final int rx, rz;
        boolean dirty = true;
        final LongOpenHashSet localChunks = new LongOpenHashSet();
        public final List<FillRect> fills = new ArrayList<>();
        public final List<BorderSegment> nationBorders = new ArrayList<>();
        public final List<BorderSegment> townBorders = new ArrayList<>();

        RegionMesh(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }
    }

    public record NationBounds(
            UUID nationId,
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ,
            double labelBlockX,
            double labelBlockZ,
            int largestClusterChunks
    ) {}

    public final ClientTileCache tiles = new ClientTileCache();

    private UUID mapId;
    private long revision = 0L;
    private MapCalibration calibration = new MapCalibration(
            500, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            true, false, -36864, 36863, -18432, 18431, 0, 0, 204.8D);
    private ServerMapSettings settings = new ServerMapSettings(
            true, TerritoryConfig.MapMode.POLITICAL, true, true, true, true, true, true, true, 150, 256);

    private final Map<UUID, NationSummary> nations = new LinkedHashMap<>();
    private final Map<UUID, TownSummary> towns = new LinkedHashMap<>();
    private final Long2ObjectOpenHashMap<UUID> chunkOwners = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<RegionMesh> regions = new Long2ObjectOpenHashMap<>();
    private final Map<UUID, NationBounds> nationBounds = new HashMap<>();
    private boolean boundsDirty = true;

    // Staging buffers while receiving a multi-part full snapshot
    private UUID stagingMapId;
    private long stagingRevision = -1L;
    private MapCalibration stagingCalibration;
    private ServerMapSettings stagingSettings;
    private final Map<UUID, NationSummary> stagingNations = new LinkedHashMap<>();
    private final Map<UUID, TownSummary> stagingTowns = new LinkedHashMap<>();
    private final List<ClaimEntry> stagingClaims = new ArrayList<>();

    private ClientMapState() {}

    public UUID mapId() { return mapId; }
    public long revision() { return revision; }
    public MapCalibration calibration() { return calibration; }
    public ServerMapSettings settings() { return settings; }
    public Collection<NationSummary> nations() { return nations.values(); }
    public Collection<TownSummary> towns() { return towns.values(); }
    public NationSummary nation(UUID id) { return id == null ? null : nations.get(id); }
    public TownSummary town(UUID id) { return id == null ? null : towns.get(id); }
    public int totalClaimedChunks() { return chunkOwners.size(); }

    public TownSummary townAtBlock(int blockX, int blockZ) {
        return townAtChunk(blockX >> 4, blockZ >> 4);
    }

    public TownSummary townAtChunk(int chunkX, int chunkZ) {
        UUID townId = chunkOwners.get(MapModels.packChunk(chunkX, chunkZ));
        return townId == null ? null : towns.get(townId);
    }

    public int territoryColor(TownSummary town) {
        if (town == null) return NationPalette.INDEPENDENT_COLOR;
        if (town.nationId() != null) {
            NationSummary n = nations.get(town.nationId());
            if (n != null) return n.colorRgb();
        }
        return NationPalette.INDEPENDENT_COLOR;
    }

    public Map<UUID, NationBounds> nationBounds() {
        if (boundsDirty) {
            recomputeNationBounds();
            boundsDirty = false;
        }
        return nationBounds;
    }

    public void requestSyncFromServer() {
        MapNetwork.CHANNEL.sendToServer(new MapNetwork.MapSubscribePacket(mapId, revision, true));
    }

    public void onDisconnect() {
        mapId = null;
        revision = 0L;
        nations.clear();
        towns.clear();
        chunkOwners.clear();
        regions.clear();
        nationBounds.clear();
        stagingRevision = -1L;
        stagingNations.clear();
        stagingTowns.clear();
        stagingClaims.clear();
        tiles.clearGpu();
    }

    @Override
    public void onOpenMap(MapNetwork.OpenMapPacket pkt) {
        PoliticalMapScreen.openWithFocus(pkt.focus(), pkt.targetId(), pkt.blockX(), pkt.blockZ());
    }

    @Override
    public void onUpToDate(MapNetwork.MapUpToDatePacket pkt) {
        if (!Objects.equals(mapId, pkt.mapId())) {
            onDisconnect();
            mapId = pkt.mapId();
        }
        this.revision = pkt.revision();
        this.calibration = pkt.calibration();
        this.settings = pkt.settings();
        tiles.configure(pkt.mapId(), pkt.settings().enableTileCache(), pkt.settings().maxCachedTiles());
    }

    @Override
    public void onSnapshotHeader(MapNetwork.MapSnapshotHeaderPacket pkt) {
        stagingMapId = pkt.mapId();
        stagingRevision = pkt.revision();
        stagingCalibration = pkt.calibration();
        stagingSettings = pkt.settings();
        stagingNations.clear();
        stagingTowns.clear();
        stagingClaims.clear();
    }

    @Override
    public void onSnapshotPart(MapNetwork.MapSnapshotPartPacket pkt) {
        if (stagingMapId == null || !stagingMapId.equals(pkt.mapId()) || stagingRevision != pkt.revision()) {
            return;
        }
        if (pkt.kind() == MapNetwork.KIND_COMPLETE) {
            commitSnapshot();
            return;
        }
        FriendlyByteBuf buf = MapModels.decompress(pkt.compressed());
        try {
            int count = buf.readVarInt();
            switch (pkt.kind()) {
                case MapNetwork.KIND_NATIONS -> {
                    for (int i = 0; i < count; i++) {
                        NationSummary n = NationSummary.read(buf);
                        stagingNations.put(n.id(), n);
                    }
                }
                case MapNetwork.KIND_TOWNS -> {
                    for (int i = 0; i < count; i++) {
                        TownSummary t = TownSummary.read(buf);
                        stagingTowns.put(t.id(), t);
                    }
                }
                case MapNetwork.KIND_CLAIMS -> {
                    for (int i = 0; i < count; i++) {
                        stagingClaims.add(ClaimEntry.read(buf));
                    }
                }
                default -> {}
            }
        } finally {
            buf.release();
        }
    }

    private void commitSnapshot() {
        boolean sameMap = Objects.equals(this.mapId, stagingMapId);
        this.mapId = stagingMapId;
        this.revision = stagingRevision;
        this.calibration = stagingCalibration;
        this.settings = stagingSettings;

        if (!sameMap) {
            tiles.clearGpu();
        }
        tiles.configure(this.mapId, this.settings.enableTileCache(), this.settings.maxCachedTiles());

        nations.clear();
        nations.putAll(stagingNations);
        towns.clear();
        towns.putAll(stagingTowns);
        chunkOwners.clear();
        regions.clear();

        for (ClaimEntry c : stagingClaims) {
            if (c.townId() != null) {
                putChunkInternal(c.chunkX(), c.chunkZ(), c.townId());
            }
        }
        boundsDirty = true;

        stagingRevision = -1L;
        stagingNations.clear();
        stagingTowns.clear();
        stagingClaims.clear();

        if (Minecraft.getInstance().screen instanceof PoliticalMapScreen screen) {
            screen.onMapDataUpdated(true);
        }
    }

    @Override
    public void onDelta(MapNetwork.MapDeltaPacket pkt) {
        if (mapId == null || !mapId.equals(pkt.mapId()) || revision != pkt.fromRevision()) {
            // Missed a revision or switched servers: request clean snapshot
            requestSyncFromServer();
            return;
        }
        DeltaPayload delta = DeltaPayload.decodeCompressed(pkt.compressedDelta());
        this.revision = pkt.toRevision();

        boolean nationVisualChanged = !delta.upsertedNations().isEmpty() || !delta.removedNations().isEmpty();
        for (UUID remN : delta.removedNations()) {
            nations.remove(remN);
        }
        for (NationSummary upN : delta.upsertedNations()) {
            nations.put(upN.id(), upN);
        }

        List<UUID> affiliatedChangedTowns = new ArrayList<>();
        for (UUID remT : delta.removedTowns()) {
            towns.remove(remT);
            affiliatedChangedTowns.add(remT);
        }
        for (TownSummary upT : delta.upsertedTowns()) {
            TownSummary prev = towns.put(upT.id(), upT);
            if (prev == null || !Objects.equals(prev.nationId(), upT.nationId())) {
                affiliatedChangedTowns.add(upT.id());
            }
        }

        for (ClaimEntry c : delta.changedClaims()) {
            if (c.townId() == null) {
                removeChunkInternal(c.chunkX(), c.chunkZ());
            } else {
                putChunkInternal(c.chunkX(), c.chunkZ(), c.townId());
            }
        }

        if (nationVisualChanged || !affiliatedChangedTowns.isEmpty()) {
            // Mark regions dirty where affected towns/nations hold claims
            for (RegionMesh rm : regions.values()) {
                rm.dirty = true;
            }
        }
        boundsDirty = true;

        if (Minecraft.getInstance().screen instanceof PoliticalMapScreen screen) {
            screen.onMapDataUpdated(false);
        }
    }

    @Override
    public void onTileResponse(MapNetwork.TileResponsePacket pkt) {
        tiles.onNetworkTileResponse(pkt);
    }

    /** Queries and lazily meshes ONLY the 64x64-chunk region buckets intersecting the visible viewport. */
    public List<RegionMesh> visibleRegions(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
        int minRx = (minBlockX >> 4) >> REGION_SHIFT;
        int maxRx = (maxBlockX >> 4) >> REGION_SHIFT;
        int minRz = (minBlockZ >> 4) >> REGION_SHIFT;
        int maxRz = (maxBlockZ >> 4) >> REGION_SHIFT;

        List<RegionMesh> out = new ArrayList<>();
        // Guard against extreme zoom-out iterating empty ocean buckets: iterate map entries if smaller!
        long spanCount = (long) (maxRx - minRx + 1) * (long) (maxRz - minRz + 1);
        if (spanCount > regions.size() * 2L) {
            for (Long2ObjectMap.Entry<RegionMesh> entry : regions.long2ObjectEntrySet()) {
                RegionMesh rm = entry.getValue();
                if (rm.rx >= minRx && rm.rx <= maxRx && rm.rz >= minRz && rm.rz <= maxRz && !rm.localChunks.isEmpty()) {
                    if (rm.dirty) rebuildRegionMesh(rm);
                    out.add(rm);
                }
            }
        } else {
            for (int rz = minRz; rz <= maxRz; rz++) {
                for (int rx = minRx; rx <= maxRx; rx++) {
                    RegionMesh rm = regions.get(packRegion(rx, rz));
                    if (rm != null && !rm.localChunks.isEmpty()) {
                        if (rm.dirty) rebuildRegionMesh(rm);
                        out.add(rm);
                    }
                }
            }
        }
        return out;
    }

    public record SearchHit(FocusKind kind, UUID id, String primary, String secondary, int blockX, int blockZ) {}

    public List<SearchHit> search(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.strip();
        if (q.isEmpty()) return List.of();

        List<SearchHit> results = new ArrayList<>();

        // 1. Coordinate search: "7773 -8037" or "7773, -8037"
        String coordClean = q.replace(',', ' ').replace("X:", "").replace("x:", "").replace("Z:", "").replace("z:", "").strip();
        String[] parts = coordClean.split("\\s+");
        if (parts.length == 2) {
            try {
                int bx = Integer.parseInt(parts[0]);
                int bz = Integer.parseInt(parts[1]);
                if (Math.abs(bx) <= 30_000_000 && Math.abs(bz) <= 30_000_000) {
                    results.add(new SearchHit(FocusKind.COORDS, null,
                            "X: " + bx + ", Z: " + bz, calibration.formatGeo(bx, bz), bx, bz));
                }
            } catch (NumberFormatException ignored) {}
        }

        String lower = q.toLowerCase(Locale.ROOT);

        // 2. Nations matching query
        List<NationSummary> matchedNations = new ArrayList<>();
        for (NationSummary n : nations.values()) {
            if (n.name().toLowerCase(Locale.ROOT).contains(lower)) {
                matchedNations.add(n);
            }
        }
        matchedNations.sort(Comparator
                .comparing((NationSummary n) -> !n.name().toLowerCase(Locale.ROOT).startsWith(lower))
                .thenComparing(NationSummary::name, String.CASE_INSENSITIVE_ORDER));
        Map<UUID, NationBounds> nbMap = nationBounds();
        for (NationSummary n : matchedNations) {
            NationBounds nb = nbMap.get(n.id());
            TownSummary cap = towns.get(n.capitalTownId());
            int bx = nb != null ? (int) nb.labelBlockX() : (cap != null ? cap.homeBlockX() : 0);
            int bz = nb != null ? (int) nb.labelBlockZ() : (cap != null ? cap.homeBlockZ() : 0);
            results.add(new SearchHit(FocusKind.NATION, n.id(), n.name(),
                    n.townCount() + " Towns • " + n.claimCount() + " Chunks", bx, bz));
            if (results.size() >= 10) return results;
        }

        // 3. Towns matching query
        List<TownSummary> matchedTowns = new ArrayList<>();
        for (TownSummary t : towns.values()) {
            if (t.name().toLowerCase(Locale.ROOT).contains(lower)) {
                matchedTowns.add(t);
            }
        }
        matchedTowns.sort(Comparator
                .comparing((TownSummary t) -> !t.name().toLowerCase(Locale.ROOT).startsWith(lower))
                .thenComparing(TownSummary::name, String.CASE_INSENSITIVE_ORDER));
        for (TownSummary t : matchedTowns) {
            NationSummary parent = t.nationId() != null ? nations.get(t.nationId()) : null;
            String sub = parent != null ? parent.name() : "Independent";
            results.add(new SearchHit(FocusKind.TOWN, t.id(), t.name(), sub, t.homeBlockX(), t.homeBlockZ()));
            if (results.size() >= 10) return results;
        }

        return results;
    }

    private void putChunkInternal(int cx, int cz, UUID townId) {
        long key = MapModels.packChunk(cx, cz);
        chunkOwners.put(key, townId);
        int rx = cx >> REGION_SHIFT;
        int rz = cz >> REGION_SHIFT;
        RegionMesh rm = regions.computeIfAbsent(packRegion(rx, rz), k -> new RegionMesh(rx, rz));
        rm.localChunks.add(key);
        markRegionAndNeighborsDirty(cx, cz, rx, rz);
    }

    private void removeChunkInternal(int cx, int cz) {
        long key = MapModels.packChunk(cx, cz);
        if (chunkOwners.remove(key) == null) return;
        int rx = cx >> REGION_SHIFT;
        int rz = cz >> REGION_SHIFT;
        RegionMesh rm = regions.get(packRegion(rx, rz));
        if (rm != null) {
            rm.localChunks.remove(key);
        }
        markRegionAndNeighborsDirty(cx, cz, rx, rz);
    }

    private void markRegionAndNeighborsDirty(int cx, int cz, int rx, int rz) {
        markDirty(rx, rz);
        int lx = cx & (REGION_CHUNKS - 1);
        int lz = cz & (REGION_CHUNKS - 1);
        if (lx == 0) markDirty(rx - 1, rz);
        if (lx == REGION_CHUNKS - 1) markDirty(rx + 1, rz);
        if (lz == 0) markDirty(rx, rz - 1);
        if (lz == REGION_CHUNKS - 1) markDirty(rx, rz + 1);
    }

    private void markDirty(int rx, int rz) {
        RegionMesh rm = regions.get(packRegion(rx, rz));
        if (rm != null) rm.dirty = true;
    }

    private void rebuildRegionMesh(RegionMesh rm) {
        rm.dirty = false;
        rm.fills.clear();
        rm.nationBorders.clear();
        rm.townBorders.clear();
        if (rm.localChunks.isEmpty()) return;

        int baseCx = rm.rx << REGION_SHIFT;
        int baseCz = rm.rz << REGION_SHIFT;

        // 1. Greedy 2D rectangle merging for seamless unified territory fills
        UUID[] grid = new UUID[REGION_CHUNKS * REGION_CHUNKS];
        boolean[] visited = new boolean[REGION_CHUNKS * REGION_CHUNKS];
        var iterator = rm.localChunks.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int lx = MapModels.unpackChunkX(key) - baseCx;
            int lz = MapModels.unpackChunkZ(key) - baseCz;
            if (lx >= 0 && lx < REGION_CHUNKS && lz >= 0 && lz < REGION_CHUNKS) {
                grid[lz * REGION_CHUNKS + lx] = chunkOwners.get(key);
            }
        }

        for (int lz = 0; lz < REGION_CHUNKS; lz++) {
            for (int lx = 0; lx < REGION_CHUNKS; lx++) {
                int idx = lz * REGION_CHUNKS + lx;
                UUID townId = grid[idx];
                if (townId == null || visited[idx]) continue;

                int w = 1;
                while (lx + w < REGION_CHUNKS && !visited[lz * REGION_CHUNKS + lx + w]
                        && townId.equals(grid[lz * REGION_CHUNKS + lx + w])) {
                    w++;
                }
                int h = 1;
                expandH:
                while (lz + h < REGION_CHUNKS) {
                    for (int dx = 0; dx < w; dx++) {
                        int nIdx = (lz + h) * REGION_CHUNKS + lx + dx;
                        if (visited[nIdx] || !townId.equals(grid[nIdx])) break expandH;
                    }
                    h++;
                }
                for (int dz = 0; dz < h; dz++) {
                    for (int dx = 0; dx < w; dx++) {
                        visited[(lz + dz) * REGION_CHUNKS + lx + dx] = true;
                    }
                }

                TownSummary town = towns.get(townId);
                UUID nationId = town != null ? town.nationId() : null;
                int color = territoryColor(town);
                rm.fills.add(new FillRect(
                        (baseCx + lx) * 16,
                        (baseCz + lz) * 16,
                        (baseCx + lx + w) * 16,
                        (baseCz + lz + h) * 16,
                        townId,
                        nationId,
                        color
                ));
            }
        }

        // 2. Merged horizontal & vertical border segments (Nation outer/inter-sovereign vs Town internal)
        buildHorizontalBorders(rm, baseCx, baseCz, grid);
        buildVerticalBorders(rm, baseCx, baseCz, grid);
    }

    private void buildHorizontalBorders(RegionMesh rm, int baseCx, int baseCz, UUID[] grid) {
        // Check North edge (dz = -1) and South edge (dz = +1) for each chunk row
        for (int side = 0; side < 2; side++) {
            int dz = side == 0 ? -1 : 1;
            int edgeOffsetBlocks = side == 0 ? 0 : 16;

            for (int lz = 0; lz < REGION_CHUNKS; lz++) {
                int cz = baseCz + lz;
                int runStartLx = -1;
                int runKind = 0; // 1 = nation border, 2 = town-only border
                int runColor = 0;
                UUID runNation = null;

                for (int lx = 0; lx <= REGION_CHUNKS; lx++) {
                    int kind = 0;
                    int color = 0;
                    UUID natId = null;

                    if (lx < REGION_CHUNKS) {
                        UUID townA = grid[lz * REGION_CHUNKS + lx];
                        if (townA != null) {
                            UUID townB = chunkOwners.get(MapModels.packChunk(baseCx + lx, cz + dz));
                            if (!townA.equals(townB)) {
                                TownSummary ta = towns.get(townA);
                                TownSummary tb = townB != null ? towns.get(townB) : null;
                                natId = ta != null ? ta.nationId() : null;
                                UUID natB = tb != null ? tb.nationId() : null;
                                color = territoryColor(ta);
                                if (natId != null && natId.equals(natB)) {
                                    // Two different towns of the SAME nation: ONLY a thin town border, NO nation border!
                                    kind = 2;
                                } else {
                                    // Unclaimed neighbor, independent town, or neighboring rival nation: sovereign border!
                                    kind = 1;
                                }
                            }
                        }
                    }

                    if (kind == runKind && color == runColor && Objects.equals(natId, runNation)) {
                        continue;
                    }
                    if (runKind != 0) {
                        int x1 = (baseCx + runStartLx) * 16;
                        int x2 = (baseCx + lx) * 16;
                        int z = cz * 16 + edgeOffsetBlocks;
                        BorderSegment seg = new BorderSegment(x1, z, x2, z, runNation, runColor);
                        if (runKind == 1) rm.nationBorders.add(seg);
                        else rm.townBorders.add(seg);
                    }
                    runStartLx = lx;
                    runKind = kind;
                    runColor = color;
                    runNation = natId;
                }
            }
        }
    }

    private void buildVerticalBorders(RegionMesh rm, int baseCx, int baseCz, UUID[] grid) {
        // Check West edge (dx = -1) and East edge (dx = +1) for each chunk column
        for (int side = 0; side < 2; side++) {
            int dx = side == 0 ? -1 : 1;
            int edgeOffsetBlocks = side == 0 ? 0 : 16;

            for (int lx = 0; lx < REGION_CHUNKS; lx++) {
                int cx = baseCx + lx;
                int runStartLz = -1;
                int runKind = 0;
                int runColor = 0;
                UUID runNation = null;

                for (int lz = 0; lz <= REGION_CHUNKS; lz++) {
                    int kind = 0;
                    int color = 0;
                    UUID natId = null;

                    if (lz < REGION_CHUNKS) {
                        UUID townA = grid[lz * REGION_CHUNKS + lx];
                        if (townA != null) {
                            UUID townB = chunkOwners.get(MapModels.packChunk(cx + dx, baseCz + lz));
                            if (!townA.equals(townB)) {
                                TownSummary ta = towns.get(townA);
                                TownSummary tb = townB != null ? towns.get(townB) : null;
                                natId = ta != null ? ta.nationId() : null;
                                UUID natB = tb != null ? tb.nationId() : null;
                                color = territoryColor(ta);
                                if (natId != null && natId.equals(natB)) {
                                    kind = 2;
                                } else {
                                    kind = 1;
                                }
                            }
                        }
                    }

                    if (kind == runKind && color == runColor && Objects.equals(natId, runNation)) {
                        continue;
                    }
                    if (runKind != 0) {
                        int x = cx * 16 + edgeOffsetBlocks;
                        int z1 = (baseCz + runStartLz) * 16;
                        int z2 = (baseCz + lz) * 16;
                        BorderSegment seg = new BorderSegment(x, z1, x, z2, runNation, runColor);
                        if (runKind == 1) rm.nationBorders.add(seg);
                        else rm.townBorders.add(seg);
                    }
                    runStartLz = lz;
                    runKind = kind;
                    runColor = color;
                    runNation = natId;
                }
            }
        }
    }

    /**
     * Computes nation bounding boxes AND places each nation's strategic map label at the centroid
     * of its largest contiguous claimed territory cluster (so overseas colonies don't pull labels into oceans).
     */
    private void recomputeNationBounds() {
        nationBounds.clear();
        Map<UUID, LongOpenHashSet> nationChunks = new HashMap<>();

        for (Long2ObjectMap.Entry<UUID> entry : chunkOwners.long2ObjectEntrySet()) {
            TownSummary t = towns.get(entry.getValue());
            if (t == null || t.nationId() == null) continue;
            nationChunks.computeIfAbsent(t.nationId(), k -> new LongOpenHashSet()).add(entry.getLongKey());
        }

        for (var e : nationChunks.entrySet()) {
            UUID natId = e.getKey();
            LongOpenHashSet all = e.getValue();
            if (all.isEmpty()) continue;

            int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
            int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
            var it = all.iterator();
            while (it.hasNext()) {
                long k = it.nextLong();
                int cx = MapModels.unpackChunkX(k);
                int cz = MapModels.unpackChunkZ(k);
                if (cx < minCx) minCx = cx;
                if (cx > maxCx) maxCx = cx;
                if (cz < minCz) minCz = cz;
                if (cz > maxCz) maxCz = cz;
            }

            LongOpenHashSet seen = new LongOpenHashSet(all.size());
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            int bestSize = 0;
            double bestCenterX = (minCx + maxCx + 1) * 8.0;
            double bestCenterZ = (minCz + maxCz + 1) * 8.0;

            var compIt = all.iterator();
            while (compIt.hasNext()) {
                long seed = compIt.nextLong();
                if (!seen.add(seed)) continue;
                queue.clear();
                queue.enqueue(seed);
                int count = 0;
                long sumCx = 0, sumCz = 0;

                while (!queue.isEmpty()) {
                    long cur = queue.dequeueLong();
                    int cx = MapModels.unpackChunkX(cur);
                    int cz = MapModels.unpackChunkZ(cur);
                    count++;
                    sumCx += cx;
                    sumCz += cz;

                    long[] neighbors = {
                            MapModels.packChunk(cx + 1, cz),
                            MapModels.packChunk(cx - 1, cz),
                            MapModels.packChunk(cx, cz + 1),
                            MapModels.packChunk(cx, cz - 1)
                    };
                    for (long nb : neighbors) {
                        if (all.contains(nb) && seen.add(nb)) {
                            queue.enqueue(nb);
                        }
                    }
                }

                if (count > bestSize) {
                    bestSize = count;
                    bestCenterX = (sumCx / (double) count) * 16.0 + 8.0;
                    bestCenterZ = (sumCz / (double) count) * 16.0 + 8.0;
                }
            }

            nationBounds.put(natId, new NationBounds(
                    natId,
                    minCx * 16,
                    minCz * 16,
                    (maxCx + 1) * 16,
                    (maxCz + 1) * 16,
                    bestCenterX,
                    bestCenterZ,
                    bestSize
            ));
        }
    }

    private static long packRegion(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }
}
