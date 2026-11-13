package dev.zeorez.hntests;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import static dev.zeorez.hntests.PrototypeServerTest.check;

final class EconomyTest {
    private final PrototypeServerTest.Suite s;
    private final EconomyApi api;
    EconomyTest(PrototypeServerTest.Suite suite) {
        s = suite;
        api = EconomyApi.get(s.server);
    }
    static void operator(MinecraftServer server, String command) throws CommandSyntaxException {
        check(server.getCommands().getDispatcher().execute("economy " + command, server.createCommandSourceStack()) == 1,
                "operator command " + command);
    }
    void setup() throws CommandSyntaxException {
        var mayor = s.a;
        var buyer = s.c;
        var town = s.api.getPlayerTown(mayor.getUUID()).orElseThrow();
        mayor.setPos(-28,80,34);
        buyer.setPos(-28,80,34);
        var pos = buyer.blockPosition();
        var dim = Level.OVERWORLD.location();
        var quarter = Parcels.quarter(s.server, dim, pos).orElseThrow();
        check(quarter.owner().equals(buyer.getUUID()) && quarter.boxes().size() == 2, "v0.3 quarter upgrade");
        check(api.getBalance(buyer.getUUID()) == 0 && api.getTownBalance(town.id()) == 0, "migration balance");
        s.command(mayor, "nation create Anadolu", "nation_created");
        var nation = s.api.getPlayerNation(mayor.getUUID()).orElseThrow();
        operator(s.server,"give player HnAlice 400 initial funds");
        s.command(mayor,"money pay HnCem 120","payment_done");
        s.command(buyer,"town bank deposit 30","payment_done");
        s.command(buyer,"town bank withdraw 1","!mayor_only");
        s.command(mayor,"town bank withdraw 10","payment_done");
        s.command(buyer,"nation bank deposit 20","payment_done");
        s.command(buyer,"nation bank withdraw 1","!leader_only");
        s.command(mayor,"nation bank withdraw 5","payment_done");
        s.command(mayor,"plot evict","updated");
        s.command(mayor,"plot forsale 100","updated");
        s.command(buyer,"plot claim","!insufficient_funds");
        var plot = Parcels.plot(s.server, dim, pos).orElseThrow();
        check(plot.price() == 100 && plot.owner() == null && api.getBalance(buyer.getUUID()) == 70, "denied sale atomic");
        s.command(mayor,"plot forsale 40","updated");
        s.command(buyer,"plot claim","updated");
        s.command(mayor,"plot claim","!no_offer");
        s.command(buyer,"quarter forsale 20","updated");
        s.command(mayor,"quarter claim","updated");
        s.command(buyer,"quarter claim","!no_offer");
        check(api.getBalance(buyer.getUUID()) == 50 && api.getBalance(mayor.getUUID()) == 275, "private sale");
        check(api.getTownBalance(town.id()) == 60 && api.getNationBalance(nation.id()) == 15, "bank and municipal sale");

        s.command(mayor,"town invite HnDeniz","invited");
        s.command(s.d,"town accept Ankara","town_joined");
        s.command(buyer,"town tax set 90","!mayor_only");
        s.command(buyer,"nation upkeep set 90","!leader_only");
        s.command(mayor,"town tax set 7","daily_amount");
        s.command(mayor,"nation tax set 9","daily_amount");
        s.command(mayor,"town upkeep set 3","daily_amount");
        s.command(mayor,"nation upkeep set 2","daily_amount");
        FinanceClockFixture.day(s.server,1);
        FinanceClockFixture.day(s.server,1);
        FinanceClockFixture.day(s.server,-1);
        balances();
        check(s.api.getPlayerTown(s.d.getUUID()).isPresent(), "unpaid resident retained");
        operator(s.server,"set player HnBora 9223372036854775807 overflow test");
        s.command(mayor,"money pay HnBora 1","!balance_limit");
        operator(s.server,"take player HnBora 9223372036854775807 reset");
        s.command(mayor,"nation disband","!bank_not_empty");
        s.command(mayor,"town bank history","account");
        s.command(mayor,"nation bank history","account");
    }
    private void balances() {
        check(api.getBalance(s.a.getUUID()) == 268 && api.getBalance(s.c.getUUID()) == 43
                && api.getTownBalance(s.api.getPlayerTown(s.a.getUUID()).orElseThrow().id()) == 62
                && api.getNationBalance(s.api.getPlayerNation(s.a.getUUID()).orElseThrow().id()) == 22,
                "daily tax/upkeep, rollback or restart");
    }
    void verify() throws CommandSyntaxException {
        FinanceClockFixture.day(s.server,1);
        balances();
        var town = s.api.getPlayerTown(s.a.getUUID()).orElseThrow();
        var nation = s.api.getPlayerNation(s.a.getUUID()).orElseThrow();
        s.a.setPos(-28,80,34);
        var dim = Level.OVERWORLD.location();
        check(Parcels.quarter(s.server,dim,s.a.blockPosition()).orElseThrow().owner().equals(s.a.getUUID())
                && Parcels.plot(s.server,dim,s.a.blockPosition()).orElseThrow().owner().equals(s.c.getUUID()), "paid titles on restart");
        operator(s.server,"give town Izmir 11 savings");
        s.command(s.a,"nation invite Izmir","invited");
        s.command(s.b,"nation accept Anadolu","nation_joined");
        s.command(s.b,"nation leave","nation_left");
        check(api.getTownBalance(s.api.getPlayerTown(s.b.getUUID()).orElseThrow().id()) == 11, "independent town savings");
        s.command(s.b,"town disband","!bank_not_empty");
        s.command(s.a,"town unclaim","land_released");
        var nether = new net.minecraftforge.common.util.FakePlayer(s.server.getLevel(Level.NETHER),s.a.getGameProfile());
        nether.setPos(-17,80,33);
        s.command(nether,"town unclaim","land_released");
        s.command(s.a,"town setmayor HnCem","new_mayor");
        check(s.api.isNationLeader(s.c.getUUID()) && api.getTownBalance(town.id()) == 62 && api.getNationBalance(nation.id()) == 22,
                "institution continuity and Documents API");
        s.command(s.a,"nation bank withdraw 1","!leader_only");
        s.command(s.a,"town bank withdraw 1","!mayor_only");
        var policy = TerritoryConfig.LIQUIDATION.get();
        try {
            TerritoryConfig.LIQUIDATION.set(TerritoryConfig.Liquidation.PAY_LEADER);
            s.command(s.c,"nation disband","nation_disbanded");
            s.command(s.c,"town disband","town_disbanded");
            check(api.getBalance(s.c.getUUID()) == 127, "liquidation funds");
        } finally { TerritoryConfig.LIQUIDATION.set(policy); }
    }
}
