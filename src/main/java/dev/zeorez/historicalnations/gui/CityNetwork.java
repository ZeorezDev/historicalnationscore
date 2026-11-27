package dev.zeorez.historicalnations.gui;

import dev.zeorez.historicalnations.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Objects;
import java.util.UUID;
import static net.minecraftforge.network.NetworkDirection.*;

public final class CityNetwork {
    public enum Sort { NAME, RECENT, SCORE }
    public record Query(int serial, UUID town, int page, int size, String name, String nation, int level,
                        int stars, Sort sort, int history) {
        public static Query open(UUID town) { return new Query(0,town,0,3,"","",0,-1,Sort.NAME,0); }
        void write(FriendlyByteBuf b) {
            b.writeInt(serial); b.writeNullable(town,FriendlyByteBuf::writeUUID); b.writeInt(page); b.writeByte(size);
            b.writeUtf(name,32); b.writeUtf(nation,32); b.writeByte(level); b.writeByte(stars); b.writeEnum(sort); b.writeInt(history);
        }
        static Query read(FriendlyByteBuf b) {
            return new Query(b.readInt(), b.readNullable(FriendlyByteBuf::readUUID), b.readInt(), b.readByte(),
                    b.readUtf(32), b.readUtf(32), b.readByte(), b.readByte(), b.readEnum(Sort.class), b.readInt());
        }
    }
    public record Save(int serial, UUID town, int revision, CityEvaluation.Draft draft) {
        void write(FriendlyByteBuf b) {
            b.writeInt(serial); b.writeUUID(town); b.writeInt(revision);
            for (var category : CityEvaluation.Category.values()) b.writeInt(draft.score(category));
            b.writeInt(draft.level()); b.writeInt(draft.rating());
            b.writeUtf(draft.reason(),256); b.writeUtf(draft.notes(),256); b.writeUtf(draft.summary(),256);
        }
        static Save read(FriendlyByteBuf b) {
            return new Save(b.readInt(),b.readUUID(),b.readInt(),new CityEvaluation.Draft(
                    b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),
                    b.readUtf(256),b.readUtf(256),b.readUtf(256)));
        }
    }
    public record Page(int serial, CompoundTag data, String feedback) {}
    static void register(SimpleChannel channel) {
        channel.messageBuilder(Query.class,4,PLAY_TO_SERVER).encoder(Query::write).decoder(Query::read)
                .consumerMainThread((q,c)->handle(c.get().getSender(),q,null)).add();
        channel.messageBuilder(Save.class,5,PLAY_TO_SERVER).encoder(Save::write).decoder(Save::read)
                .consumerMainThread((s,c)->handle(c.get().getSender(),Query.open(s.town()),s)).add();
        channel.messageBuilder(Page.class,6,PLAY_TO_CLIENT)
                .encoder((p,b)->{ b.writeInt(p.serial()); b.writeNbt(p.data()); b.writeUtf(p.feedback(),64); })
                .decoder(b->new Page(b.readInt(),Objects.requireNonNull(b.readNbt()),b.readUtf(64)))
                .consumerMainThread((p,c)->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        ()->()->dev.zeorez.historicalnations.client.CityEvaluationScreen.receive(p))).add();
    }
    public static boolean open(ServerPlayer player) {
        if (!PanelNetwork.CHANNEL.isRemotePresent(player.connection.connection)) return false;
        handle(player, Query.open(null),null);
        return true;
    }
    private static void handle(ServerPlayer p, Query q, Save save) {
        if (p == null || p.hasDisconnected()) return;
        if (!p.hasPermissions(4)) {
            p.displayClientMessage(Component.translatable("historical_nations.city_permission"),true);
            return;
        }
        int serial = save == null ? q.serial() : save.serial();
        CompoundTag data = new CompoundTag();
        String feedback = "";
        try {
            if (!PanelNetwork.permit(p)) throw new RuleViolation("city_slow");
            if (save != null) WorldRegistry.get(p.server).evaluate(p, save.town(), save.revision(), save.draft());
            data = CityPanel.page(p, q);
            if (save != null) feedback = "city_saved";
        } catch (RuleViolation err) {
            feedback = err.getMessage();
        }
        PanelNetwork.CHANNEL.sendTo(new Page(serial,data,feedback),p.connection.connection,PLAY_TO_CLIENT);
    }
}
