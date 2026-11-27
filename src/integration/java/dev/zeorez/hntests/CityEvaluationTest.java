package dev.zeorez.hntests;

import dev.zeorez.historicalnations.core.*;
import dev.zeorez.historicalnations.gui.CityNetwork;
import dev.zeorez.historicalnations.gui.PanelQuery;
import dev.zeorez.historicalnations.map.*;
import net.minecraft.nbt.Tag;
import java.util.List;
import static dev.zeorez.hntests.PrototypeServerTest.check;

/** Run citySetup and then cityVerify on a disposable copy of the v9 map fixture. */
final class CityEvaluationTest {
    private static void rejected(Runnable call, String key) {
        try {call.run();} catch (RuleViolation error) {
            check(error.getMessage().equals(key),"expected "+key+" got "+error.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection "+key);
    }
    static void setup(PrototypeServerTest.Suite s) throws Exception {
        new WorldMapTest(s).verify();
        int[] points={0,20,21,40,41,60,61,80,81,100}, expected={1,1,2,2,3,3,4,4,5,5};
        for(int i=0;i<points.length;i++) check(CityEvaluation.suggested(points[i])==expected[i],"boundary "+points[i]);
        var data=WorldRegistry.get(s.server);
        var id=data.namedTown("Kumanovo").id();
        var fresh=data.createTown(s.player("HnBuilder").getUUID(),"Port Vale");
        var mgr=PoliticalMapServer.get(s.server);
        var before=mgr.ensureSnapshot();
        for(var town:List.of(id,fresh.id())) {
            var t=before.towns().get(town);
            check(data.evaluation(town)==null && t.level()==1 && t.score()==-1 && t.rating()==0,"legacy and new defaults");
        }
        var admin=s.player("HnReviewer");
        s.server.getPlayerList().op(admin.getGameProfile());
        var first=new CityEvaluation.Draft(24,16,18,14,8,2,4,"","private-one","Finish the station");
        rejected(()->CityPanel.page(s.b,CityNetwork.Query.open(id)),"city_permission");
        rejected(()->data.evaluate(s.a,id,0,first),"city_permission");
        rejected(()->data.evaluate(s.b,id,0,first),"city_permission");
        rejected(()->data.evaluate(admin,id,0,new CityEvaluation.Draft(31,0,0,0,0,1,2,"","","")),"city_invalid");
        rejected(()->data.evaluate(admin,id,0,new CityEvaluation.Draft(30,20,20,20,10,1,4,"  ","","")),"city_reason");
        data.evaluate(admin,id,0,first);
        var after=mgr.ensureSnapshot();
        var delta=PoliticalMapServer.computeDelta(before,after);
        check(delta.upsertedTowns().size()==1 && delta.changedClaims().isEmpty() && before.claims()==after.claims(),"town-only delta");
        var t=MapModels.DeltaPayload.decodeCompressed(delta.encodeCompressed()).upsertedTowns().get(0);
        check(t.id().equals(id) && t.score()==80 && t.rating()==4 && t.level()==2,"wire score and independent level");
        rejected(()->data.evaluate(admin,id,0,first),"city_conflict");
        data.evaluate(admin,id,1,new CityEvaluation.Draft(30,20,20,20,10,2,4,"Station closed","private-two","Restore passenger service"));
        var filtered=CityPanel.page(admin,new CityNetwork.Query(1,null,0,1,"kuma","yugo",2,4,CityNetwork.Sort.RECENT,0));
        check(filtered.getList("rows",Tag.TAG_COMPOUND).size()==1 && filtered.getList("rows",Tag.TAG_COMPOUND).getCompound(0).getUUID("town").equals(id),"directory filters");
        data.leaveNation(s.b.getUUID());
        data.renameTown(s.b.getUUID(),"Kumanova");
        check(data.evaluation(id).draft().rating()==4,"identity survives rename and nation change");
        data.renameTown(s.b.getUUID(),"Kumanovo");
        s.command(s.a,"nation invite Kumanovo","invited");
        s.command(s.b,"nation accept Kingdom of Yugoslavia","nation_joined");
        verify(s);
    }
    static void verify(PrototypeServerTest.Suite s) throws Exception {
        new WorldMapTest(s).verify();
        var data=WorldRegistry.get(s.server);
        var id=data.namedTown("Kumanovo").id();
        var admin=s.player("HnReviewer");
        var page=CityPanel.page(admin,CityNetwork.Query.open(id));
        check(page.getInt("revision")==2 && page.getCompound("review").getString("notes").equals("private-two"),"current private revision");
        var old=CityPanel.page(admin,new CityNetwork.Query(1,id,0,1,"","",0,-1,CityNetwork.Sort.NAME,1)).getCompound("review");
        check(old.getInt("total")==80 && old.getString("notes").equals("private-one") && old.getUUID("reviewer").equals(admin.getUUID())
                && old.getLong("date")>0,"prior scores, notes, reviewer, timestamp");
        var dossier=PoliticalPanel.page(s.b,PanelQuery.open(PanelQuery.View.TOWN,id)).toString();
        check(dossier.contains("Restore passenger service") && dossier.contains("30/30") && dossier.contains("★★★★☆")
                && !dossier.contains("private-two") && !dossier.contains("Station closed") && !dossier.contains(admin.getUUID().toString()),"public dossier privacy");
        var summary=PoliticalMapServer.get(s.server).ensureSnapshot().towns().get(id);
        check(summary.level()==2 && summary.score()==100 && summary.rating()==4,"persisted manually overridden stars");
    }
}
