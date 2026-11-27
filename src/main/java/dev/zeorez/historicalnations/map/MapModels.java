package dev.zeorez.historicalnations.map;

import dev.zeorez.historicalnations.TerritoryConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Shared server/client DTOs and bounded Deflate binary serialization for the Political World Map. */
public final class MapModels {
    public static final int MAX_UNCOMPRESSED_BYTES = 2 * 1024 * 1024;
    public static final int MAX_COMPRESSED_PACKET_BYTES = 786_432;

    private MapModels() {}

    public enum FocusKind { NONE, PLAYER, COORDS, TOWN, NATION, WAR_MODE }

    public record ServerMapSettings(
            boolean enabled,
            TerritoryConfig.MapMode defaultMode,
            boolean showNationBorders,
            boolean showTownBorders,
            boolean showTownNames,
            boolean showNationNames,
            boolean enableTownInfoPopup,
            boolean enableSearch,
            boolean lockCameraToBounds,
            boolean enableTileCache,
            int maxVisibleLabels,
            int maxCachedTiles
    ) {
        public ServerMapSettings(
                boolean enabled,
                TerritoryConfig.MapMode defaultMode,
                boolean showNationBorders,
                boolean showTownBorders,
                boolean showTownNames,
                boolean showNationNames,
                boolean enableTownInfoPopup,
                boolean enableSearch,
                boolean enableTileCache,
                int maxVisibleLabels,
                int maxCachedTiles
        ) {
            this(enabled, defaultMode, showNationBorders, showTownBorders, showTownNames, showNationNames,
                    enableTownInfoPopup, enableSearch, true, enableTileCache, maxVisibleLabels, maxCachedTiles);
        }

        public static ServerMapSettings fromConfig() {
            return new ServerMapSettings(
                    TerritoryConfig.MAP_ENABLED.get(),
                    TerritoryConfig.DEFAULT_MAP_MODE.get(),
                    TerritoryConfig.SHOW_NATION_BORDERS.get(),
                    TerritoryConfig.SHOW_TOWN_BORDERS.get(),
                    TerritoryConfig.SHOW_TOWN_NAMES.get(),
                    TerritoryConfig.SHOW_NATION_NAMES.get(),
                    TerritoryConfig.ENABLE_TOWN_INFO_POPUP.get(),
                    TerritoryConfig.ENABLE_MAP_SEARCH.get(),
                    TerritoryConfig.LOCK_CAMERA_TO_BOUNDS.get(),
                    TerritoryConfig.ENABLE_TILE_CACHE.get(),
                    TerritoryConfig.MAX_VISIBLE_LABELS.get(),
                    TerritoryConfig.MAX_CACHED_TILES.get()
            );
        }

        public void write(FriendlyByteBuf b) {
            b.writeBoolean(enabled);
            b.writeEnum(defaultMode);
            b.writeBoolean(showNationBorders);
            b.writeBoolean(showTownBorders);
            b.writeBoolean(showTownNames);
            b.writeBoolean(showNationNames);
            b.writeBoolean(enableTownInfoPopup);
            b.writeBoolean(enableSearch);
            b.writeBoolean(lockCameraToBounds);
            b.writeBoolean(enableTileCache);
            b.writeVarInt(maxVisibleLabels);
            b.writeVarInt(maxCachedTiles);
        }

        public static ServerMapSettings read(FriendlyByteBuf b) {
            return new ServerMapSettings(
                    b.readBoolean(),
                    b.readEnum(TerritoryConfig.MapMode.class),
                    b.readBoolean(),
                    b.readBoolean(),
                    b.readBoolean(),
                    b.readBoolean(),
                    b.readBoolean(),
                    b.readBoolean(),
                    b.readBoolean(),
                    b.readBoolean(),
                    b.readVarInt(),
                    b.readVarInt()
            );
        }
    }

    public record NationSummary(
            UUID id,
            String name,
            UUID capitalTownId,
            String leaderName,
            int colorRgb,
            int townCount,
            int population,
            int claimCount,
            String currencyCode
    ) {
        public void write(FriendlyByteBuf b) {
            b.writeUUID(id);
            b.writeUtf(name, 64);
            b.writeUUID(capitalTownId);
            b.writeUtf(leaderName, 64);
            b.writeInt(colorRgb);
            b.writeVarInt(townCount);
            b.writeVarInt(population);
            b.writeVarInt(claimCount);
            b.writeUtf(currencyCode, 16);
        }

        public static NationSummary read(FriendlyByteBuf b) {
            return new NationSummary(
                    b.readUUID(),
                    b.readUtf(64),
                    b.readUUID(),
                    b.readUtf(64),
                    b.readInt() & 0xFFFFFF,
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readUtf(16)
            );
        }
    }

    public record TownSummary(
            UUID id,
            String name,
            String mayorName,
            UUID nationId,
            int population,
            int claimCount,
            int homeBlockX,
            int homeBlockZ,
            boolean hasClaims,
            int level, int score, int rating
    ) {
        public TownSummary withEvaluation(int level, int score, int rating) {
            return new TownSummary(id, name, mayorName, nationId, population, claimCount, homeBlockX, homeBlockZ,
                    hasClaims, level, score, rating);
        }
        public void write(FriendlyByteBuf b) {
            b.writeUUID(id);
            b.writeUtf(name, 64);
            b.writeUtf(mayorName, 64);
            b.writeNullable(nationId, FriendlyByteBuf::writeUUID);
            b.writeVarInt(population);
            b.writeVarInt(claimCount);
            b.writeInt(homeBlockX);
            b.writeInt(homeBlockZ);
            b.writeBoolean(hasClaims);
            b.writeByte(level);
            b.writeByte(score);
            b.writeByte(rating);
        }

        public static TownSummary read(FriendlyByteBuf b) {
            return new TownSummary(
                    b.readUUID(),
                    b.readUtf(64),
                    b.readUtf(64),
                    b.readNullable(FriendlyByteBuf::readUUID),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readBoolean(), b.readByte(), b.readByte(), b.readByte()
            );
        }
    }

    public record ClaimEntry(int chunkX, int chunkZ, UUID townId) {
        public void write(FriendlyByteBuf b) {
            b.writeInt(chunkX);
            b.writeInt(chunkZ);
            b.writeNullable(townId, FriendlyByteBuf::writeUUID);
        }

        public static ClaimEntry read(FriendlyByteBuf b) {
            return new ClaimEntry(b.readInt(), b.readInt(), b.readNullable(FriendlyByteBuf::readUUID));
        }
    }

    public record DeltaPayload(
            List<NationSummary> upsertedNations,
            List<UUID> removedNations,
            List<TownSummary> upsertedTowns,
            List<UUID> removedTowns,
            List<ClaimEntry> changedClaims
    ) {
        public boolean isEmpty() {
            return upsertedNations.isEmpty() && removedNations.isEmpty()
                    && upsertedTowns.isEmpty() && removedTowns.isEmpty()
                    && changedClaims.isEmpty();
        }

        public byte[] encodeCompressed() {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeVarInt(upsertedNations.size());
                for (NationSummary n : upsertedNations) n.write(buf);
                buf.writeVarInt(removedNations.size());
                for (UUID id : removedNations) buf.writeUUID(id);
                buf.writeVarInt(upsertedTowns.size());
                for (TownSummary t : upsertedTowns) t.write(buf);
                buf.writeVarInt(removedTowns.size());
                for (UUID id : removedTowns) buf.writeUUID(id);
                buf.writeVarInt(changedClaims.size());
                for (ClaimEntry c : changedClaims) c.write(buf);
                return compress(buf);
            } finally {
                buf.release();
            }
        }

        public static DeltaPayload decodeCompressed(byte[] compressed) {
            FriendlyByteBuf buf = decompress(compressed);
            try {
                int un = buf.readVarInt();
                List<NationSummary> nations = new ArrayList<>(un);
                for (int i = 0; i < un; i++) nations.add(NationSummary.read(buf));

                int rn = buf.readVarInt();
                List<UUID> remNations = new ArrayList<>(rn);
                for (int i = 0; i < rn; i++) remNations.add(buf.readUUID());

                int ut = buf.readVarInt();
                List<TownSummary> towns = new ArrayList<>(ut);
                for (int i = 0; i < ut; i++) towns.add(TownSummary.read(buf));

                int rt = buf.readVarInt();
                List<UUID> remTowns = new ArrayList<>(rt);
                for (int i = 0; i < rt; i++) remTowns.add(buf.readUUID());

                int cc = buf.readVarInt();
                List<ClaimEntry> claims = new ArrayList<>(cc);
                for (int i = 0; i < cc; i++) claims.add(ClaimEntry.read(buf));

                return new DeltaPayload(nations, remNations, towns, remTowns, claims);
            } finally {
                buf.release();
            }
        }
    }

    public static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int unpackChunkX(long key) {
        return (int) (key >> 32);
    }

    public static int unpackChunkZ(long key) {
        return (int) key;
    }

    public static byte[] compress(FriendlyByteBuf buf) {
        byte[] raw = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), raw);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
            dos.write(raw);
        } catch (IOException ex) {
            throw new IllegalStateException("Compression failed", ex);
        } finally {
            deflater.end();
        }
        return baos.toByteArray();
    }

    public static FriendlyByteBuf decompress(byte[] compressed) {
        if (compressed.length > MAX_COMPRESSED_PACKET_BYTES) {
            throw new IllegalArgumentException("Compressed payload exceeds limit: " + compressed.length);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(MAX_UNCOMPRESSED_BYTES, compressed.length * 4 + 64));
        byte[] chunk = new byte[4096];
        int total = 0;
        try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = iis.read(chunk)) != -1) {
                total += read;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw new IllegalArgumentException("Decompressed map payload exceeds safety limit");
                }
                out.write(chunk, 0, read);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Corrupt compressed map payload", ex);
        }
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(out.toByteArray()));
    }
}
