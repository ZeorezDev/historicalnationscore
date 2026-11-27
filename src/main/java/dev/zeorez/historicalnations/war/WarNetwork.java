package dev.zeorez.historicalnations.war;

import dev.zeorez.historicalnations.HistoricalNations;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.Nation;
import dev.zeorez.historicalnations.api.Town;
import dev.zeorez.historicalnations.core.RuleViolation;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.war.WarModels.OccupiedChunk;
import dev.zeorez.historicalnations.war.WarModels.WarRecord;
import dev.zeorez.historicalnations.war.WarModels.WarSide;
import dev.zeorez.historicalnations.war.WarModels.WarState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dedicated Forge network channel for Peace Treaty negotiation & ratification.
 * Strictly zero client-only class imports so Dedicated Servers never load Dist.CLIENT classes.
 * Zero chunk-capture packets exist on this channel; all captures occur exclusively via server-side flag timers.
 */
public final class WarNetwork {
    public static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(HistoricalNations.ID, "war"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL)
    );

    public enum PeaceOp {
        OPEN_OR_REFRESH,
        SET_CHUNK_ANNEX,
        RELEASE_ALL,
        ANNEX_ALL,
        SET_TRUCE_HOURS,
        SET_APPROVAL
    }

    public interface ClientHandler {
        void onPeaceState(PeaceStatePacket pkt);
    }

    private static volatile ClientHandler clientHandler;

    private WarNetwork() {}

    public static void setClientHandler(ClientHandler handler) {
        clientHandler = handler;
    }

    public static boolean canSend(ServerPlayer player) {
        return player != null
                && player.connection != null
                && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                && CHANNEL.isRemotePresent(player.connection.connection);
    }

    public static boolean openPeaceScreen(ServerPlayer player, UUID warId) {
        if (!canSend(player)) return false;
        sendPeaceState(player, warId, Component.empty(), true);
        return true;
    }

    public record TownOption(UUID townId, String townName) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(townId);
            b.writeUtf(townName, 64);
        }

        static TownOption read(FriendlyByteBuf b) {
            return new TownOption(b.readUUID(), b.readUtf(64));
        }
    }

    public record TreatyChunkEntry(
            ResourceLocation dimension,
            int chunkX,
            int chunkZ,
            String originalTownName,
            String originalNationName,
            UUID occupyingNationId,
            String occupyingNationName,
            UUID recipientTownId,
            String recipientTownName,
            List<TownOption> candidateTowns
    ) {
        void write(FriendlyByteBuf b) {
            b.writeResourceLocation(dimension);
            b.writeInt(chunkX);
            b.writeInt(chunkZ);
            b.writeUtf(originalTownName, 64);
            b.writeUtf(originalNationName, 64);
            b.writeUUID(occupyingNationId);
            b.writeUtf(occupyingNationName, 64);
            b.writeNullable(recipientTownId, FriendlyByteBuf::writeUUID);
            b.writeUtf(recipientTownName, 64);
            b.writeVarInt(candidateTowns.size());
            for (TownOption opt : candidateTowns) opt.write(b);
        }

        static TreatyChunkEntry read(FriendlyByteBuf b) {
            ResourceLocation dim = b.readResourceLocation();
            int cx = b.readInt();
            int cz = b.readInt();
            String origTown = b.readUtf(64);
            String origNat = b.readUtf(64);
            UUID occNatId = b.readUUID();
            String occNatName = b.readUtf(64);
            UUID recId = b.readNullable(FriendlyByteBuf::readUUID);
            String recName = b.readUtf(64);
            int count = Math.min(256, b.readVarInt());
            List<TownOption> opts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) opts.add(TownOption.read(b));
            return new TreatyChunkEntry(dim, cx, cz, origTown, origNat, occNatId, occNatName, recId, recName, opts);
        }
    }

    public record PeaceActionPacket(
            UUID warId,
            long expectedVersion,
            PeaceOp op,
            ResourceLocation dimension,
            int chunkX,
            int chunkZ,
            UUID recipientTownId,
            int intArg
    ) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(warId);
            b.writeLong(expectedVersion);
            b.writeEnum(op);
            b.writeNullable(dimension, FriendlyByteBuf::writeResourceLocation);
            b.writeInt(chunkX);
            b.writeInt(chunkZ);
            b.writeNullable(recipientTownId, FriendlyByteBuf::writeUUID);
            b.writeInt(intArg);
        }

        static PeaceActionPacket read(FriendlyByteBuf b) {
            return new PeaceActionPacket(
                    b.readUUID(),
                    b.readLong(),
                    b.readEnum(PeaceOp.class),
                    b.readNullable(FriendlyByteBuf::readResourceLocation),
                    b.readInt(),
                    b.readInt(),
                    b.readNullable(FriendlyByteBuf::readUUID),
                    b.readInt()
            );
        }
    }

    public record PeaceStatePacket(
            UUID warId,
            String warTitle,
            WarState warState,
            long version,
            boolean canNegotiate,
            WarSide viewerSide,
            String attackerName,
            String defenderName,
            boolean attackerApproved,
            boolean defenderApproved,
            int effectiveTruceHours,
            List<TreatyChunkEntry> occupiedEntries,
            Component feedback,
            boolean ok
    ) {
        void write(FriendlyByteBuf b) {
            b.writeUUID(warId);
            b.writeUtf(warTitle, 128);
            b.writeEnum(warState);
            b.writeLong(version);
            b.writeBoolean(canNegotiate);
            b.writeNullable(viewerSide, FriendlyByteBuf::writeEnum);
            b.writeUtf(attackerName, 64);
            b.writeUtf(defenderName, 64);
            b.writeBoolean(attackerApproved);
            b.writeBoolean(defenderApproved);
            b.writeVarInt(effectiveTruceHours);
            b.writeVarInt(occupiedEntries.size());
            for (TreatyChunkEntry e : occupiedEntries) e.write(b);
            b.writeComponent(feedback);
            b.writeBoolean(ok);
        }

        static PeaceStatePacket read(FriendlyByteBuf b) {
            UUID wid = b.readUUID();
            String title = b.readUtf(128);
            WarState st = b.readEnum(WarState.class);
            long ver = b.readLong();
            boolean canNeg = b.readBoolean();
            WarSide side = b.readNullable(buf -> buf.readEnum(WarSide.class));
            String att = b.readUtf(64);
            String def = b.readUtf(64);
            boolean attApp = b.readBoolean();
            boolean defApp = b.readBoolean();
            int truce = b.readVarInt();
            int count = Math.min(4096, b.readVarInt());
            List<TreatyChunkEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) entries.add(TreatyChunkEntry.read(b));
            Component fb = b.readComponent();
            boolean ok = b.readBoolean();
            return new PeaceStatePacket(wid, title, st, ver, canNeg, side, att, def, attApp, defApp, truce, entries, fb, ok);
        }
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(PeaceActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PeaceActionPacket::write).decoder(PeaceActionPacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ServerPlayer sp = ctx.get().getSender();
                    if (sp != null && sp.server != null) {
                        handlePeaceAction(sp, pkt);
                    }
                }).add();

        CHANNEL.messageBuilder(PeaceStatePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PeaceStatePacket::write).decoder(PeaceStatePacket::read)
                .consumerMainThread((pkt, ctx) -> {
                    ClientHandler h = clientHandler;
                    if (h != null) h.onPeaceState(pkt);
                }).add();
    }

    private static void handlePeaceAction(ServerPlayer player, PeaceActionPacket pkt) {
        if (player.hasDisconnected()) return;
        Component feedback = Component.empty();
        boolean ok = true;
        try {
            switch (pkt.op()) {
                case OPEN_OR_REFRESH -> {}
                case SET_CHUNK_ANNEX -> {
                    if (pkt.dimension() == null) throw new RuleViolation("invalid_text");
                    Location loc = new Location(pkt.dimension(), pkt.chunkX(), pkt.chunkZ());
                    WarService.setTreatyChunkTerm(player, pkt.warId(), loc, pkt.recipientTownId());
                    feedback = Component.translatable("historical_nations.updated");
                }
                case RELEASE_ALL -> {
                    WarService.setTreatyBulkMode(player, pkt.warId(), false);
                    feedback = Component.translatable("historical_nations.updated");
                }
                case ANNEX_ALL -> {
                    WarService.setTreatyBulkMode(player, pkt.warId(), true);
                    feedback = Component.translatable("historical_nations.updated");
                }
                case SET_TRUCE_HOURS -> {
                    WarService.setTreatyTruceHours(player, pkt.warId(), pkt.intArg());
                    feedback = Component.translatable("historical_nations.updated");
                }
                case SET_APPROVAL -> {
                    boolean concluded = WarService.approvePeaceTreaty(player, pkt.warId(), pkt.expectedVersion(), pkt.intArg() == 1);
                    feedback = Component.translatable(concluded
                            ? "historical_nations.war_treaty_ratified"
                            : "historical_nations.updated");
                }
            }
        } catch (RuleViolation rv) {
            feedback = Component.translatable("historical_nations." + rv.getMessage());
            ok = false;
        }
        sendPeaceState(player, pkt.warId(), feedback, ok);
    }

    public static void sendPeaceState(ServerPlayer player, UUID warId, Component feedback, boolean ok) {
        if (!canSend(player)) return;
        WorldRegistry data = WorldRegistry.get(player.server);
        WarRecord war = data.wars.warById(warId).orElse(null);
        if (war == null) return;

        String attName = data.getNationById(war.primaryAttackerId).map(Nation::name).orElse("?");
        String defName = war.independentDefenderTown
                ? data.getTownById(war.primaryDefenderId).map(Town::name).orElse("?")
                : data.getNationById(war.primaryDefenderId).map(Nation::name).orElse("?");

        WarSide viewerSide = null;
        boolean canNegotiate = false;
        if (war.isOngoing()) {
            if (war.independentDefenderTown) {
                Town dt = data.getTownById(war.primaryDefenderId).orElse(null);
                if (dt != null && dt.mayor().equals(player.getUUID())) {
                    viewerSide = WarSide.DEFENDER;
                    canNegotiate = true;
                }
            }
            Nation pNat = data.getPlayerNation(player.getUUID()).orElse(null);
            if (pNat != null) {
                viewerSide = war.sideOfNation(pNat.id());
                if (data.isNationLeader(player.getUUID()) && war.isPrimaryLeaderNation(pNat.id())) {
                    canNegotiate = true;
                }
            }
        }

        int effTruce = war.peaceDraft.truceHours >= 0
                ? war.peaceDraft.truceHours
                : TerritoryConfig.WAR_TRUCE_HOURS.get();

        List<TreatyChunkEntry> entries = new ArrayList<>(war.occupiedChunks.size());
        for (OccupiedChunk occ : war.occupiedChunks.values()) {
            String origTown = data.getTownById(occ.originalTownId()).map(Town::name).orElse("?");
            String origNat = occ.originalNationId() == null ? "-"
                    : data.getNationById(occ.originalNationId()).map(Nation::name).orElse("?");
            String occNat = data.getNationById(occ.occupyingNationId()).map(Nation::name).orElse("?");

            UUID selectedTownId = war.peaceDraft.annexations.get(occ.location());
            String selectedTownName = selectedTownId == null ? ""
                    : data.getTownById(selectedTownId).map(Town::name).orElse("");

            List<TownOption> candidates = new ArrayList<>();
            for (Town t : data.getNationTowns(occ.occupyingNationId())) {
                candidates.add(new TownOption(t.id(), t.name()));
            }
            entries.add(new TreatyChunkEntry(
                    occ.location().dimension(),
                    occ.location().x(),
                    occ.location().z(),
                    origTown,
                    origNat,
                    occ.occupyingNationId(),
                    occNat,
                    selectedTownId,
                    selectedTownName,
                    candidates
            ));
        }

        PeaceStatePacket pkt = new PeaceStatePacket(
                war.warId,
                WarService.warTitle(data, war),
                war.state,
                war.peaceDraft.version,
                canNegotiate,
                viewerSide,
                attName,
                defName,
                war.peaceDraft.attackerApproved,
                war.peaceDraft.defenderApproved,
                effTruce,
                entries,
                feedback,
                ok
        );
        CHANNEL.sendTo(pkt, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
