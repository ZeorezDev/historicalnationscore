package dev.zeorez.historicalnations.map;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Off-thread Anvil (.mca) terrain tile pyramid generator for Minecraft 1.18-1.20.1 worlds.
 *
 * CRITICAL SERVER & DISK GUARANTEES:
 * 1. Reads .mca region files directly from disk; NEVER loads chunks in ServerLevel or runs on the server tick thread.
 * 2. Disables ImageIO temporary disk cache so zero temporary files touch C:\.
 * 3. Writes temporary files exclusively inside the target MapTiles directory before atomic rename.
 * 4. Rejects chunks with DataVersion > 3465 (Minecraft > 1.20.1, e.g. 1.21.4+) with a clear warning.
 */
public final class AnvilTileGenerator {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Pattern REGION_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
    private static final Pattern TILE_NAME = Pattern.compile("^(-?\\d+)_(-?\\d+)\\.png$");
    public static final int MC_1_20_1_DATA_VERSION = 3465;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicInteger TOTAL_REGIONS = new AtomicInteger(0);
    private static final AtomicInteger PROCESSED_REGIONS = new AtomicInteger(0);
    private static volatile String LAST_STATUS = "idle";

    static {
        // Never write ImageIO cache files to java.io.tmpdir (protects C: drive).
        ImageIO.setUseCache(false);
    }

    private AnvilTileGenerator() {}

    public record Status(boolean running, int processedRegions, int totalRegions, String message) {}

    public static Status status() {
        return new Status(RUNNING.get(), PROCESSED_REGIONS.get(), TOTAL_REGIONS.get(), LAST_STATUS);
    }

    /** Starts an asynchronous off-thread render of existing Anvil regions without touching the server tick loop. */
    public static boolean startBackgroundRender(MinecraftServer server, Path outputRoot, int maxRadiusRegions) {
        if (!RUNNING.compareAndSet(false, true)) {
            return false;
        }
        MapCalibration cal = MapCalibration.fromServer(server);
        Path regionDir = resolveDimensionRegionDir(server, cal.dimension());
        // Flush already-dirty chunks passively on server thread? No — never force chunk saves/loads; read existing .mca files.
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HNC-MapTile-Builder");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 2);
            return t;
        });
        CompletableFuture.runAsync(() -> {
            try {
                renderWorldRegions(regionDir, outputRoot, cal, maxRadiusRegions);
                WorldBoundsEnforcer.invalidate(server);
            } catch (Exception ex) {
                LAST_STATUS = "error: " + ex.getMessage();
                LOG.error("Background map tile generation failed", ex);
            } finally {
                RUNNING.set(false);
                worker.shutdown();
            }
        }, worker);
        return true;
    }

    public static Path resolveDimensionRegionDir(MinecraftServer server, ResourceLocation dimension) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        if (Level.OVERWORLD.location().equals(dimension)) {
            return worldRoot.resolve("region");
        }
        if (Level.NETHER.location().equals(dimension)) {
            return worldRoot.resolve("DIM-1").resolve("region");
        }
        if (Level.END.location().equals(dimension)) {
            return worldRoot.resolve("DIM1").resolve("region");
        }
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dimension));
        if (level != null) {
            return worldRoot.resolve("dimensions").resolve(dimension.getNamespace()).resolve(dimension.getPath()).resolve("region");
        }
        return worldRoot.resolve("region");
    }

    public record RegionCoord(int rx, int rz, Path file) {}

    public static int renderWorldRegions(Path regionDir, Path tileRoot, MapCalibration calibration, int maxRadiusRegions) throws IOException {
        PROCESSED_REGIONS.set(0);
        TOTAL_REGIONS.set(0);
        LAST_STATUS = "scanning " + regionDir;

        if (!Files.isDirectory(regionDir)) {
            LAST_STATUS = "no region directory at " + regionDir;
            return 0;
        }

        List<RegionCoord> regions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (Path entry : stream) {
                Matcher m = REGION_NAME.matcher(entry.getFileName().toString());
                if (!m.matches()) continue;
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                if (maxRadiusRegions > 0 && (Math.abs(rx) > maxRadiusRegions || Math.abs(rz) > maxRadiusRegions)) {
                    continue;
                }
                if (Files.size(entry) < 8192L) continue;
                regions.add(new RegionCoord(rx, rz, entry));
            }
        }
        regions.sort(Comparator.comparingInt((RegionCoord r) -> Math.abs(r.rx()) + Math.abs(r.rz())));
        TOTAL_REGIONS.set(regions.size());

        Path dimDir = tileRoot.resolve(MapCalibration.dimensionFolder(calibration.dimension()));
        for (int lod = MapCalibration.MIN_LOD; lod <= MapCalibration.MAX_LOD; lod++) {
            Files.createDirectories(dimDir.resolve("z" + lod));
        }

        Set<Long> dirtyNextLod = collectExistingLod1Parents(dimDir.resolve("z1"));
        Set<Long> batchDirty = new HashSet<>();
        int renderedCount = 0;

        for (RegionCoord reg : regions) {
            LAST_STATUS = "rendering r." + reg.rx() + "." + reg.rz() + ".mca";
            if (renderSingleRegion(reg, dimDir)) {
                renderedCount++;
                // Region (rx, rz) corresponds 1:1 to LOD 1 tile (rx, rz), whose parent at LOD 2 is (rx >> 1, rz >> 1).
                long parent = packTile(Math.floorDiv(reg.rx(), 2), Math.floorDiv(reg.rz(), 2));
                dirtyNextLod.add(parent);
                batchDirty.add(parent);
                if (batchDirty.size() >= 32) {
                    buildPyramidFromLod2(dimDir, batchDirty);
                    batchDirty.clear();
                }
            }
            PROCESSED_REGIONS.incrementAndGet();
        }

        // Final full LOD 2..7 mipmap pyramid pass covering both newly rendered and pre-existing z1 tiles.
        buildPyramidFromLod2(dimDir, dirtyNextLod);

        calibration.writeCalibrationFile(tileRoot, "EQUIRECTANGULAR_MOTFE_1_500");
        LAST_STATUS = "completed (" + renderedCount + " regions)";
        return renderedCount;
    }

    private static Set<Long> collectExistingLod1Parents(Path z1Dir) {
        Set<Long> parents = new HashSet<>();
        if (!Files.isDirectory(z1Dir)) return parents;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(z1Dir, "*.png")) {
            for (Path entry : stream) {
                Matcher m = TILE_NAME.matcher(entry.getFileName().toString());
                if (!m.matches()) continue;
                int tx = Integer.parseInt(m.group(1));
                int tz = Integer.parseInt(m.group(2));
                parents.add(packTile(Math.floorDiv(tx, 2), Math.floorDiv(tz, 2)));
            }
        } catch (IOException ex) {
            LOG.debug("Could not scan existing z1 tiles in {}", z1Dir, ex);
        }
        return parents;
    }

    private static void buildPyramidFromLod2(Path dimDir, Set<Long> seedLod2) throws IOException {
        Set<Long> currentDirty = new HashSet<>(seedLod2);
        for (int lod = 2; lod <= MapCalibration.MAX_LOD && !currentDirty.isEmpty(); lod++) {
            LAST_STATUS = "building pyramid z" + lod + " (" + currentDirty.size() + " tiles)";
            Set<Long> parentDirty = new HashSet<>();
            for (long packed : currentDirty) {
                int tx = unpackX(packed);
                int tz = unpackZ(packed);
                if (downsampleTile(dimDir, lod, tx, tz)) {
                    parentDirty.add(packTile(Math.floorDiv(tx, 2), Math.floorDiv(tz, 2)));
                }
            }
            currentDirty = parentDirty;
        }
    }

    /**
     * Renders one 512x512 block Anvil region into:
     * - Four 256x256 px tiles at z0 (1 block = 1 px)
     * - One 256x256 px tile at z1 (2 blocks = 1 px)
     */
    private static boolean renderSingleRegion(RegionCoord reg, Path dimDir) {
        int[] argb512 = new int[512 * 512];
        short[] heights = new short[512 * 512];
        boolean anyChunk = false;

        try (RandomAccessFile raf = new RandomAccessFile(reg.file().toFile(), "r")) {
            if (raf.length() < 8192L) return false;
            byte[] locations = new byte[4096];
            raf.readFully(locations);

            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    int idx = 4 * (cx + cz * 32);
                    int sectorOffset = ((locations[idx] & 0xFF) << 16)
                            | ((locations[idx + 1] & 0xFF) << 8)
                            | (locations[idx + 2] & 0xFF);
                    int sectorCount = locations[idx + 3] & 0xFF;
                    if (sectorOffset < 2 || sectorCount == 0) continue;

                    long bytePos = sectorOffset * 4096L;
                    if (bytePos + 5L > raf.length()) continue;
                    raf.seek(bytePos);
                    int length = raf.readInt();
                    int compression = raf.readUnsignedByte();
                    if (length <= 1 || bytePos + 4L + length > raf.length()) continue;

                    byte[] raw = new byte[length - 1];
                    raf.readFully(raw);
                    CompoundTag chunkTag = readChunkNbt(raw, compression);
                    if (chunkTag == null) continue;

                    int dataVer = chunkTag.getInt("DataVersion");
                    if (dataVer > MC_1_20_1_DATA_VERSION) {
                        LOG.warn("Skipping chunk in {} with unsupported newer Minecraft DataVersion {} (> 1.20.1 / 3465)",
                                reg.file().getFileName(), dataVer);
                        continue;
                    }
                    if (shadeChunkColumns(chunkTag, cx * 16, cz * 16, argb512, heights)) {
                        anyChunk = true;
                    }
                }
            }
        } catch (Exception ex) {
            LOG.warn("Failed reading region {}", reg.file(), ex);
            return false;
        }

        if (!anyChunk) return false;

        applyHillshade(argb512, heights);

        try {
            // Write four z0 (256x256, 1 block/px) tiles
            for (int qz = 0; qz < 2; qz++) {
                for (int qx = 0; qx < 2; qx++) {
                    BufferedImage z0 = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                    boolean nonEmpty = false;
                    for (int py = 0; py < 256; py++) {
                        int srcRow = (qz * 256 + py) * 512 + qx * 256;
                        for (int px = 0; px < 256; px++) {
                            int c = argb512[srcRow + px];
                            if ((c >>> 24) != 0) nonEmpty = true;
                            z0.setRGB(px, py, c);
                        }
                    }
                    if (nonEmpty) {
                        int tx0 = reg.rx() * 2 + qx;
                        int tz0 = reg.rz() * 2 + qz;
                        writePngAtomic(z0, dimDir.resolve("z0").resolve(tx0 + "_" + tz0 + ".png"));
                    }
                }
            }

            // Write one z1 (256x256, 2 blocks/px) tile
            BufferedImage z1 = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            for (int py = 0; py < 256; py++) {
                for (int px = 0; px < 256; px++) {
                    int c00 = argb512[(py * 2) * 512 + (px * 2)];
                    int c10 = argb512[(py * 2) * 512 + (px * 2 + 1)];
                    int c01 = argb512[(py * 2 + 1) * 512 + (px * 2)];
                    int c11 = argb512[(py * 2 + 1) * 512 + (px * 2 + 1)];
                    z1.setRGB(px, py, average4(c00, c10, c01, c11));
                }
            }
            writePngAtomic(z1, dimDir.resolve("z1").resolve(reg.rx() + "_" + reg.rz() + ".png"));
            return true;
        } catch (IOException ex) {
            LOG.warn("Failed writing tiles for region ({}, {})", reg.rx(), reg.rz(), ex);
            return false;
        }
    }

    private static boolean downsampleTile(Path dimDir, int lod, int tx, int tz) throws IOException {
        BufferedImage dst = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        boolean any = false;
        Path childDir = dimDir.resolve("z" + (lod - 1));
        for (int qz = 0; qz < 2; qz++) {
            for (int qx = 0; qx < 2; qx++) {
                Path childFile = childDir.resolve((tx * 2 + qx) + "_" + (tz * 2 + qz) + ".png");
                if (!Files.isRegularFile(childFile)) continue;
                BufferedImage src;
                try (InputStream in = new BufferedInputStream(Files.newInputStream(childFile))) {
                    src = ImageIO.read(in);
                }
                if (src == null || src.getWidth() != 256 || src.getHeight() != 256) continue;
                any = true;
                for (int py = 0; py < 128; py++) {
                    for (int px = 0; px < 128; px++) {
                        int c = average4(
                                src.getRGB(px * 2, py * 2),
                                src.getRGB(px * 2 + 1, py * 2),
                                src.getRGB(px * 2, py * 2 + 1),
                                src.getRGB(px * 2 + 1, py * 2 + 1)
                        );
                        dst.setRGB(qx * 128 + px, qz * 128 + py, c);
                    }
                }
            }
        }
        if (!any) return false;
        writePngAtomic(dst, dimDir.resolve("z" + lod).resolve(tx + "_" + tz + ".png"));
        return true;
    }

    private static void writePngAtomic(BufferedImage image, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ImageIO.write(image, "PNG", out);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static CompoundTag readChunkNbt(byte[] raw, int compression) throws IOException {
        InputStream base = new ByteArrayInputStream(raw);
        InputStream decompressed = switch (compression) {
            case 1 -> new GZIPInputStream(base);
            case 2 -> new InflaterInputStream(base);
            case 3 -> base;
            default -> null;
        };
        if (decompressed == null) return null;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(decompressed))) {
            return NbtIo.read(dis);
        }
    }

    private record ParsedSection(int sectionY, String[] palette, long[] data, int bits, int perLong) {
        String blockAt(int lx, int ly, int lz) {
            if (palette.length == 0) return "minecraft:air";
            if (palette.length == 1 || data == null || data.length == 0) return palette[0];
            int blockIdx = (ly << 8) | (lz << 4) | lx;
            int longIdx = blockIdx / perLong;
            if (longIdx >= data.length) return palette[0];
            int bitOffset = (blockIdx % perLong) * bits;
            int pIdx = (int) ((data[longIdx] >>> bitOffset) & ((1L << bits) - 1L));
            return pIdx >= 0 && pIdx < palette.length ? palette[pIdx] : palette[0];
        }
    }

    private static boolean shadeChunkColumns(CompoundTag chunk, int baseX, int baseZ, int[] argb512, short[] heights) {
        String status = chunk.getString("Status");
        if (status.isEmpty() || status.endsWith("empty")) return false;
        ListTag secTags = chunk.getList("sections", Tag.TAG_COMPOUND);
        if (secTags.isEmpty()) return false;

        List<ParsedSection> sections = new ArrayList<>(secTags.size());
        for (int i = 0; i < secTags.size(); i++) {
            CompoundTag sec = secTags.getCompound(i);
            if (!sec.contains("block_states", Tag.TAG_COMPOUND)) continue;
            CompoundTag bs = sec.getCompound("block_states");
            ListTag palTag = bs.getList("palette", Tag.TAG_COMPOUND);
            if (palTag.isEmpty()) continue;
            String[] palette = new String[palTag.size()];
            boolean allAir = true;
            for (int p = 0; p < palTag.size(); p++) {
                String name = palTag.getCompound(p).getString("Name");
                palette[p] = name;
                if (!isAir(name)) allAir = false;
            }
            if (allAir) continue;
            long[] data = bs.contains("data", Tag.TAG_LONG_ARRAY) ? bs.getLongArray("data") : null;
            int bits = palette.length <= 1 ? 0 : Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
            int perLong = bits == 0 ? 1 : 64 / bits;
            sections.add(new ParsedSection(sec.getByte("Y"), palette, data, bits, perLong));
        }
        if (sections.isEmpty()) return false;
        sections.sort(Comparator.comparingInt(ParsedSection::sectionY).reversed());

        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int waterY = Integer.MIN_VALUE;
                int surfaceY = -64;
                String surfaceBlock = "minecraft:stone";
                boolean foundSolid = false;

                outer:
                for (ParsedSection sec : sections) {
                    int secBaseY = sec.sectionY() << 4;
                    for (int ly = 15; ly >= 0; ly--) {
                        String block = sec.blockAt(lx, ly, lz);
                        if (isAir(block)) continue;
                        int y = secBaseY + ly;
                        if (isWater(block)) {
                            if (waterY == Integer.MIN_VALUE) waterY = y;
                            continue;
                        }
                        surfaceY = y;
                        surfaceBlock = block;
                        foundSolid = true;
                        break outer;
                    }
                }

                int outIdx = (baseZ + lz) * 512 + (baseX + lx);
                if (!foundSolid && waterY == Integer.MIN_VALUE) {
                    continue;
                }
                if (waterY != Integer.MIN_VALUE) {
                    int depth = Math.max(1, waterY - surfaceY);
                    argb512[outIdx] = waterColor(depth);
                    heights[outIdx] = (short) waterY;
                } else {
                    argb512[outIdx] = terrainColor(surfaceBlock, surfaceY);
                    heights[outIdx] = (short) surfaceY;
                }
            }
        }
        return true;
    }

    private static void applyHillshade(int[] argb512, short[] heights) {
        for (int z = 0; z < 512; z++) {
            for (int x = 0; x < 512; x++) {
                int idx = z * 512 + x;
                int c = argb512[idx];
                if ((c >>> 24) == 0) continue;
                int xm = Math.max(0, x - 1);
                int xp = Math.min(511, x + 1);
                int zm = Math.max(0, z - 1);
                int zp = Math.min(511, z + 1);
                int slope = (heights[zm * 512 + xm] - heights[zp * 512 + xp])
                        + ((heights[z * 512 + xm] - heights[z * 512 + xp]) >> 1)
                        + ((heights[zm * 512 + x] - heights[zp * 512 + x]) >> 1);
                if (slope == 0) continue;
                int delta = Math.max(-28, Math.min(28, slope * 4));
                int r = clampByte(((c >> 16) & 0xFF) + delta);
                int g = clampByte(((c >> 8) & 0xFF) + delta);
                int b = clampByte((c & 0xFF) + delta);
                argb512[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static boolean isAir(String name) {
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
    }

    private static boolean isWater(String name) {
        return name.equals("minecraft:water") || name.equals("minecraft:kelp") || name.equals("minecraft:kelp_plant")
                || name.equals("minecraft:seagrass") || name.equals("minecraft:tall_seagrass")
                || name.equals("minecraft:bubble_column");
    }

    /** 1930s strategic atlas bathymetry shading. */
    private static int waterColor(int depth) {
        if (depth <= 2) return 0xFF3E6578; // coastal shallows
        if (depth <= 6) return 0xFF325468; // continental shelf
        if (depth <= 16) return 0xFF274558; // mid sea
        return 0xFF1D3647; // deep ocean
    }

    /** 1930-1950 war-room cartographic terrain palette. */
    private static int terrainColor(String block, int y) {
        String id = block.startsWith("minecraft:") ? block.substring(10) : block;
        int base;
        if (id.contains("snow") || id.contains("powder_snow")) base = 0xFFD8D7CF;
        else if (id.contains("ice")) base = 0xFF9FB8C2;
        else if (id.contains("sand") || id.contains("sandstone")) base = id.contains("red_") ? 0xFFA86F4C : 0xFFC2B289;
        else if (id.contains("terracotta")) base = 0xFF9E6B4F;
        else if (id.contains("gravel") || id.contains("clay") || id.contains("mud")) base = 0xFF7B766C;
        else if (id.contains("leaves") || id.contains("vine") || id.contains("azalea")) base = 0xFF4F6644;
        else if (id.contains("podzol") || id.contains("mycelium") || id.contains("moss")) base = 0xFF5D6B48;
        else if (id.contains("grass") || id.contains("fern") || id.contains("tall_grass")) base = 0xFF697B54;
        else if (id.contains("dirt") || id.contains("farmland") || id.contains("rooted")) base = 0xFF7E6B50;
        else if (id.contains("lava") || id.contains("magma")) base = 0xFFB84E28;
        else if (id.contains("planks") || id.contains("log") || id.contains("wood")) base = 0xFF7C6242;
        else if (id.contains("brick") || id.contains("concrete") || id.contains("cobblestone")) base = 0xFF837E75;
        else base = 0xFF75746D; // stone, deepslate, andesite, granite, mountain rock

        // Subtle hypsometric elevation tint above sea level (62)
        int elev = Math.max(-8, Math.min(22, (y - 63) / 6));
        int r = clampByte(((base >> 16) & 0xFF) + elev);
        int g = clampByte(((base >> 8) & 0xFF) + elev);
        int b = clampByte((base & 0xFF) + (elev / 2));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int average4(int c0, int c1, int c2, int c3) {
        int[] cols = {c0, c1, c2, c3};
        int count = 0, r = 0, g = 0, b = 0;
        for (int c : cols) {
            int ca = (c >>> 24) & 0xFF;
            if (ca > 0) {
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                count++;
            }
        }
        if (count == 0) return 0;
        // Preserve solid opacity whenever any sub-quadrant has generated terrain/water so islands,
        // coastlines, and partial worlds never fade to alpha=0 across LOD z2..z7.
        return 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static long packTile(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    /** Standalone CLI tool so administrators can pre-generate tiles on D:\ without launching the server. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: AnvilTileGenerator <worldRegionDir> <outputMapTilesDir> [dimension]");
            System.exit(1);
        }
        Path regionDir = Path.of(args[0]).toAbsolutePath().normalize();
        Path tileRoot = Path.of(args[1]).toAbsolutePath().normalize();
        ResourceLocation dim = args.length >= 3
                ? java.util.Objects.requireNonNull(ResourceLocation.tryParse(args[2]), "Invalid dimension ID")
                : ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        MapCalibration cal = new MapCalibration(500, dim, true, true,
                -36864, 36863, -18432, 18431, 0, 0, 204.8D);
        System.out.println("Rendering Anvil regions from " + regionDir + " -> " + tileRoot + " ...");
        int count = renderWorldRegions(regionDir, tileRoot, cal, 0);
        System.out.println("Finished rendering " + count + " region(s) across LOD z0..z7.");
    }
}
