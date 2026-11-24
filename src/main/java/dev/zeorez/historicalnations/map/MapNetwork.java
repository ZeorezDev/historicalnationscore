package dev.zeorez.historicalnations.map;

import dev.zeorez.historicalnations.HistoricalNations;
import dev.zeorez.historicalnations.map.MapModels.FocusKind;
import dev.zeorez.historicalnations.map.MapModels.ServerMapSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;

/**
 * Dedicated Forge 1.20.1 network channel for the Political World Map.
 * Strictly zero client-only class imports so Dedicated Servers never load Dist.CLIENT classes.
 */
public final class MapNetwork {
    public static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(HistoricalNations.ID, "world_map"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL)
    );

    public static final byte KIND_NATIONS = 0;
    public static final byte KIND_TOWNS = 1;
    public static final byte KIND_CLAIMS = 2;
    public static final byte KIND_COMPLETE = 3;

    public interface ClientHandler {
        void onOpenMap(OpenMapPacket pkt);
        void onUpToDate(MapUpToDatePacket pkt);
        void onSnapshotHeader(MapSnapshotHeaderPacket pkt);
        void onSnapshotPart(MapSnapshotPartPacket pkt);
        void onDelta(MapDeltaPacket pkt);
        void onTileResponse(TileResponsePacket pkt);
    }

    private static volatile ClientHandler clientHandler;

    private MapNetwork() {}

    public static void setClientHandler(ClientHandler handler) {
        clientHandler = handler;
    }

    public static boolean canSend(ServerPlayer player) {
        return player != null
                && player.connection != null
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && CHANNEL.isRemotePresent(player.connection.connection);
    }

    public static void sendToPlayer(ServerPlayer player, Object packet) {
        if (canSend(player)) {
            CHANNEL.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    public static boolean requestClientOpen(ServerPlayer player, FocusKind focus, UUID targetId, int blockX, int blockZ) {
        if (!canSend(player)) return false;
        sendToPlayer(player, new OpenMapPacket(focus, targetId, blockX, blockZ));
        return true;
    }

    public record OpenMapPacket(FocusKind focus, UUID targetId, int blockX, int blockZ) {
        void write(FriendlyByteBuf b) {
            b.writeEnum(focus);
            b.writeNullable(targetId, FriendlyByteBuf::writeUUID);
            b.writeInt(blockX);
            b.writeInt(blockZ);
        }

        static OpenMapPacket read(FriendlyByteBuf b) {
            return new OpenMapPacket(
                    b.readEnum(FocusKind.class),
                    b.readNullable(FriendlyByteBuf::readUUID),
                    b.readInt(),
                    b.readInt()
            );
        }
    }

    public record MapSubscribePacket(UUID knownMapId, long knownRevision, boolean subscribe) {
        void write(FriendlyByteBuf b) {
            b.writeNullable(knownMapId, FriendlyByteBuf::writeUUID);
            b.writeLong(knownRevision);
            b.writeBoolean(subscribe);
        }

        static MapSubscribePacket read(FriendlyByteBuf b) {
            return new MapSubscribePacket(
                    b.readNullable(FriendlyByteBuf::readUUID),
                    b.readLong(),
                    b.readBoolean()
            );
        }
    }

    public record MapUpToDatePacket(UUID mapId, long revision, MapCalibration calibration, ServerMapSettings settings) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(mapId);
            b.writeLong(revision);
            calibration.write(b);
            settings.write(b);
        }

        static MapUpToDatePacket read(FriendlyByteBuf b) {
            return new MapUpToDatePacket(b.readUUID(), b.readLong(), MapCalibration.read(b), ServerMapSettings.read(b));
        }
    }

    public record MapSnapshotHeaderPacket(
            UUID mapId,
            long revision,
            MapCalibration calibration,
            ServerMapSettings settings,
            int nationBatches,
            int townBatches,
            int claimBatches
    ) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(mapId);
            b.writeLong(revision);
            calibration.write(b);
            settings.write(b);
            b.writeVarInt(nationBatches);
            b.writeVarInt(townBatches);
            b.writeVarInt(claimBatches);
        }

        static MapSnapshotHeaderPacket read(FriendlyByteBuf b) {
            return new MapSnapshotHeaderPacket(
                    b.readUUID(),
                    b.readLong(),
                    MapCalibration.read(b),
                    ServerMapSettings.read(b),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readVarInt()
            );
        }
    }

    public record MapSnapshotPartPacket(UUID mapId, long revision, byte kind, int batchIndex, byte[] compressed) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(mapId);
            b.writeLong(revision);
            b.writeByte(kind);
            b.writeVarInt(batchIndex);
            b.writeByteArray(compressed);
        }

        static MapSnapshotPartPacket read(FriendlyByteBuf b) {
            return new MapSnapshotPartPacket(
                    b.readUUID(),
                    b.readLong(),
                    b.readByte(),
                    b.readVarInt(),
                    b.readByteArray(MapModels.MAX_COMPRESSED_PACKET_BYTES)
            );
        }
    }

    public record MapDeltaPacket(UUID mapId, long fromRevision, long toRevision, byte[] compressedDelta) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(mapId);
            b.writeLong(fromRevision);
            b.writeLong(toRevision);
            b.writeByteArray(compressedDelta);
        }

        static MapDeltaPacket read(FriendlyByteBuf b) {
            return new MapDeltaPacket(
                    b.readUUID(),
                    b.readLong(),
                    b.readLong(),
                    b.readByteArray(MapModels.MAX_COMPRESSED_PACKET_BYTES)
            );
        }
    }

    public record TileRequestPacket(UUID mapId, int lod, int tx, int tz) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(mapId);
            b.writeVarInt(lod);
            b.writeInt(tx);
            b.writeInt(tz);
        }

        static TileRequestPacket read(FriendlyByteBuf b) {
            return new TileRequestPacket(b.readUUID(), b.readVarInt(), b.readInt(), b.readInt());
        }
    }

    public record TileResponsePacket(UUID mapId, int lod, int tx, int tz, boolean exists, byte[] pngBytes) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(mapId);
            b.writeVarInt(lod);
            b.writeInt(tx);
            b.writeInt(tz);
            b.writeBoolean(exists);
            b.writeByteArray(pngBytes);
        }

        static TileResponsePacket read(FriendlyByteBuf b) {
            return new TileResponsePacket(
                    b.readUUID(),
                    b.readVarInt(),
                    b.readInt(),
                    b.readInt(),
                    b.readBoolean(),
                    b.readByteArray(MapModels.MAX_COMPRESSED_PACKET_BYTES)
            );
        }
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(OpenMapPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(OpenMapPacket::write).decoder(OpenMapPacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onOpenMap(pkt);
                }).add();

        CHANNEL.messageBuilder(MapSubscribePacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(MapSubscribePacket::write).decoder(MapSubscribePacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ServerPlayer sp = ctx.get().getSender();
                    if (sp != null && sp.server != null) {
                        PoliticalMapServer.get(sp.server).handleSubscribe(sp, pkt.knownMapId(), pkt.knownRevision(), pkt.subscribe());
                    }
                }).add();

        CHANNEL.messageBuilder(MapUpToDatePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MapUpToDatePacket::write).decoder(MapUpToDatePacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onUpToDate(pkt);
                }).add();

        CHANNEL.messageBuilder(MapSnapshotHeaderPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MapSnapshotHeaderPacket::write).decoder(MapSnapshotHeaderPacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onSnapshotHeader(pkt);
                }).add();

        CHANNEL.messageBuilder(MapSnapshotPartPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MapSnapshotPartPacket::write).decoder(MapSnapshotPartPacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onSnapshotPart(pkt);
                }).add();

        CHANNEL.messageBuilder(MapDeltaPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(MapDeltaPacket::write).decoder(MapDeltaPacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onDelta(pkt);
                }).add();

        CHANNEL.messageBuilder(TileRequestPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TileRequestPacket::write).decoder(TileRequestPacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ServerPlayer sp = ctx.get().getSender();
                    if (sp != null && sp.server != null) {
                        PoliticalMapServer.get(sp.server).handleTileRequest(sp, pkt.mapId(), pkt.lod(), pkt.tx(), pkt.tz());
                    }
                }).add();

        CHANNEL.messageBuilder(TileResponsePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TileResponsePacket::write).decoder(TileResponsePacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onTileResponse(pkt);
                }).add();
    }
}
