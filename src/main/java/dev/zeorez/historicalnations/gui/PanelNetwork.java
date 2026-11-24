package dev.zeorez.historicalnations.gui;

import dev.zeorez.historicalnations.HistoricalNations;
import dev.zeorez.historicalnations.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import static net.minecraftforge.network.NetworkDirection.*;
import static dev.zeorez.historicalnations.gui.PanelQuery.View;

public final class PanelNetwork {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(HistoricalNations.ID,"politics"),()->"1",
            NetworkRegistry.acceptMissingOr("1"),NetworkRegistry.acceptMissingOr("1"));
    private static final Map<ServerPlayer, Budget> limits=new WeakHashMap<>();
    private static final class Budget { int start, used, lastAction; }
    private static boolean permit(ServerPlayer p) {
        var b=limits.computeIfAbsent(p,k->new Budget());
        int now=p.server.getTickCount();
        if (now-b.start>=20) { b.start=now; b.used=0; }
        return ++b.used<=12;
    }
    public record Action(PanelQuery q, OfficeActions operation,String value) {
        void write(FriendlyByteBuf b) { q.write(b); b.writeEnum(operation); b.writeUtf(value,256); }
        static Action read(FriendlyByteBuf b) { return new Action(PanelQuery.read(b),b.readEnum(OfficeActions.class),b.readUtf(256)); }
    }
    public record Page(PanelQuery q,CompoundTag data,Component feedback,boolean success) {
        void write(FriendlyByteBuf b) { q.write(b); b.writeNbt(data); b.writeComponent(feedback); b.writeBoolean(success); }
        static Page read(FriendlyByteBuf b) { return new Page(PanelQuery.read(b),Objects.requireNonNull(b.readNbt()),b.readComponent(),b.readBoolean()); }
    }
    public static void register() {
        CHANNEL.messageBuilder(PanelQuery.class,0,PLAY_TO_SERVER).encoder(PanelQuery::write).decoder(PanelQuery::read)
            .consumerMainThread((q,ctx)->handle(ctx.get().getSender(),q,null)).add();
        CHANNEL.messageBuilder(Action.class,1,PLAY_TO_SERVER).encoder(Action::write).decoder(Action::read)
            .consumerMainThread((a,ctx)->handle(ctx.get().getSender(),a.q,a)).add();
        CHANNEL.messageBuilder(Page.class,2,PLAY_TO_CLIENT).encoder(Page::write).decoder(Page::read)
            .consumerMainThread((p,ctx)->net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    ()->()->dev.zeorez.historicalnations.client.PoliticalScreen.receive(p))).add();
    }
    public static boolean open(ServerPlayer p, View view, UUID id) {
        if (p.server.getPlayerList().getPlayer(p.getUUID())!=p || !CHANNEL.isRemotePresent(p.connection.connection)) return false;
        handle(p,PanelQuery.open(view,id),null);
        return true;
    }
    private static void handle(ServerPlayer player,PanelQuery q,Action action) {
        if (player==null || player.hasDisconnected()) return;
        if (!permit(player)) { player.displayClientMessage(Component.translatable("hn.panel.slow"),true); return; }
        Component message=Component.empty(); boolean success=true; CompoundTag data;
        try {
            if (action!=null) {
                if (q.target()==null || (q.view()!=View.TOWN_OFFICE && q.view()!=View.NATION_OFFICE)) return;
                var budget=limits.get(player);
                if (q.serial()<=budget.lastAction) return;
                budget.lastAction=q.serial();
                var result=action.operation.perform(player,q.target(),q.view()==View.NATION_OFFICE,action.value);
                message=result.message(); success=result.success();
            }
            data=PoliticalPanel.page(player,q);
        } catch (RuleViolation error) {
            data=new CompoundTag(); message=Component.translatable("historical_nations."+error.getMessage()); success=false;
        }
        CHANNEL.sendTo(new Page(q,data,message,success),player.connection.connection,PLAY_TO_CLIENT);
    }
}
