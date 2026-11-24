package dev.zeorez.historicalnations.client;

import dev.zeorez.historicalnations.core.OfficeActions;
import dev.zeorez.historicalnations.gui.PanelNetwork;
import dev.zeorez.historicalnations.gui.PanelQuery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import static dev.zeorez.historicalnations.gui.PanelQuery.View;
import static dev.zeorez.historicalnations.gui.PanelQuery.Sort;

/** One navigation stack for the directories, dossiers and the two offices. */
public final class PoliticalScreen extends Screen {
    private static final int GOLD=0xffe5c579, CREAM=0xffede6d5, MUTED=0xffa7b2b2, BORDER=0xff71654c;
    private static final int STEP=18;
    private static final View[] NAV={View.TOWN,View.NATION,View.TOWNS,View.NATIONS,View.PLAYER};
    private static final String[] NAV_KEYS={"my_town","my_nation","town_directory","nations","player"};
    private static final ResourceLocation SEALS=ResourceLocation.fromNamespaceAndPath("historical_nations","textures/gui/offices.png");
    private static int sequence;
    private final Deque<PanelQuery> history=new ArrayDeque<>();
    private PanelQuery query;
    private CompoundTag data;
    private Component feedback=Component.empty();
    private boolean ok, focusSearch;
    private int expected, left, top, panelWidth, bottom, body, visible, offset, debounce;
    private int editCursor;
    private String search="", draft="";
    private EditBox editor;
    private OfficeActions editing;
    private Button submit;
    private PoliticalScreen() { super(tr("home")); }
    private static Component tr(String key) { return Component.translatable("hn.panel."+key); }
    private static Component label(String value) { return value.startsWith("@@") ? Component.literal(value.substring(1)) : value.startsWith("@") ? tr(value.substring(1)) : Component.literal(value); }
    public static void receive(PanelNetwork.Page page) {
        var mc=Minecraft.getInstance();
        if (mc.player==null) return;
        PoliticalScreen screen;
        if (page.q().serial()==0) screen=new PoliticalScreen();
        else if (mc.screen instanceof PoliticalScreen current && current.expected==page.q().serial()) screen=current;
        else return;
        if (screen.debounce>0 && page.q().view()==screen.query.view() && !screen.search.equals(page.q().search())) return;
        if (screen.editor!=null && screen.editing==null) {
            screen.focusSearch=screen.editor.isFocused(); screen.editCursor=screen.editor.getCursorPosition();
        }
        screen.query=page.q(); screen.data=page.data(); screen.search=page.q().search();
        screen.feedback=page.feedback(); screen.ok=page.success(); screen.editing=null; screen.offset=0;
        if(mc.screen!=screen) mc.setScreen(screen); else screen.rebuildWidgets();
    }
    private int pageSize() { return Math.max(1,Math.min(12,(bottom-top-108)/STEP)); }
    private PanelQuery request(View view,UUID id,Sort sort,boolean desc,String filter,int page) {
        return new PanelQuery(view,id,sort,desc,filter,page,pageSize(),++sequence);
    }
    private void send(PanelQuery q) {
        expected=q.serial(); debounce=0; PanelNetwork.CHANNEL.sendToServer(q);
    }
    private void navigate(View view,UUID target) {
        history.push(query); focusSearch=false;
        send(request(view,target,Sort.NAME,false,"",0));
    }
    private void previous() {
        if(editing!=null) { editing=null; rebuildWidgets(); return; }
        if(history.isEmpty()) { onClose(); return; }
        var p=history.pop();
        send(request(p.view(),p.target(),p.sort(),p.descending(),p.search(),p.page()));
    }
    private boolean directory() { return query.view()==View.TOWNS || query.view()==View.NATIONS || query.view()==View.MEMBER_TOWNS; }
    private boolean national() { return switch(query.view()) { case NATION,NATIONS,NATION_OFFICE,MEMBER_TOWNS -> true; default -> false; }; }
    private void turnPage(int delta) {
        int p=data.getInt("page")+delta;
        if(p>=0 && p<data.getInt("pages")) send(request(query.view(),query.target(),query.sort(),query.descending(),search,p));
    }
    private void order(Sort sort) {
        send(request(query.view(),query.target(),sort,query.sort()==sort?!query.descending():sort!=Sort.NAME,search,0));
    }
    private class Tab extends Button {
        final boolean selected; final int icon;
        Tab(int x,int y,int w,Component text,boolean selected,int icon,Runnable click) {
            super(x,y,w,16,text,b->click.run(),DEFAULT_NARRATION); this.selected=selected; this.icon=icon;
            setTooltip(Tooltip.create(text));
        }
        @Override protected void renderWidget(GuiGraphics g,int mx,int my,float tick) {
            int x=getX(),y=getY();
            g.fill(x,y,x+width,y+height,!active?0xff242a2b:isHoveredOrFocused()?0xff465350:selected?0xff3d443c:0xff2f3739);
            g.fill(x,y+height-1,x+width,y+height,selected?GOLD:BORDER);
            int start=x+4;
            if(icon>=0) { g.blit(SEALS,start,y+3,(float)(icon*10),0,10,10,50,10); start+=13; }
            String text=font.plainSubstrByWidth(getMessage().getString(),Math.max(0,x+width-start-2));
            g.drawString(font,text,start,y+4,!active?MUTED:selected?GOLD:CREAM,false);
        }
    }
    private void homeCards(int x,int w) {
        int y=top+32, gap=5;
        int h=Math.min(66,(bottom-27-y-2*gap)/3);
        for(int i=0;i<NAV.length;i++) {
            final int icon=i;
            boolean wide=i==4;
            int tile=wide?w:(w-gap)/2;
            int a=x+(wide?0:i%2*(tile+gap)), b=y+i/2*(h+gap);
            Component detail=switch(i) {
                case 0,1 -> label(rows().getCompound(i).getList("cells",Tag.TAG_STRING).getString(1));
                case 2,3 -> Component.translatable("hn.panel.records",data.getInt(i==2?"townCount":"nationCount"));
                default -> minecraft.player.getDisplayName();
            };
            var tileButton=new Button(a,b,tile,h,tr(NAV_KEYS[i]),btn->navigate(NAV[icon],null),supplier->supplier.get()) {
                @Override protected void renderWidget(GuiGraphics g,int mx,int my,float tick) {
                    int xx=getX(), yy=getY(), cy=yy+height/2;
                    g.fillGradient(xx,yy,xx+width,yy+height,isHoveredOrFocused()?0xff46524d:0xff343e3e,0xff252d2f);
                    g.renderOutline(xx,yy,width,height,isHoveredOrFocused()?GOLD:BORDER);
                    g.fill(xx+1,yy+2,xx+3,yy+height-2,0xffa99566);
                    g.blit(SEALS,xx+8,cy-5,(float)(icon*10),0,10,10,50,10);
                    g.drawString(font,trim(getMessage().getString(),width-32),xx+24,cy-10,GOLD,false);
                    g.drawString(font,trim(detail.getString(),width-32),xx+24,cy+3,CREAM,false);
                }
            };
            addRenderableWidget(tileButton);
        }
    }
    private Tab tab(int x,int y,int w,Component text,boolean active,Runnable click) {
        return addRenderableWidget(new Tab(x,y,w,text,active,-1,click));
    }
    @Override protected void init() {
        panelWidth=Math.min(820,width-12); left=(width-panelWidth)/2;
        int h=Math.min(400,height-10); top=(height-h)/2; bottom=top+h;
        editor=null; submit=null;
        int x=left+7, w=panelWidth-14;
        tab(left+panelWidth-46,top+5,39,tr("back"),false,this::previous);
        if(query.view()==View.HOME) { homeCards(x,w); return; }
        for(int i=0;i<NAV.length;i++) {
            View v=NAV[i]; int a=x+w*i/5,b=x+w*(i+1)/5;
            addRenderableWidget(new Tab(a,top+25,b-a-1,tr(NAV_KEYS[i]),query.view()==v,i,()->navigate(v,null)));
        }
        int y=top+50;
        if(editing!=null) { form(x,y,w); return; }
        UUID id=data.hasUUID("subject")?data.getUUID("subject"):null;
        if(id!=null && (query.view()!=View.MEMBER && query.view()!=View.PLAYER)) {
            boolean nation=national();
            List<View> views=new ArrayList<>(List.of(nation?View.NATION:View.TOWN,nation?View.MEMBER_TOWNS:View.RESIDENTS));
            if(data.getBoolean("office")) views.add(nation?View.NATION_OFFICE:View.TOWN_OFFICE);
            for(int i=0;i<views.size();i++) {
                View v=views.get(i); String key=i==0?"overview":i==2?"management":nation?"member_towns":"residents";
                tab(x+w*i/views.size(),y,w/views.size()-1,tr(key),query.view()==v,()->navigate(v,id));
            }
            y+=20;
        } else if(query.view()==View.MEMBER && data.hasUUID("parent")) {
            tab(x,y,w,tr("residents"),false,()->navigate(View.RESIDENTS,data.getUUID("parent"))); y+=20;
        }
        if(directory()) {
            editor=addRenderableWidget(new EditBox(font,x+1,y+1,w-2,15,tr("search")));
            editor.setMaxLength(32); editor.setHint(tr("search")); editor.setValue(search);
            if(focusSearch) { setInitialFocus(editor); editor.setCursorPosition(Math.min(search.length(),editCursor)); }
            editor.setResponder(s->{search=s; debounce=5;});
            y+=20;
        }
        if(directory() || query.view()==View.RESIDENTS) { headers(x,y,w); y+=18; }
        body=y+2; visible=Math.max(1,(bottom-(feedback.getString().isEmpty()?27:42)-body)/STEP);
        int page=data.getInt("page"), pages=data.getInt("pages");
        if(pages>1) {
            tab(left+panelWidth-82,bottom-21,35,Component.literal("◀"),false,()->turnPage(-1)).active=page>0;
            tab(left+panelWidth-44,bottom-21,35,Component.literal("▶"),false,()->turnPage(1)).active=page+1<pages;
        }
        int extra=rows().size()-visible;
        if(extra>0) {
            tab(x,bottom-21,27,Component.literal("▲"),false,()->scrollRows(-1));
            tab(x+30,bottom-21,27,Component.literal("▼"),false,()->scrollRows(1));
        }
    }
    private void form(int x,int y,int w) {
        y+=34;
        if(!editing.input.isEmpty()) {
            editor=addRenderableWidget(new EditBox(font,x+2,y,w-4,18,tr(editing.input)));
            editor.setMaxLength(256); editor.setValue(draft); editor.setHint(tr(editing.input)); setInitialFocus(editor);
            editor.setResponder(s->{ draft=s; submit.active=!s.isBlank(); }); y+=26;
        }
        submit=tab(x,y,w/2-2,tr("apply"),true,this::submit);
        submit.active=editing.input.isEmpty() || !draft.isBlank();
        tab(x+w/2,y,w/2,tr("cancel"),false,()->{editing=null;rebuildWidgets();});
    }
    private void submit() {
        var action=editing;
        boolean resident=query.view()==View.MEMBER;
        var q=request(resident?View.TOWN_OFFICE:query.view(),data.getUUID(resident?"parent":"subject"),Sort.NAME,false,"",0);
        Runnable dispatch=()->{
            editing=null; expected=q.serial(); rebuildWidgets();
            PanelNetwork.CHANNEL.sendToServer(new PanelNetwork.Action(q,action,draft));
        };
        if(action.confirm) minecraft.setScreen(new ConfirmScreen(answer->{
            minecraft.setScreen(this);
            if(answer) dispatch.run();
        },tr("confirm"),Component.translatable("hn.panel.confirm_action",tr("act_"+action.name().toLowerCase(Locale.ROOT)),
                data.getString("title"),draft)));
        else dispatch.run();
    }
    private String[] columns() {
        return switch(query.view()) {
            case NATIONS -> new String[]{"name","capital","leader","people","towns","land","assets"};
            case TOWNS -> new String[]{"name","nation","mayor","people","land"};
            case MEMBER_TOWNS -> new String[]{"name","status","mayor","people","land"};
            default -> new String[]{"name","town_rank","status"};
        };
    }
    private int[] weights() {
        return switch(query.view()) {
            case NATIONS -> new int[]{23,17,17,11,9,10,13}; case TOWNS,MEMBER_TOWNS -> new int[]{26,24,22,15,13};
            case RESIDENTS -> new int[]{42,33,25}; default -> new int[]{39,61};
        };
    }
    private void headers(int x,int y,int w) {
        String[] cols=columns(); int[] weights=weights(); int used=0;
        for(int i=0;i<cols.length;i++) {
            int start=x+used*w/100; used+=weights[i]; int cw=x+used*w/100-start-1;
            Sort s=switch(cols[i]) {case "name"->Sort.NAME;case "people"->Sort.PEOPLE;case "towns"->Sort.TOWNS;
                case "land"->Sort.LAND;case "assets"->Sort.TREASURY;default->null;};
            boolean sortable=s!=null && directory(), selected=sortable && query.sort()==s;
            var text=tr(cols[i]).copy();
            if(selected) text=Component.literal(query.descending()?"▼ ":"▲ ").append(text);
            var btn=tab(start,y,cw,text,selected,()->order(s)); btn.active=sortable;
            if(sortable) btn.setTooltip(Tooltip.create(Component.translatable("hn.panel.sort",tr(cols[i]))));
        }
    }
    private ListTag rows() { return data.getList("rows",Tag.TAG_COMPOUND); }
    private String trim(String s,int width) {
        if(font.width(s)<=width) return s;
        return font.plainSubstrByWidth(s,Math.max(0,width-font.width("…")))+"…";
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        renderBackground(g);
        g.fill(left,top,left+panelWidth,bottom,0xff1b2224);
        g.renderOutline(left,top,panelWidth,bottom-top,BORDER);
        g.fill(left+1,top+1,left+panelWidth-1,top+3,GOLD);
        g.fillGradient(left+2,top+3,left+panelWidth-2,top+24,0xff393b31,0xff272e2e);
        int seal = switch(query.view()) {
            case NATION,NATION_OFFICE,MEMBER_TOWNS -> 1; case TOWNS -> 2; case NATIONS -> 3;
            case MEMBER,PLAYER,RESIDENTS -> 4; default -> 0;
        };
        g.renderOutline(left+7,top+5,17,16,BORDER);
        g.blit(SEALS,left+11,top+8,(float)(seal*10),0,10,10,50,10);
        g.drawString(font,trim(label(data.getString("title")).getString(),panelWidth-82),left+30,top+9,GOLD,false);
        g.fill(left+5,bottom-24,left+panelWidth-5,bottom-23,0xff4a5350);
        if(editing==null && data.contains("total")) {
            int start=left+(rows().size()>visible?70:10);
            g.drawString(font,Component.translatable("hn.panel.records",data.getInt("total")),start,bottom-15,MUTED,false);
        }
        if(editing!=null) {
            g.drawString(font,tr("act_"+editing.name().toLowerCase(Locale.ROOT)),left+9,top+52,GOLD,false);
            if(!editing.input.isEmpty()) g.drawString(font,tr(editing.input),left+9,top+66,MUTED,false);
        } else {
            if(query.view()!=View.HOME) drawRows(g,mx,my);
            int pages=data.getInt("pages");
            if(pages>1) g.drawCenteredString(font,(data.getInt("page")+1)+" / "+pages,width/2,bottom-16,MUTED);
        }
        if(!feedback.getString().isEmpty()) {
            String msg=trim(feedback.getString(),panelWidth-24);
            g.fill(left+5,bottom-39,left+panelWidth-5,bottom-25,0xff253234);
            g.drawString(font,msg,left+9,bottom-36,ok?0xff95d8ad:0xffffa697,false);
            if(my>=bottom-39 && my<bottom-25) setTooltipForNextRenderPass(font.split(feedback,panelWidth-20));
        }
        super.render(g,mx,my,partial);
    }
    private void drawRows(GuiGraphics g,int mx,int my) {
        var rows=rows(); int x=left+7,w=panelWidth-14; int[] widths=weights();
        if(rows.isEmpty()) g.drawCenteredString(font,tr("empty"),width/2,body+5,MUTED);
        for(int slot=0;slot<visible && offset+slot<rows.size();slot++) {
            int i=offset+slot,y=body+slot*STEP; var r=rows.getCompound(i);
            boolean hover=mx>=x && mx<x+w && my>=y && my<y+STEP-1;
            g.fill(x,y,x+w,y+STEP-1,hover?0xff3d4a4b:i%2==0?0xff2a3335:0xff252d30);
            int rank=data.getInt("page")*query.size()+i;
            int accent=directory() && query.sort()!=Sort.NAME && rank<3 ? new int[]{GOLD,0xffbbc4c8,0xffc49270}[rank]:0xff506568;
            g.fill(x,y,x+2,y+STEP-1,accent);
            var cells=r.getList("cells",Tag.TAG_STRING); int used=0;
            for(int j=0;j<cells.size();j++) {
                int at=x+w*used/100,end;
                if(cells.size()==1) end=x+w; else { used+=widths[j]; end=x+w*used/100; }
                int start=at+4;
                if(j==0 && r.hasUUID("head")) {
                    var id=r.getUUID("head"); var info=minecraft.getConnection().getPlayerInfo(id);
                    PlayerFaceRenderer.draw(g,info==null?DefaultPlayerSkin.getDefaultSkin(id):info.getSkinLocation(),start,y+2,13);
                    start+=17;
                }
                String raw=cells.getString(j),value=label(raw).getString();
                int color=raw.equals("@online")?0xff91d2ad:raw.equals("@offline")?MUTED:j==0?CREAM:0xffc7d3d4;
                g.drawString(font,trim(value,end-start-2),start,y+5,color,false);
                if(hover && mx>=at && mx<end && font.width(value)>end-start-2)
                    setTooltipForNextRenderPass(font.split(label(raw),Math.max(100,panelWidth-28)));
            }
        }
    }
    @Override public boolean mouseClicked(double x,double y,int button) {
        if(super.mouseClicked(x,y,button)) return true;
        if(query.view()==View.HOME || editing!=null || button!=0 || x<left+7 || x>=left+panelWidth-7 || y<body || y>=body+visible*STEP) return false;
        int i=offset+(int)((y-body)/STEP);
        if(i>=rows().size()) return false;
        var r=rows().getCompound(i);
        if(r.hasUUID("id")) navigate(View.valueOf(r.getString("view")),r.getUUID("id"));
        else if(r.contains("action")) {
            editing=OfficeActions.valueOf(r.getString("action")); draft=query.view()==View.MEMBER?query.target().toString():""; feedback=Component.empty();rebuildWidgets();
        }
        return true;
    }
    private void scrollRows(int step) { offset=Math.max(0,Math.min(Math.max(0,rows().size()-visible),offset+step)); }
    @Override public boolean mouseScrolled(double x,double y,double delta) {
        if(query.view()==View.HOME || editing!=null || delta==0) return false;
        int step=delta>0?-1:1, old=offset; scrollRows(step);
        if(old==offset) turnPage(step);
        return true;
    }
    @Override public void tick() {
        if(editor!=null) editor.tick();
        if(debounce>0 && --debounce==0) send(request(query.view(),query.target(),query.sort(),query.descending(),search,0));
    }
    @Override public boolean isPauseScreen() { return false; }
}
