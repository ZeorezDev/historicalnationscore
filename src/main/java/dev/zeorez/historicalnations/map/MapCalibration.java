package dev.zeorez.historicalnations.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.TerritoryConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Authoritative coordinate and tile-pyramid calibration.
 * Primary coordinates are ALWAYS exact Minecraft block (X, Z) and chunk (X >> 4, Z >> 4).
 * Geographic degree readouts are enabled only when calibration metadata is verified or explicitly allowed.
 */
public record MapCalibration(
        int scale,
        ResourceLocation dimension,
        boolean requireCalibration,
        boolean calibrated,
        int minBlockX,
        int maxBlockX,
        int minBlockZ,
        int maxBlockZ,
        int originBlockX,
        int originBlockZ,
        double blocksPerDegree
) {
    public static final int TILE_PIXELS = 256;
    public static final int MIN_LOD = 0;
    public static final int MAX_LOD = 7;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static int blocksPerPixel(int lod) {
        int clamped = Math.max(MIN_LOD, Math.min(MAX_LOD, lod));
        return 1 << clamped;
    }

    public static int blocksPerTile(int lod) {
        return TILE_PIXELS * blocksPerPixel(lod);
    }

    public static int blockToTile(int blockCoord, int lod) {
        return Math.floorDiv(blockCoord, blocksPerTile(lod));
    }

    public static int tileMinBlock(int tileCoord, int lod) {
        return tileCoord * blocksPerTile(lod);
    }

    public static String dimensionFolder(ResourceLocation dim) {
        return dim.getNamespace() + "_" + dim.getPath().replace('/', '_');
    }

    public boolean geographicReady() {
        return blocksPerDegree > 0.0D && (!requireCalibration || calibrated);
    }

    public double toLongitude(double blockX) {
        return (blockX - originBlockX) / blocksPerDegree;
    }

    public double toLatitude(double blockZ) {
        return -(blockZ - originBlockZ) / blocksPerDegree;
    }

    public int longitudeToBlockX(double lon) {
        return (int) Math.round(originBlockX + lon * blocksPerDegree);
    }

    public int latitudeToBlockZ(double lat) {
        return (int) Math.round(originBlockZ - lat * blocksPerDegree);
    }

    public String formatGeo(int blockX, int blockZ) {
        if (!geographicReady()) return "";
        double lat = toLatitude(blockZ);
        double lon = toLongitude(blockX);
        if (Math.abs(lat) > 90.5D || Math.abs(lon) > 180.5D) return "";
        char ns = lat >= 0 ? 'N' : 'S';
        char ew = lon >= 0 ? 'E' : 'W';
        return String.format(Locale.ROOT, "%.2f°%c, %.2f°%c", Math.abs(lat), ns, Math.abs(lon), ew);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(scale);
        buf.writeResourceLocation(dimension);
        buf.writeBoolean(requireCalibration);
        buf.writeBoolean(calibrated);
        buf.writeInt(minBlockX);
        buf.writeInt(maxBlockX);
        buf.writeInt(minBlockZ);
        buf.writeInt(maxBlockZ);
        buf.writeInt(originBlockX);
        buf.writeInt(originBlockZ);
        buf.writeDouble(blocksPerDegree);
    }

    public static MapCalibration read(FriendlyByteBuf buf) {
        return new MapCalibration(
                buf.readVarInt(),
                buf.readResourceLocation(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readInt(),
                buf.readDouble()
        );
    }

    public static Path resolveTileRoot(MinecraftServer server) {
        Path worldLocal = server.getWorldPath(LevelResource.ROOT).resolve("map_tiles").normalize();
        if (Files.isDirectory(worldLocal)) {
            return worldLocal;
        }
        String configured = TerritoryConfig.MAP_TILE_DIRECTORY.get().strip();
        if (configured.isEmpty()) {
            return worldLocal;
        }
        Path cfgPath = Path.of(configured);
        if (!cfgPath.isAbsolute()) {
            cfgPath = server.getServerDirectory().toPath().resolve(cfgPath);
        }
        return cfgPath.normalize();
    }

    public static MapCalibration fromServer(MinecraftServer server) {
        ResourceLocation dim = ResourceLocation.tryParse(TerritoryConfig.EARTH_DIMENSION.get());
        if (dim == null) dim = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

        int scale = TerritoryConfig.EARTH_SCALE.get();
        boolean reqCal = TerritoryConfig.REQUIRE_MAP_CALIBRATION.get();
        int minX = TerritoryConfig.EARTH_MIN_BLOCK_X.get();
        int maxX = TerritoryConfig.EARTH_MAX_BLOCK_X.get();
        int minZ = TerritoryConfig.EARTH_MIN_BLOCK_Z.get();
        int maxZ = TerritoryConfig.EARTH_MAX_BLOCK_Z.get();
        int origX = TerritoryConfig.EARTH_ORIGIN_BLOCK_X.get();
        int origZ = TerritoryConfig.EARTH_ORIGIN_BLOCK_Z.get();
        double bpd = TerritoryConfig.EARTH_BLOCKS_PER_DEGREE.get();
        boolean calibrated = false;

        Path root = resolveTileRoot(server);
        Path calFile = root.resolve("calibration.json");
        if (Files.isRegularFile(calFile)) {
            try {
                JsonObject json = GSON.fromJson(Files.readString(calFile, StandardCharsets.UTF_8), JsonObject.class);
                if (json != null) {
                    if (json.has("scale")) scale = json.get("scale").getAsInt();
                    if (json.has("minBlockX")) minX = json.get("minBlockX").getAsInt();
                    if (json.has("maxBlockX")) maxX = json.get("maxBlockX").getAsInt();
                    if (json.has("minBlockZ")) minZ = json.get("minBlockZ").getAsInt();
                    if (json.has("maxBlockZ")) maxZ = json.get("maxBlockZ").getAsInt();
                    if (json.has("originBlockX")) origX = json.get("originBlockX").getAsInt();
                    if (json.has("originBlockZ")) origZ = json.get("originBlockZ").getAsInt();
                    if (json.has("blocksPerDegree")) bpd = json.get("blocksPerDegree").getAsDouble();
                    calibrated = json.has("verified") && json.get("verified").getAsBoolean();
                }
            } catch (Exception ex) {
                LogUtils.getLogger().warn("Could not parse map calibration.json at {}", calFile, ex);
            }
        }
        return new MapCalibration(scale, dim, reqCal, calibrated, minX, maxX, minZ, maxZ, origX, origZ, bpd);
    }

    public void writeCalibrationFile(Path tileRoot, String projectionNote) throws IOException {
        Files.createDirectories(tileRoot);
        JsonObject json = new JsonObject();
        json.addProperty("formatVersion", 1);
        json.addProperty("verified", true);
        json.addProperty("projection", projectionNote);
        json.addProperty("minecraftVersion", "1.20.1");
        json.addProperty("scale", scale);
        json.addProperty("dimension", dimension.toString());
        json.addProperty("minBlockX", minBlockX);
        json.addProperty("maxBlockX", maxBlockX);
        json.addProperty("minBlockZ", minBlockZ);
        json.addProperty("maxBlockZ", maxBlockZ);
        json.addProperty("originBlockX", originBlockX);
        json.addProperty("originBlockZ", originBlockZ);
        json.addProperty("blocksPerDegree", blocksPerDegree);
        json.addProperty("tilePixels", TILE_PIXELS);
        json.addProperty("minLod", MIN_LOD);
        json.addProperty("maxLod", MAX_LOD);
        Path tmp = tileRoot.resolve("calibration.json.tmp");
        Path dest = tileRoot.resolve("calibration.json");
        Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
