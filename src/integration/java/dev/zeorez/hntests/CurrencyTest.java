package dev.zeorez.hntests;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.zeorez.historicalnations.api.*;
import net.minecraft.server.level.ServerPlayer;
import java.math.BigDecimal;
import static dev.zeorez.hntests.PrototypeServerTest.check;

final class CurrencyTest {
    private final PrototypeServerTest.Suite s;
    private final EconomyApi api;
    CurrencyTest(PrototypeServerTest.Suite suite) { s = suite; api = EconomyApi.get(s.server); }
    private void atPlot() {
        s.a.setPos(208,80,208);
        s.c.setPos(208,80,208);
    }
    // Only v0.4 public entry points here. Run with the old production jar.
    void legacy() throws CommandSyntaxException {
        atPlot();
        s.command(s.a,"town create Berlin","town_created");
        s.command(s.a,"nation create Germany","nation_created");
        s.command(s.a,"town claim","land_claimed");
        s.command(s.a,"town invite HnCem","invited");
        s.command(s.c,"town accept Berlin","town_joined");
        s.command(s.b,"town create London","town_created");
        s.command(s.b,"nation create Britain","nation_created");
        s.command(s.a,"plot forsale 31","updated");
        EconomyTest.operator(s.server,"give player HnCem 500 legacy");
        s.command(s.c,"town bank deposit 25","payment_done");
        s.command(s.c,"nation bank deposit 15","payment_done");
        s.command(s.a,"town tax set 3","daily_amount");
    }
    private Object[] stats(ServerPlayer player) throws CommandSyntaxException {
        return s.command(player,"nation economy","national_economy").getArgs();
    }
    void setup() throws CommandSyntaxException {
        atPlot();
        var town = s.api.getPlayerTown(s.a.getUUID()).orElseThrow();
        var nation = s.api.getPlayerNation(s.a.getUUID()).orElseThrow();
        check(api.getBalance(s.c.getUUID()) == 460 && api.getTownBalance(town.id()) == 25 &&
                api.getNationBalance(nation.id()) == 15,"v0.4 account migration");
        s.command(s.c,"nation currency create Mark RM DEM","!leader_only");
        s.command(s.a,"nation currency create Reichsmark RM DEM","updated");
        s.command(s.b,"nation currency create Mark M dem","!currency_invalid");
        s.command(s.b,"nation currency create Reichsmark P GBP","!currency_invalid");
        s.command(s.b,"nation currency create Sterling P GBP","updated");
        s.command(s.a,"nation currency create Franc F FRF","!currency_invalid");
        s.command(s.c,"plot claim","updated");
        check(api.getBalance(s.c.getUUID()) == 429 && api.getTownBalance(town.id()) == 56,"v0.4 sale paid in TRY");
        s.command(s.a,"nation centralbank issue 1000","updated");
        s.command(s.a,"nation centralbank issue 9001","!issue_limit");
        s.command(s.c,"nation centralbank issue 1","!leader_only");
        s.command(s.b,"nation centralbank issue 200","updated");
        s.command(s.a,"nation centralbank interest 3.25","updated");
        s.command(s.a,"nation bank withdraw 500 DEM","payment_done");
        s.command(s.b,"nation bank withdraw 200 GBP","payment_done");
        s.command(s.a,"money pay HnCem 200 DEM","payment_done");
        s.command(s.c,"exchange convert DEM GBP 10","!fx_reserve");
        check(api.getBalance(s.c.getUUID(),"DEM") == 200,"empty reserve atomic");
        s.command(s.b,"exchange fund GBP 180","payment_done");
        s.command(s.a,"exchange fund DEM 100","payment_done");
        s.command(s.c,"exchange convert DEM GBP 30","payment_done");
        s.command(s.c,"money balance","balance");
        s.command(s.c,"money balance GBP","balance");
        check(api.getBalance(s.c.getUUID(),"GBP") == 30 && api.getBalance(s.c.getUUID(),"DEM") == 170,"two wallets");
        s.command(s.c,"town bank deposit 20 DEM","payment_done");
        s.command(s.a,"town bank withdraw 5 DEM","payment_done");
        s.command(s.a,"quarter pos1","corner");
        s.a.setPos(210,82,210);
        s.command(s.a,"quarter pos2","corner");
        s.command(s.a,"quarter create Flat","updated");
        s.command(s.a,"quarter forsale 11","updated");
        s.command(s.c,"quarter claim","updated");
        s.command(s.c,"plot forsale 17 GBP","updated");
        s.command(s.a,"plot claim","!insufficient_funds");
        s.command(s.a,"town tax set 5","daily_amount");
        s.command(s.a,"town upkeep set 2","daily_amount");
        long base = api.getBalance(s.c.getUUID());
        FinanceClockFixture.day(s.server,1);
        Object[] de = stats(s.a), gb = stats(s.b);
        check(new BigDecimal(de[2].toString()).signum()>0 && !de[2].equals(gb[2]),"national inflation");
        check(new BigDecimal(de[5].toString()).compareTo(new BigDecimal("0.97"))>=0 &&
                !de[5].equals(gb[5]) && de[6].equals("3.25"),"bounded FX and policy");
        check(api.getTownBalance(town.id(),"DEM") == 34 && api.getBalance(s.c.getUUID(),"DEM")==134
                && api.getBalance(s.c.getUUID())==base,"national fees, base funds unchanged");
        long before = api.getBalance(s.c.getUUID(),"GBP");
        s.command(s.c,"exchange convert GBP DEM 17","payment_done");
        long received = api.getBalance(s.c.getUUID(),"DEM") - 134;
        s.command(s.c,"exchange convert DEM GBP "+received,"payment_done");
        check(api.getBalance(s.c.getUUID(),"GBP") <= before && api.getBalance(s.c.getUUID(),"DEM")==134,"rounding conservation");
        FinanceClockFixture.day(s.server,1);
        FinanceClockFixture.day(s.server,-1);
        check(java.util.Arrays.equals(stats(s.a),de),"one update per day");
    }
    void verify() throws CommandSyntaxException {
        atPlot();
        var town = s.api.getPlayerTown(s.c.getUUID()).orElseThrow();
        check(api.getTownBalance(town.id(),"DEM")==34 && api.getBalance(s.c.getUUID(),"DEM")==134 &&
                api.getBalance(s.c.getUUID())==429,"restart balances");
        var dim = s.a.level().dimension().location();
        var listing = Parcels.plot(s.server,dim,s.a.blockPosition()).orElseThrow();
        check(listing.price()==17 && listing.currency().equals("GBP") && Parcels.quarter(s.server,dim,s.a.blockPosition())
                .orElseThrow().owner().equals(s.c.getUUID()),"listing and quarter on restart");
        s.command(s.a,"nation centralbank issue 9001","!issue_limit");
        s.command(s.a,"exchange history DEM","currency_history");
        s.command(s.b,"nation disband","nation_disbanded");
        s.command(s.b,"nation create NewBritain","nation_created");
        s.command(s.b,"nation currency create Sterling P GBP","!currency_invalid");
        s.command(s.b,"nation centralbank issue 1","!unknown_currency");
        s.command(s.b,"money pay HnAlice 17 GBP","payment_done");
        s.command(s.a,"plot claim","updated");
        check(api.getBalance(s.a.getUUID(),"GBP")==0 && api.getBalance(s.b.getUUID(),"GBP")==3 &&
                Parcels.plot(s.server,dim,s.a.blockPosition()).orElseThrow().owner().equals(s.a.getUUID()),"historical private sale");
    }
}
