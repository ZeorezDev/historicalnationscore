package dev.zeorez.historicalnations.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.HistoricalNations;
import dev.zeorez.historicalnations.map.MapCalibration;
import dev.zeorez.historicalnations.map.MapNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded GPU + disk LRU tile cache strictly isolated per server mapId.
 *
 * - Disk cache prefers D:\HeartsOfSteel\Cache\<mapId> when D:\HeartsOfSteel exists (never touches C:\),
 *   otherwise <gameDirectory>/hn_map_cache/<mapId>.
 * - Releases DynamicTexture + TextureManager bindings upon LRU eviction and server switch.
 */
public final class ClientTileCache {
    private static final ExecutorService DECODE_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "HNC-ClientTile-Decoder");
        t.setDaemon(true);
        return t;
    });

    public record TileKey(int lod, int tx, int tz) {}

    public record TileSlice(ResourceLocation texture, float u0, float v0, float u1, float v1, int actualLod) {}

    private record LoadedTile(ResourceLocation location, DynamicTexture texture) {
        void release() {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getTextureManager() != null) {
                mc.getTextureManager().release(location);
            } else {
                texture.close();
            }
        }
    }

    private record DecodedUpload(UUID mapId, TileKey key, NativeImage image, boolean missing) {}

    private static final long IN_FLIGHT_TIMEOUT_MS = 3_000L;
    private static final long MISSING_RETRY_MS = 6_000L;
    private static final double NET_TOKENS_PER_MS = 0.090D; // 90 tiles/sec steady pace (under server 200/sec cap)
    private static final double MAX_NET_BURST = 56.0D;

    private UUID activeMapId;
    private boolean diskCacheEnabled = true;
    private int maxCachedTiles = 256;
    private long texCounter = 0L;

    private final LinkedHashMap<TileKey, LoadedTile> gpuCache = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<TileKey, Long> missingUntilMs = new HashMap<>();
    private final Map<TileKey, Long> inFlightSinceMs = new HashMap<>();
    private final Queue<DecodedUpload> uploadQueue = new ConcurrentLinkedQueue<>();
    private int diskLoadsThisFrame = 0;
    private int netRequestsThisFrame = 0;
    private double netTokens = MAX_NET_BURST;
    private long lastTokenRefillMs = System.currentTimeMillis();

    public void configure(UUID mapId, boolean enableDiskCache, int maxTiles) {
        if (activeMapId == null || !activeMapId.equals(mapId)) {
            clearGpu();
            activeMapId = mapId;
        }
        this.diskCacheEnabled = enableDiskCache;
        this.maxCachedTiles = Math.max(48, Math.min(4096, maxTiles));
        trimLru();
    }

    /** Clears transient negative-cache entries when the map screen opens so newly rendered tiles appear immediately. */
    public void resetTransientState() {
        missingUntilMs.clear();
    }

    public UUID activeMapId() {
        return activeMapId;
    }

    public int cachedCount() {
        return gpuCache.size();
    }

    public void beginFrame() {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0L, now - lastTokenRefillMs);
        lastTokenRefillMs = now;
        netTokens = Math.min(MAX_NET_BURST, netTokens + elapsed * NET_TOKENS_PER_MS);
        diskLoadsThisFrame = 0;
        netRequestsThisFrame = 0;

        if (!inFlightSinceMs.isEmpty()) {
            inFlightSinceMs.entrySet().removeIf(e -> now - e.getValue() >= IN_FLIGHT_TIMEOUT_MS);
        }
        if (!missingUntilMs.isEmpty()) {
            missingUntilMs.entrySet().removeIf(e -> now >= e.getValue());
        }

        int uploads = 0;
        DecodedUpload item;
        while (uploads < 16 && (item = uploadQueue.poll()) != null) {
            if (activeMapId == null || !activeMapId.equals(item.mapId())) {
                if (item.image() != null) item.image().close();
                continue;
            }
            inFlightSinceMs.remove(item.key());
            if (item.missing() || item.image() == null) {
                missingUntilMs.put(item.key(), now + MISSING_RETRY_MS);
                continue;
            }
            missingUntilMs.remove(item.key());
            uploadToGpu(item.key(), item.image());
            uploads++;
        }
    }

    /** Returns an already-uploaded GPU texture for (lod, tx, tz) without triggering I/O or network requests. */
    public ResourceLocation peekLoaded(int lod, int tx, int tz) {
        LoadedTile loaded = gpuCache.get(new TileKey(lod, tx, tz));
        return loaded != null ? loaded.location() : null;
    }

    /** Proactively warms the coarse planetary z7 overview tiles (bounded to <= 16 tiles) for instant World View. */
    public void preloadWorldOverview(MapCalibration cal) {
        if (activeMapId == null || cal == null) return;
        int rootLod = MapCalibration.MAX_LOD;
        int minTx = Math.max(-4, MapCalibration.blockToTile(cal.minBlockX(), rootLod));
        int maxTx = Math.min(3, MapCalibration.blockToTile(cal.maxBlockX(), rootLod));
        int minTz = Math.max(-2, MapCalibration.blockToTile(cal.minBlockZ(), rootLod));
        int maxTz = Math.min(1, MapCalibration.blockToTile(cal.maxBlockZ(), rootLod));
        for (int tz = minTz; tz <= maxTz; tz++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                TileKey key = new TileKey(rootLod, tx, tz);
                if (!gpuCache.containsKey(key)) {
                    requestTileIfNeeded(key);
                }
            }
        }
    }

    /**
     * Queries the exact tile or falls back to an already-loaded coarser parent LOD tile slice
     * while asynchronously requesting the exact tile from local disk cache or the server.
     */
    public TileSlice resolveOrRequest(int lod, int tx, int tz) {
        if (activeMapId == null) return null;
        TileKey exactKey = new TileKey(lod, tx, tz);
        LoadedTile exact = gpuCache.get(exactKey);
        if (exact != null) {
            return new TileSlice(exact.location(), 0f, 0f, 1f, 1f, lod);
        }

        requestTileIfNeeded(exactKey);

        // Parent LOD fallback for seamless zoom/pan without flickering holes
        int curTx = tx;
        int curTz = tz;
        for (int parentLod = lod + 1; parentLod <= MapCalibration.MAX_LOD; parentLod++) {
            curTx = Math.floorDiv(curTx, 2);
            curTz = Math.floorDiv(curTz, 2);
            TileKey parentKey = new TileKey(parentLod, curTx, curTz);
            LoadedTile parent = gpuCache.get(parentKey);
            if (parent != null) {
                int shift = parentLod - lod;
                int span = 1 << shift;
                int subX = Math.floorMod(tx, span);
                int subZ = Math.floorMod(tz, span);
                float inv = 1.0f / span;
                return new TileSlice(
                        parent.location(),
                        subX * inv,
                        subZ * inv,
                        (subX + 1) * inv,
                        (subZ + 1) * inv,
                        parentLod
                );
            }
        }
        return null;
    }

    public void onNetworkTileResponse(MapNetwork.TileResponsePacket pkt) {
        if (activeMapId == null || !activeMapId.equals(pkt.mapId())) return;
        TileKey key = new TileKey(pkt.lod(), pkt.tx(), pkt.tz());
        if (!pkt.exists() || pkt.pngBytes().length == 0) {
            inFlightSinceMs.remove(key);
            missingUntilMs.put(key, System.currentTimeMillis() + MISSING_RETRY_MS);
            return;
        }
        UUID mapId = activeMapId;
        boolean saveToDisk = diskCacheEnabled;
        Path diskFile = saveToDisk ? diskTilePath(mapId, key) : null;
        byte[] bytes = pkt.pngBytes();

        CompletableFuture.runAsync(() -> {
            try {
                if (saveToDisk && diskFile != null) {
                    Files.createDirectories(diskFile.getParent());
                    Path tmp = diskFile.resolveSibling(diskFile.getFileName().toString() + ".tmp");
                    Files.write(tmp, bytes);
                    Files.move(tmp, diskFile, StandardCopyOption.REPLACE_EXISTING);
                }
                NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
                uploadQueue.add(new DecodedUpload(mapId, key, img, false));
            } catch (Exception ex) {
                LogUtils.getLogger().debug("Failed decoding received map tile {}", key, ex);
                uploadQueue.add(new DecodedUpload(mapId, key, null, true));
            }
        }, DECODE_POOL);
    }

    private void requestTileIfNeeded(TileKey key) {
        long now = System.currentTimeMillis();
        Long retryAt = missingUntilMs.get(key);
        if (retryAt != null && now < retryAt) return;
        Long startedAt = inFlightSinceMs.get(key);
        if (startedAt != null && now - startedAt < IN_FLIGHT_TIMEOUT_MS) return;

        UUID mapId = activeMapId;
        Path diskFile = diskCacheEnabled ? diskTilePath(mapId, key) : null;

        if (diskFile != null && Files.isRegularFile(diskFile)) {
            if (diskLoadsThisFrame >= 24) return;
            diskLoadsThisFrame++;
            inFlightSinceMs.put(key, now);
            CompletableFuture.runAsync(() -> {
                try {
                    byte[] bytes = Files.readAllBytes(diskFile);
                    NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
                    uploadQueue.add(new DecodedUpload(mapId, key, img, false));
                } catch (Exception ex) {
                    // Corrupt cached file: delete and fall back to network request on next frame
                    try { Files.deleteIfExists(diskFile); } catch (Exception ignored) {}
                    Minecraft.getInstance().execute(() -> {
                        if (mapId.equals(activeMapId)) {
                            inFlightSinceMs.remove(key);
                        }
                    });
                }
            }, DECODE_POOL);
            return;
        }

        if (netRequestsThisFrame >= 10 || netTokens < 1.0D) return;
        netRequestsThisFrame++;
        netTokens -= 1.0D;
        inFlightSinceMs.put(key, now);
        MapNetwork.CHANNEL.sendToServer(new MapNetwork.TileRequestPacket(mapId, key.lod(), key.tx(), key.tz()));
    }

    private void uploadToGpu(TileKey key, NativeImage image) {
        LoadedTile prev = gpuCache.remove(key);
        if (prev != null) prev.release();

        DynamicTexture dyn = new DynamicTexture(image);
        dyn.setFilter(false, false);
        String name = String.format(Locale.ROOT, "map_tile_%d_%d_%d_%d",
                key.lod(), key.tx() & 0xFFFF, key.tz() & 0xFFFF, ++texCounter);
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(HistoricalNations.ID, name);
        Minecraft.getInstance().getTextureManager().register(loc, dyn);
        gpuCache.put(key, new LoadedTile(loc, dyn));
        trimLru();
    }

    private void trimLru() {
        int pinnedRootCount = 0;
        Iterator<Map.Entry<TileKey, LoadedTile>> it = gpuCache.entrySet().iterator();
        while (gpuCache.size() > maxCachedTiles && it.hasNext()) {
            Map.Entry<TileKey, LoadedTile> eldest = it.next();
            if (eldest.getKey().lod() == MapCalibration.MAX_LOD && pinnedRootCount < 16) {
                pinnedRootCount++;
                continue;
            }
            eldest.getValue().release();
            it.remove();
        }
    }

    public void clearGpu() {
        for (LoadedTile tile : gpuCache.values()) {
            tile.release();
        }
        gpuCache.clear();
        missingUntilMs.clear();
        inFlightSinceMs.clear();
        netTokens = MAX_NET_BURST;
        lastTokenRefillMs = System.currentTimeMillis();
        DecodedUpload pending;
        while ((pending = uploadQueue.poll()) != null) {
            if (pending.image() != null) pending.image().close();
        }
    }

    /** Resolves server-isolated disk cache path (<CacheRoot>/<mapId>/z<lod>/<tx>_<tz>.png). */
    public static Path resolveCacheRoot(UUID mapId) {
        Path hosRoot = Path.of("D:/HeartsOfSteel");
        Path base;
        if (Files.isDirectory(hosRoot)) {
            base = hosRoot.resolve("Cache");
        } else {
            Minecraft mc = Minecraft.getInstance();
            base = (mc != null && mc.gameDirectory != null ? mc.gameDirectory.toPath() : Path.of("."))
                    .resolve("hn_map_cache");
        }
        return base.resolve(mapId.toString()).normalize();
    }

    private static Path diskTilePath(UUID mapId, TileKey key) {
        return resolveCacheRoot(mapId).resolve("z" + key.lod()).resolve(key.tx() + "_" + key.tz() + ".png");
    }
}
