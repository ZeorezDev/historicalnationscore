package dev.zeorez.hntests;

import dev.zeorez.historicalnations.client.PoliticalScreen;
import dev.zeorez.historicalnations.core.OfficeActions;
import dev.zeorez.historicalnations.gui.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import static dev.zeorez.historicalnations.gui.PanelQuery.*;
import static dev.zeorez.hntests.PrototypeServerTest.check;

/** Exercises the actual Screen widgets, vanilla command connection and both custom packet directions. */
@Mod.EventBusSubscriber(modid="hn_integration_tests",value=Dist.CLIENT)
public final class PanelClientTest {
    private static int step, ticks;
    @SubscribeEvent public static void message(ClientChatReceivedEvent.System e) {
        if(!Boolean.getBoolean("hn.test.panel")) return;
        var c=Minecraft.getInstance().getConnection();
        switch(e.getMessage().getString()) {
            case "HN_PANEL_START" -> { step=1; c.sendCommand("n list"); }
            case "HN_PANEL_READ" -> { step=17; c.sendCommand("hnc"); }
        }
    }
    private static Object read(PoliticalScreen s,String field) throws Exception {
        var f=PoliticalScreen.class.getDeclaredField(field); f.setAccessible(true); return f.get(s);
    }
    private static void button(PoliticalScreen s,String key) {
        String label=Component.translatable("hn.panel."+key).getString();
        var b=s.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(w->w.getMessage().getString().endsWith(label) && w.active).findFirst().orElseThrow();
        b.onPress();
    }
    private static EditBox editor(PoliticalScreen s) {
        return s.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }
    private static void row(PoliticalScreen s,int i) throws Exception {
        check(s.mouseClicked((int)read(s,"left")+13,(int)read(s,"body")+18*i+6,0),"click row "+i);
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if(step==0 || event.phase!=TickEvent.Phase.END || ++ticks%10!=0) return;
        var mc=Minecraft.getInstance();
        if(!(mc.screen instanceof PoliticalScreen s)) return;
        var q=(PanelQuery)read(s,"query"); var d=(CompoundTag)read(s,"data");
        switch(step) {
            case 1 -> {
                if(q.view()!=View.NATIONS)return;
                check(d.getInt("total")==9 && d.getInt("pages")>1,"initial directory is paged");
                mc.getWindow().setWindowed(1920,1080);
                mc.options.guiScale().set(3); mc.resizeDisplay(); mc.getToasts().clear();
                step=30; return;
            }
            case 30 -> {
                if(mc.getWindow().getGuiScale()!=3) return;
                button(s,"land"); step=31; return;
            }
            case 31 -> { if(q.sort()!=Sort.LAND)return;
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,"hn-v8-large.png",mc.getMainRenderTarget(),c->{});
                mc.getWindow().setWindowed(960,540); mc.options.guiScale().set(2); mc.resizeDisplay();
                step=32; return;
            }
            case 32 -> { if(mc.getWindow().getGuiScale()!=2)return;
                editor(s).setValue("uni"); step=2; return;
            }
            case 2 -> { if(!q.search().equals("uni"))return; button(s,"people"); }
            case 3 -> { if(q.sort()!=Sort.PEOPLE || !q.descending())return;
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,"hn-v8-directory.png",mc.getMainRenderTarget(),c->{});
                row(s,0); }
            case 4 -> { if(q.view()!=View.NATION)return; check(d.getString("title").equals("Union"),"nation details");
                button(s,"management"); step=40; return; }
            case 40 -> {
                if(q.view()!=View.NATION_OFFICE)return;
                var rows=d.getList("rows",10);
                int i=0; while(i<rows.size() && !rows.getCompound(i).getString("action").equals("DISBAND"))i++;
                check(i<rows.size(),"leader disband"); row(s,i); button(s,"apply");
                check(mc.screen instanceof net.minecraft.client.gui.screens.ConfirmScreen,"disband requires confirmation");
                mc.screen.keyPressed(256,0,0);
                check(mc.screen==s,"cancel returns to panel"); button(s,"cancel"); button(s,"member_towns");
                step=5; return;
            }
            case 5 -> { if(q.view()!=View.MEMBER_TOWNS)return; row(s,0); }
            case 6 -> { if(q.view()!=View.TOWN)return; check(d.getString("title").equals("Bursa"),"town details"); button(s,"management"); }
            case 7 -> {
                if(q.view()!=View.TOWN_OFFICE)return;
                var rows=d.getList("rows",10);
                int i=0; while(i<rows.size() && !rows.getCompound(i).getString("action").equals("DESCRIPTION")) i++;
                check(i<rows.size(),"mayor actions"); row(s,i);
                editor(s).setValue("Council verified"); button(s,"apply");
            }
            case 8 -> {
                if(!((Component)read(s,"feedback")).getString().isEmpty()) {
                    check((boolean)read(s,"ok"),"action feedback");
                    mc.getConnection().sendCommand("hnpanelack"); step=0;
                }
                return;
            }
            case 17 -> { if(q.view()!=View.HOME)return; button(s,"player"); step=50; return; }
            case 50 -> { if(q.view()!=View.PLAYER)return; check(d.toString().contains("Bursa"),"player citizenship");
                mc.getConnection().sendCommand("t list"); step=18; return; }
            case 18 -> { if(q.view()!=View.TOWNS)return; mc.getConnection().sendCommand("n info"); }
            case 19 -> { if(q.view()!=View.NATION)return; mc.getConnection().sendCommand("t info Bursa"); }
            case 20 -> { if(q.view()!=View.TOWN)return; check(d.toString().contains("Council verified"),"second client sees edit");
                button(s,"residents"); step=51; return; }
            case 51 -> { if(q.view()!=View.RESIDENTS)return;
                check(d.getList("rows",10).getCompound(0).hasUUID("head"),"resident head");
                row(s,0); step=52; return; }
            case 52 -> { if(q.view()!=View.MEMBER)return; check(d.toString().contains("@online"),"resident detail");
                button(s,"my_town"); step=53; return; }
            case 53 -> { if(q.view()!=View.TOWN)return; button(s,"management"); step=21; return; }
            case 21 -> {
                if(q.view()!=View.TOWN_OFFICE)return;
                check(!d.toString().contains("DESCRIPTION"),"member controls");
                PanelNetwork.CHANNEL.sendToServer(new PanelNetwork.Action(q,OfficeActions.DESCRIPTION,"Unauthorized"));
            }
            case 22 -> { if(((Component)read(s,"feedback")).getString().isEmpty())return;
                check(!(boolean)read(s,"ok"),"unauthorized packet denied"); mc.getConnection().sendCommand("hnpanelack"); step=0; return; }
        }
        step++;
    }
}
