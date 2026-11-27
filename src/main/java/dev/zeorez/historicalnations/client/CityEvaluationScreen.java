package dev.zeorez.historicalnations.client;

import dev.zeorez.historicalnations.core.CityEvaluation;
import dev.zeorez.historicalnations.core.CityEvaluation.Category;
import dev.zeorez.historicalnations.gui.CityNetwork;
import dev.zeorez.historicalnations.gui.PanelNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public final class CityEvaluationScreen extends Screen {
    private static final int GOLD=0xffecc879, INK=0xff20292c, MUTED=0xffb7c8c5;
    private static int sequence;
    private static Screen pendingMap;
    private static int pendingSerial;
    private final Screen parent;
    private CompoundTag data = new CompoundTag();
    private UUID town;
    private int expected, page, filterLevel, filterStars=-1, level, rating, history, revision;
    private CityNetwork.Sort sort=CityNetwork.Sort.NAME;
    private String search="", nation="", reason="", notes="", summary="", feedback="";
    private boolean notesTab, followSuggestion;
    private final int[] scores=new int[5];
    private final ScoreSlider[] sliders=new ScoreSlider[5];
    private final EditBox[] numbers=new EditBox[5];
    private Button ratingButton, saveButton;
    private int x,y,w,bottom;

    private CityEvaluationScreen(Screen parent) { super(tr("city_panel")); this.parent=parent; }
    private static Component tr(String key,Object... args) { return Component.translatable("hn.panel."+key,args); }
    private static Component level(int n) { return tr("level_"+n); }
    private static Component stars(int n) { return n==0?tr("unrated"):Component.literal(CityEvaluation.stars(n)); }
    public static void openTown(UUID town,Screen map) {
        pendingMap=map; pendingSerial=++sequence;
        PanelNetwork.CHANNEL.sendToServer(new CityNetwork.Query(pendingSerial,town,0,3,"","",0,-1,CityNetwork.Sort.NAME,0));
    }
    public static void receive(CityNetwork.Page packet) {
        var mc=Minecraft.getInstance();
        if (mc.player==null) return;
        CityEvaluationScreen screen;
        if (packet.serial()==0) screen=new CityEvaluationScreen(mc.screen);
        else if (packet.serial()==pendingSerial && pendingMap!=null && mc.screen==pendingMap) {
            screen=new CityEvaluationScreen(pendingMap); pendingMap=null;
        } else if (mc.screen instanceof CityEvaluationScreen current && current.expected==packet.serial()) screen=current;
        else return;
        screen.feedback=packet.feedback();
        if (packet.data().isEmpty()) {
            if (mc.screen==screen) screen.rebuildWidgets();
            else mc.player.displayClientMessage(Component.translatable("historical_nations."+packet.feedback()),false);
            return;
        }
        screen.data=packet.data();
        screen.town=screen.data.hasUUID("town")?screen.data.getUUID("town"):null;
        if (screen.town==null) screen.page=screen.data.getInt("page");
        screen.history=screen.data.getInt("history"); screen.revision=screen.data.getInt("revision");
        if (screen.town!=null) screen.loadDraft();
        if (mc.screen==screen) screen.rebuildWidgets(); else mc.setScreen(screen);
    }
    private void loadDraft() {
        var d=data.getCompound("review");
        for (var c:Category.values()) scores[c.ordinal()]=d.getInt(c.name());
        level=d.isEmpty()?1:d.getInt("level");
        rating=d.isEmpty()?1:d.getInt("rating");
        followSuggestion=rating==suggested();
        reason=d.getString("reason"); notes=d.getString("notes"); summary=d.getString("summary");
    }
    private int total() { return Arrays.stream(scores).sum(); }
    private int suggested() { return CityEvaluation.suggested(total()); }
    private void request(UUID id,int number,int entry) {
        expected=++sequence;
        PanelNetwork.CHANNEL.sendToServer(new CityNetwork.Query(expected,id,Math.max(0,number),pageSize(),search,nation,
                filterLevel,filterStars,sort,entry));
    }
    private int pageSize() { return Math.max(1,Math.min(8,(bottom-y-117)/38)); }
    private Button button(int px,int py,int width,Component title,Runnable click) {
        return addRenderableWidget(Button.builder(title,b->click.run()).bounds(px,py,width,17).build());
    }
    private EditBox input(int px,int py,int width, String key,String value,int max,Consumer<String> setter) {
        var box=addRenderableWidget(new EditBox(font,px+1,py+1,width-2,15,tr(key)));
        box.setMaxLength(max); box.setValue(value); box.setHint(tr(key)); box.setResponder(setter);
        return box;
    }
    @Override protected void init() {
        w=Math.min(610,width-12); x=(width-w)/2; int h=Math.min(height-8,town==null?420:258);
        y=(height-h)/2; bottom=y+h;
        saveButton=null; ratingButton=null;
        button(x+w-53,y+5,47,tr("back"),()->{
            if(town==null || parent instanceof PoliticalMapScreen) onClose();
            else {notesTab=false;request(null,page,0);}
        });
        if(town==null) {
            directory();
            if(data.getInt("size")!=pageSize()) request(null,page,0);
        } else detail();
    }
    private void directory() {
        int inner=w-16, half=(inner-46)/2;
        input(x+8,y+29,half,"search_town",search,32,s->search=s);
        input(x+11+half,y+29,half,"search_nation",nation,32,s->nation=s);
        button(x+w-47,y+29,39,tr("find"),()->request(null,0,0));
        int third=inner/3;
        button(x+8,y+50,third-2,filterLevel==0?tr("all_levels"):level(filterLevel),()->{
            filterLevel=(filterLevel+1)%6;request(null,0,0);
        });
        button(x+8+third,y+50,third-2,filterStars<0?tr("all_ratings"):stars(filterStars),()->{
            filterStars=filterStars==5?-1:filterStars+1;request(null,0,0);
        });
        button(x+8+2*third,y+50,inner-2*third,tr("sort_"+sort.name().toLowerCase(Locale.ROOT)),()->{
            sort=CityNetwork.Sort.values()[(sort.ordinal()+1)%CityNetwork.Sort.values().length];request(null,0,0);
        });
        var rows=data.getList("rows",Tag.TAG_COMPOUND);
        for(int i=0;i<rows.size();i++) {
            var row=rows.getCompound(i);
            int py=y+77+i*38;
            if(py+35>bottom-30) break;
            var name=Component.literal(row.getString("name"));
            String line=identity(row), evaluation=status(row)+"   ·   "+date(row);
            var btn=new Button(x+8,py,inner,35,name,b->{notesTab=false; request(row.getUUID("town"),page,0);},supplier->supplier.get()) {
                @Override protected void renderWidget(GuiGraphics g,int mx,int my,float t) {
                    int left=getX()+6, top=getY()+3;
                    g.fill(getX(),getY(),getX()+width,getY()+height,isHoveredOrFocused()?0xff424d49:0xff2d393b);
                    g.fill(getX(),getY(),getX()+2,getY()+height,GOLD);
                    g.drawString(font,name,left,top,GOLD,false);
                    g.drawString(font,font.plainSubstrByWidth(line,width-10),left,top+10,MUTED,false);
                    g.drawString(font,font.plainSubstrByWidth(evaluation,width-10),left,top+20,0xffeee6ce,false);
                }
            };
            btn.setTooltip(Tooltip.create(Component.literal(line+"\n"+evaluation)));
            addRenderableWidget(btn);
        }
        button(x+8,bottom-24,38,Component.literal("‹"),()->request(null,page-1,0)).active=page>0;
        button(x+w-46,bottom-24,38,Component.literal("›"),()->request(null,page+1,0)).active=page+1<data.getInt("pages");
    }
    private static String identity(CompoundTag row) {
        String affiliation=row.getString("nation");
        return (affiliation.isEmpty()?tr("independent").getString():affiliation)+"   ·   "+
                tr("mayor").getString()+" "+row.getString("mayor")+"   ·   "+
                tr("people").getString()+" "+row.getInt("population")+"   ·   "+tr("land").getString()+" "+row.getInt("claims");
    }
    private static String status(CompoundTag row) {
        return level(row.getInt("level")).getString()+"   ·   "+(row.getInt("score")<0?tr("unevaluated").getString():
                row.getInt("score")+"/100")+"   ·   "+stars(row.getInt("rating")).getString();
    }
    private static String date(CompoundTag row) {
        return row.contains("date")?Instant.ofEpochMilli(row.getLong("date")).toString().substring(0,10):"—";
    }
    private void detail() {
        int half=(w-18)/2;
        button(x+8,y+53,half,notesTab?tr("scores"):tr("review_notes"),()->{notesTab=!notesTab;rebuildWidgets();});
        button(x+10+half,y+53,half,history==0?tr("history"):tr("current_review"),()->{
            notesTab=false;request(town,page,history==0?revision:0);
        }).active=revision>0;
        if(notesTab) notesForm(); else scoreForm();
        if(history>0) {
            button(x+8,bottom-23,43,Component.literal("‹"),()->request(town,page,history-1)).active=history>1;
            button(x+w-51,bottom-23,43,Component.literal("›"),()->request(town,page,history+1)).active=history<revision;
        } else {
            if (feedback.equals("city_conflict")) button(x+8,bottom-23,90,tr("reload"),()->request(town,page,0));
            saveButton=button(x+w-128,bottom-23,120,tr("save_review"),this::save);
            updateControls();
        }
    }
    private void scoreForm() {
        int numericWidth=36;
        for (var c:Category.values()) {
            int i=c.ordinal(), py=y+75+i*18;
            var slider=new ScoreSlider(x+8,py,w-20-numericWidth,c);
            slider.active=history==0; sliders[i]=addRenderableWidget(slider);
            var number=input(x+w-8-numericWidth,py,numericWidth,"score",Integer.toString(scores[i]),2,v->{
                if(!v.isEmpty()) {scores[i]=Integer.parseInt(v); slider.sync();updateControls();}
                else updateControls();
            });
            number.setFilter(v->v.isEmpty() || v.matches("[0-9]{1,2}") && Integer.parseInt(v)<=c.max);
            number.setEditable(history==0); numbers[i]=number;
        }
        int half=(w-18)/2;
        button(x+8,y+170,half,tr("choose_level",level(level)),()->{
            level=level%5+1;rebuildWidgets();
        }).active=history==0;
        ratingButton=button(x+10+half,y+170,half,tr("final_rating",stars(rating)),()->{
            rating=rating%5+1;followSuggestion=rating==suggested();updateControls();
        });
        ratingButton.active=history==0;
    }
    private final class ScoreSlider extends AbstractSliderButton {
        final Category category;
        ScoreSlider(int px,int py,int width,Category c) {
            super(px,py,width,16,Component.empty(),scores[c.ordinal()]/(double)c.max);
            category=c; updateMessage();
            setTooltip(Tooltip.create(tr("rubric_"+c.name().toLowerCase(Locale.ROOT))));
        }
        void sync() {value=scores[category.ordinal()]/(double)category.max;updateMessage();}
        @Override protected void updateMessage() {
            if(category!=null) setMessage(tr(category.name().toLowerCase(Locale.ROOT)).copy().append("  "+scores[category.ordinal()]+"/"+category.max));
        }
        @Override protected void applyValue() {
            int i=category.ordinal();scores[i]=(int)Math.round(value*category.max);
            numbers[i].setValue(Integer.toString(scores[i]));updateControls();
        }
    }
    private void notesForm() {
        EditBox r=input(x+8,y+88,w-16,"override_reason",reason,256,v->{reason=v;updateControls();});
        EditBox n=input(x+8,y+126,w-16,"internal_notes",notes,256,v->notes=v);
        EditBox s=input(x+8,y+164,w-16,"summary",summary,256,v->summary=v);
        for (var box:List.of(r,n,s)) box.setEditable(history==0);
    }
    private void updateControls() {
        if(followSuggestion) rating=suggested();
        if(ratingButton!=null) ratingButton.setMessage(tr("final_rating",stars(rating)));
        if(saveButton!=null) saveButton.active=(rating==suggested() || !reason.isBlank())
                && (notesTab || Arrays.stream(numbers).noneMatch(n->n!=null && n.getValue().isBlank()))
                && !feedback.equals("city_conflict");
    }
    private void save() {
        expected=++sequence; saveButton.active=false;
        var draft=new CityEvaluation.Draft(scores[0],scores[1],scores[2],scores[3],scores[4],level,rating,reason,notes,summary);
        PanelNetwork.CHANNEL.sendToServer(new CityNetwork.Save(expected,town,revision,draft));
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta) {
        renderBackground(g);
        g.fill(x,y,x+w,bottom,INK);g.renderOutline(x,y,w,bottom-y,0xff786a50);
        g.fill(x+1,y+1,x+w-1,y+3,GOLD);
        String title=town==null?getTitle().getString():data.getString("name")+"  /  "+tr("city_evaluation").getString();
        g.drawString(font,font.plainSubstrByWidth(title,w-68),x+8,y+10,GOLD,false);
        if(town==null) {
            if(data.getList("rows",Tag.TAG_COMPOUND).isEmpty()) g.drawCenteredString(font,tr("empty"),width/2,y+86,MUTED);
            g.drawCenteredString(font,(page+1)+" / "+data.getInt("pages"),width/2,bottom-18,MUTED);
        } else {
            String id=identity(data);
            g.drawString(font,font.plainSubstrByWidth(id,w-16),x+8,y+28,MUTED,false);
            if(my>=y+25 && my<y+38) setTooltipForNextRenderPass(Component.literal(id));
            g.drawString(font,font.plainSubstrByWidth(status(data),w-16),x+8,y+40,GOLD,false);
            if(notesTab) {
                g.drawString(font,tr("override_reason"),x+8,y+76,GOLD,false);
                g.drawString(font,tr("internal_notes"),x+8,y+114,MUTED,false);
                g.drawString(font,tr("summary"),x+8,y+152,MUTED,false);
            }
            g.drawString(font,tr("live_score",total(),stars(suggested())),x+8,y+192,GOLD,false);
            if(history>0) {
                var e=data.getCompound("review");
                var audit=Component.literal("#"+history+" / "+revision+"  ·  "+date(e)+"  ·  "+e.getUUID("reviewer"));
                g.drawCenteredString(font,font.plainSubstrByWidth(audit.getString(),w-112),width/2,bottom-17,MUTED);
                if(my>bottom-26) setTooltipForNextRenderPass(audit);
            }
        }
        String msg=feedback;
        if(town!=null && history==0 && rating!=suggested() && reason.isBlank()) msg="city_reason";
        if(!msg.isEmpty() && history==0) {
            var note=Component.translatable("historical_nations."+msg);
            
            int py=town==null?bottom-37:bottom-34;
            g.drawString(font,font.plainSubstrByWidth(note.getString(),w-16),x+8,py,msg.equals("city_saved")?MUTED:0xffffb498,false);
            if(my>=py-1 && my<=py+10) setTooltipForNextRenderPass(note);
        }
        super.render(g,mx,my,delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
