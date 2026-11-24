package dev.zeorez.hntests;

import dev.zeorez.historicalnations.core.*;
import dev.zeorez.historicalnations.gui.PanelQuery;
import net.minecraft.nbt.*;
import java.util.UUID;
import static dev.zeorez.historicalnations.gui.PanelQuery.*;
import static dev.zeorez.hntests.PrototypeServerTest.check;

/** Real OPAC, currency and directory data plus a synthetic high-cardinality registry. */
final class PoliticalPanelTest {
    static void run(PrototypeServerTest.Suite s) throws Exception {
        var d=WorldRegistry.get(s.server);
        check(page(s,View.NATIONS,Sort.NAME,false,"",0).getInt("total")==0,"empty");
        GoldCosts.giveGold(s.a,130);
        s.a.setPos(8,80,8);
        s.command(s.a,"town create Alba","town_created");
        s.command(s.a,"nation create Union","nation_created");
        s.command(s.a,"town invite HnBora","invited");
        s.command(s.b,"town accept Alba","town_joined");
        s.command(s.a,"nation currency create Testmark M TMK","updated");
        s.command(s.a,"nation centralbank issue 123","updated");
        var town=d.home(s.a.getUUID());
        var nation=d.country(s.a.getUUID());
        var extra=d.createTown(s.c.getUUID(),"Long Municipality Name");
        d.inviteTown(s.a.getUUID(),extra);
        d.answerNation(s.c.getUUID(),nation,true);
        for(int i=0;i<400;i++) {
            UUID mayor=new UUID(0,i);
            d.createTown(mayor,"District "+i);
            d.createNation(mayor,"Republic "+i);
        }
        for(var criterion:new Sort[]{Sort.PEOPLE,Sort.LAND,Sort.TOWNS,Sort.TREASURY}) {
            var first=page(s,View.NATIONS,criterion,true,"",0);
            check(first.getInt("total")==401 && first.getInt("pages")==81,"bounded directory");
            var row=first.getList("rows",10).getCompound(0);
            check(row.getUUID("id").equals(nation.id()),"highest "+criterion);
            check(row.getList("cells",8).getString(6).equals("123"),"official quote valuation");
            check(!page(s,View.NATIONS,criterion,false,"",0).getList("rows",10).getCompound(0)
                    .getUUID("id").equals(nation.id()),"ascending "+criterion);
        }
        check(page(s,View.NATIONS,Sort.NAME,false,"REPUBLIC",Integer.MAX_VALUE).getInt("page")==79,"search and clamp");
        check(page(s,View.TOWNS,Sort.PEOPLE,true,"",0).getList("rows",10).getCompound(0).getUUID("id").equals(town.id()),"town population");
        check(page(s,View.TOWNS,Sort.LAND,true,"",0).getList("rows",10).getCompound(0).getUUID("id").equals(town.id()),"town land");
        check(page(s,View.TOWNS,Sort.NAME,false,"Municipality",0).getInt("total")==1,"town search");
        check(PoliticalPanel.page(s.a,PanelQuery.open(View.RESIDENTS,town.id())).getInt("total")==2,"residents");
        check(PoliticalPanel.page(s.b,PanelQuery.open(View.MEMBER_TOWNS,nation.id())).getInt("total")==2,"member towns");
        check(OfficeActions.DESCRIPTION.perform(s.a,town.id(),false,"Council hall").success(),"mayor edit");
        check(OfficeActions.DEPUTY.perform(s.a,town.id(),false,s.b.getUUID().toString()).success(),"resident UUID appointment");
        check(s.b.getUUID().equals(d.deputy(town.id())),"correct deputy");
        check(OfficeActions.REMOVE_DEPUTY.perform(s.a,town.id(),false,"").success(),"remove deputy");
        check(!OfficeActions.DESCRIPTION.perform(s.b,town.id(),false,"Unauthorized").success(),"citizen restriction");
        check(!OfficeActions.DISBAND.perform(s.a,extra.id(),false,"").success(),"stale target");
        check(PoliticalPanel.page(s.b,PanelQuery.open(View.TOWN,town.id())).toString().contains("Council hall"),"fresh observer data");
        s.command(s.b,"t info Alba","town_info"); s.command(s.a,"n info Union","nation_info");
        s.command(s.b,"t list","towns"); s.command(s.a,"n list","nations");
        s.command(s.a,"plot info","parcel_info");
        s.command(s.a,"quarter pos1","corner");
        s.command(s.a,"quarter pos2","corner");
        s.command(s.a,"quarter create Council","updated");
        s.command(s.a,"quarter info","parcel_info");
    }
    private static CompoundTag page(PrototypeServerTest.Suite s,View v,Sort sort,boolean desc,String text,int page) {
        return PoliticalPanel.page(s.a,new PanelQuery(v,null,sort,desc,text,page,5,1));
    }
}
