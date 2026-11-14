package dev.zeorez.hntests;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.zeorez.historicalnations.api.EconomyApi;
import dev.zeorez.historicalnations.core.WorldRegistry;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import java.util.*;
import static dev.zeorez.hntests.PrototypeServerTest.check;

final class TradeTest {
    private final PrototypeServerTest.Suite s;
    private final EconomyApi money;
    TradeTest(PrototypeServerTest.Suite suite) { s=suite; money=EconomyApi.get(s.server); }

    private static void hand(ServerPlayer p, int amount) {
        var stack = new ItemStack(Items.IRON_INGOT,amount);
        stack.getOrCreateTag().putInt("Lot",7);
        p.setItemInHand(InteractionHand.MAIN_HAND,stack);
    }
    private String offer(ServerPlayer seller,ServerPlayer buyer,int count,long price) throws CommandSyntaxException {
        var args = s.command(seller,"trade offer "+buyer.getScoreboardName()+" "+count+" "+price+" RHM","trade_quote").getArgs();
        return (String)args[0];
    }
    String setup(ServerPlayer a,ServerPlayer b) throws CommandSyntaxException {
        var registry = WorldRegistry.get(s.server);
        check(money.getTownBalance(registry.namedTown("Berlin").id(),"DEM")==34,"v0.5 town balance");
        s.networkTerritory(a,b); // runs original OPAC claim and stacked apartment assertions
        s.command(a,"nation create Rhine","nation_created");
        s.command(b,"town create Paris","town_created");
        s.command(b,"nation create Seine","nation_created");
        s.command(a,"nation currency create Rheinmark R RHM","updated");
        s.command(a,"nation centralbank issue 1000","updated");
        s.command(a,"nation bank withdraw 1000 RHM","payment_done");
        s.command(a,"money pay HnNetB 550 RHM","payment_done");
        s.command(b,"nation tariff set 10","trade_tariff");
        s.command(b,"nation tariff set 50.01","!trade_rate");
        a.getInventory().clearContent(); b.getInventory().clearContent();
        b.teleportTo(a.serverLevel(),a.getX()+1,a.getY(),a.getZ(),0,0);
        hand(a,64);
        String id = offer(a,b,64,500);
        Object[] quote = s.command(b,"trade info "+id,"trade_quote").getArgs();
        check(quote[3].equals(500L) && quote[4].equals(50L) && quote[5].equals(550L),"quote totals");
        s.command(a,"trade accept "+id,"!trade_role");
        s.command(b,"trade cancel "+id,"!trade_role");
        s.command(b,"nation embargo add Rhine","trade_bans");
        s.command(b,"trade accept "+id,"!trade_embargo");
        s.command(a,"trade offer HnNetB 64 500 RHM","!trade_embargo");
        s.command(b,"nation embargo remove Rhine","trade_bans");
        s.command(b,"nation tariff set 11","trade_tariff");
        s.command(b,"trade accept "+id,"!trade_requote");
        s.command(b,"nation tariff set 10","trade_tariff");
        a.getMainHandItem().getOrCreateTag().putInt("Lot",8);
        s.command(b,"trade accept "+id,"!trade_item");
        hand(a,64);
        b.teleportTo(a.getX()+9,a.getY(),a.getZ());
        s.command(b,"trade accept "+id,"!trade_distance");
        b.teleportTo(a.getX()+1,a.getY(),a.getZ());
        for(int slot=0;slot<b.getInventory().items.size();slot++)
            b.getInventory().items.set(slot,new ItemStack(Items.STONE,64));
        s.command(b,"trade accept "+id,"!trade_full");
        b.getInventory().clearContent();
        String expensive=offer(a,b,1,501);
        s.command(b,"trade accept "+expensive,"!insufficient_funds");
        s.command(b,"trade deny "+expensive,"trade_closed");
        String canceled=offer(a,b,1,1);
        check(s.command(b,"trade info "+canceled,"trade_quote").getArgs()[4].equals(1L),"tariff ceiling");
        s.command(a,"trade cancel "+canceled,"trade_closed");
        check(a.getMainHandItem().getCount()==64 && b.getInventory().isEmpty() &&
                money.getBalance(b.getUUID(),"RHM")==550 && s.command(b,"nation trade imports","trade_imports")
                .getArgs()[0].equals("0"),"failed trades unchanged");
        // Two separate listings on the same 64 ingots and two accepts of the same id.
        String competing=offer(a,b,64,500);
        EconomyTest.operator(s.server,"give player HnNetB 1 network acknowledgement");
        b.sendSystemMessage(net.minecraft.network.chat.Component.literal("HN_TEST_TRADE "+id+" "+competing));
        return competing;
    }
    boolean acknowledged(ServerPlayer b) {
        return money.getTownBalance(s.api.getPlayerTown(b.getUUID()).orElseThrow().id())==1;
    }
    void finish(ServerPlayer a,ServerPlayer b,String competing) throws CommandSyntaxException {
        check(a.getMainHandItem().isEmpty() && b.getInventory().countItem(Items.IRON_INGOT)==64
                && b.getInventory().items.stream().anyMatch(stack -> stack.hasTag() && stack.getTag().getInt("Lot")==7),"item transfer");
        s.command(b,"trade accept "+competing,"!trade_item");
        check(money.getBalance(a.getUUID(),"RHM")==950 && money.getBalance(b.getUUID(),"RHM")==0,"one payment");
        verify();
        // Domestic payment is duty free and excluded from import/export totals.
        var resident=s.player("TradeResident");
        s.command(b,"town invite TradeResident","invited");
        s.command(resident,"town accept Paris","town_joined");
        resident.setPos(b.getX(),b.getY(),b.getZ());
        hand(resident,1);
        String domestic=offer(resident,b,1,1);
        check(s.command(b,"trade info "+domestic,"trade_quote").getArgs()[4].equals(0L),"domestic quote");
        s.command(b,"trade deny "+domestic,"trade_closed");
        s.command(resident,"nation tariff clear","!leader_only");
        s.command(resident,"nation embargo add Rhine","!leader_only");
        s.command(a,"nation embargo add Seine","trade_bans");
    }
    void verify() {
        var data=WorldRegistry.get(s.server);
        UUID france=data.namedNation("Seine").id();
        check(money.getNationBalance(france,"RHM")==50,"treasury");
        net.minecraft.world.level.saveddata.SavedData saved = data;
        var snapshot=saved.save(new CompoundTag());
        var stats=snapshot.getCompound("Trade").getList("Stats",Tag.TAG_COMPOUND);
        check(stats.size()==2,"two national statements");
        for (var entry:stats) {
            var line=(CompoundTag)entry;
            check(line.getString("Count").equals("1") && line.getCompound("Volume").getString("RHM").equals("500")
                    && line.getList("Recent",Tag.TAG_STRING).size()==1,"international statistics");
        }
        var policies=snapshot.getCompound("Economy").getList("Policies",Tag.TAG_COMPOUND);
        for (var p:policies) {
            var row=(CompoundTag)p;
            if(row.getString("Code").equals("RHM")) check(row.getString("Volume").equals("1050"),"activity counted exactly once");
        }
    }
    void restart() throws CommandSyntaxException {
        verify();
        var a=s.player("HnNetA"); var b=s.player("HnNetB");
        s.command(b,"nation trade stats","trade_stats");
        s.command(a,"nation trade exports","trade_exports");
        var bans=s.command(b,"nation embargo list","trade_bans").getArgs();
        check(bans[1].equals("Rhine"),"embargo persisted");
        s.command(a,"nation disband","nation_disbanded");
        check(s.command(b,"nation embargo list","trade_bans").getArgs()[1].equals(""),"embargo removed on disband");
    }
}
