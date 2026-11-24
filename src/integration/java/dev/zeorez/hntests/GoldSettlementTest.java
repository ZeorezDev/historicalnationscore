package dev.zeorez.hntests;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.EconomyApi;
import dev.zeorez.historicalnations.core.GoldCosts;
import dev.zeorez.historicalnations.core.WorldRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.config.ServerConfig;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

import static dev.zeorez.hntests.PrototypeServerTest.check;

/** Exercises v7.0.0 Gold-Based Settlement costs, OPAC integration, 100-chunk flat pricing, and v0.6.0 coexistence. */
final class GoldSettlementTest {
    private final PrototypeServerTest.Suite s;
    private final EconomyApi money;

    GoldSettlementTest(PrototypeServerTest.Suite suite) {
        this.s = suite;
        this.money = EconomyApi.get(suite.server);
    }

    private static void clearInv(ServerPlayer player) {
        player.getInventory().clearContent();
    }

    private static void giveGold(ServerPlayer player, int amount) {
        GoldCosts.giveGold(player, amount);
    }

    void setup() throws CommandSyntaxException {
        var dim = Level.OVERWORLD.location();
        var claims = OpenPACServerAPI.get(s.server).getServerClaimsManager();
        var a = s.a;
        var b = s.b;
        var c = s.c;

        // 0. Verify default config values from Section 6.
        check(TerritoryConfig.ENABLE_GOLD_COSTS.get(), "enableGoldCosts default true");
        check(TerritoryConfig.TOWN_CREATION_GOLD_COST.get() == 2, "town.creationGoldCost default 2");
        check(TerritoryConfig.NATION_CREATION_GOLD_COST.get() == 128, "nation.creationGoldCost default 128");
        check(TerritoryConfig.CLAIM_GOLD_COST.get() == 1, "claims.claimGoldCost default 1");
        check(!TerritoryConfig.REFUND_ON_UNCLAIM.get(), "claims.refundOnUnclaim default false");

        clearInv(a);
        clearInv(b);
        clearInv(c);
        a.setPos(8, 80, 8); // chunk (0, 0)

        // Test 3 & 9: Player with 0 or 1 gold (or non-vanilla gold / nuggets) cannot create a town; no item loss.
        var noGold = s.command(a, "town create Ankara", "!insufficient_gold");
        check(noGold.getArgs()[0].equals(2) && noGold.getArgs()[1].equals(0), "insufficient gold args 2/0");
        check(s.api.getPlayerTown(a.getUUID()).isEmpty() && claims.get(dim, 0, 0) == null, "failed create wrote nothing");

        giveGold(a, 1);
        var oneGold = s.command(a, "town create Ankara", "!insufficient_gold");
        check(oneGold.getArgs()[0].equals(2) && oneGold.getArgs()[1].equals(1), "insufficient gold args 2/1");
        check(GoldCosts.countGold(a) == 1, "1 gold preserved on failure");

        // Tagged / custom NBT gold ingot must NOT count as vanilla gold ingot.
        clearInv(a);
        var tagged = new ItemStack(Items.GOLD_INGOT, 4);
        tagged.getOrCreateTag().putInt("CustomModelData", 99);
        a.getInventory().items.set(0, tagged);
        a.getInventory().items.set(1, new ItemStack(Items.GOLD_NUGGET, 64));
        a.getInventory().items.set(2, new ItemStack(Items.GOLD_BLOCK, 2));
        s.command(a, "town create Ankara", "!insufficient_gold");
        check(a.getInventory().items.get(0).getCount() == 4, "tagged gold untouched");

        // Test 1: Player with 2 vanilla gold ingots split across main inventory + offhand creates town + gets free initial chunk.
        clearInv(a);
        a.getInventory().items.set(12, new ItemStack(Items.GOLD_INGOT, 1));
        a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.GOLD_INGOT, 1));
        s.command(a, "town create Ankara", "town_created");
        check(GoldCosts.countGold(a) == 0, "2 gold deducted across slots");
        check(s.api.isTownMayor(a.getUUID()), "mayor set");
        s.checkCount(a, 1);
        var initialClaim = claims.get(dim, 0, 0);
        check(initialClaim != null && initialClaim.getPlayerId().equals(a.getUUID()), "initial free chunk claimed in OPAC");

        // Test 9: Failed town creation on occupied chunk or duplicate name preserves gold.
        b.setPos(8, 80, 8); // same occupied chunk (0, 0)
        giveGold(b, 5);
        s.command(b, "town create Izmir", "!land_occupied");
        check(GoldCosts.countGold(b) == 5 && s.api.getPlayerTown(b.getUUID()).isEmpty(), "occupied chunk preserved gold");
        b.setPos(24, 80, 8); // chunk (1, 0)
        s.command(b, "town create Ankara", "!name_taken");
        check(GoldCosts.countGold(b) == 5 && claims.get(dim, 1, 0) == null, "duplicate name preserved gold");

        // Invite C to Ankara to test non-mayor claim permission.
        s.command(a, "town invite HnCem", "invited");
        s.command(c, "town accept Ankara", "town_joined");

        // Test 2, 3, 7, 9: Claiming costs 1 gold, respects permissions, OPAC failures don't consume gold, no double charge, no unclaim refund.
        a.setPos(40, 80, 8); // chunk (2, 0)
        c.setPos(40, 80, 8);
        s.command(a, "town claim", "!insufficient_gold");
        check(claims.get(dim, 2, 0) == null, "0-gold claim blocked");

        giveGold(c, 10);
        s.command(c, "town claim", "!mayor_only");
        check(GoldCosts.countGold(c) == 10 && claims.get(dim, 2, 0) == null, "non-mayor claim blocked, gold preserved");

        a.getInventory().items.set(17, new ItemStack(Items.GOLD_INGOT, 1));
        s.command(a, "town claim", "land_claimed");
        check(GoldCosts.countGold(a) == 0, "1 gold deducted for claim");
        check(claims.get(dim, 2, 0) != null && claims.get(dim, 2, 0).getPlayerId().equals(a.getUUID()), "OPAC claim created");
        s.checkCount(a, 2);

        // Same chunk cannot be charged twice.
        giveGold(a, 5);
        s.command(a, "town claim", "!land_occupied");
        check(GoldCosts.countGold(a) == 5, "double-claim did not charge gold");

        // OPAC denial does not consume gold.
        a.setPos(56, 80, 8); // chunk (3, 0)
        var aConfig = OpenPACServerAPI.get(s.server).getPlayerConfigManager().getLoadedConfig(a.getUUID());
        aConfig.tryToSet(PlayerConfigOptions.ALLOWED_TO_CLAIM, false);
        try {
            s.command(a, "town claim", "!gui.xaero_claims_not_allowed_in_config");
            check(GoldCosts.countGold(a) == 5 && claims.get(dim, 3, 0) == null, "OPAC rejection preserved gold");
        } finally {
            aConfig.tryToSet(PlayerConfigOptions.ALLOWED_TO_CLAIM, true);
        }

        // Unclaim does NOT refund gold by default.
        a.setPos(40, 80, 8); // chunk (2, 0)
        s.command(a, "town unclaim", "land_released");
        check(GoldCosts.countGold(a) == 5 && claims.get(dim, 2, 0) == null, "unclaim gave no refund");
        s.checkCount(a, 1);

        // Test 4 & 5: First nation created from scratch (0 nations exist) for exactly 128 gold ingots (2 stacks).
        var registry = WorldRegistry.get(s.server);
        check(registry.nations().isEmpty(), "world starts with zero nations");
        clearInv(c);
        giveGold(c, 128);
        s.command(c, "nation create Turkiye", "!mayor_only");
        check(GoldCosts.countGold(c) == 128, "non-mayor nation create preserved gold");

        clearInv(a);
        // Split 127 gold across 3 slots -> must fail without losing gold.
        a.getInventory().items.set(0, new ItemStack(Items.GOLD_INGOT, 64));
        a.getInventory().items.set(5, new ItemStack(Items.GOLD_INGOT, 62));
        a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.GOLD_INGOT, 1));
        var shortNation = s.command(a, "nation create Turkiye", "!insufficient_gold");
        check(shortNation.getArgs()[0].equals(128) && shortNation.getArgs()[1].equals(127), "insufficient nation gold args");
        check(GoldCosts.countGold(a) == 127 && registry.nations().isEmpty(), "127 gold preserved on failed nation create");

        // Add 1 more gold -> total 128 across slots -> first nation succeeds from scratch!
        a.getInventory().items.set(10, new ItemStack(Items.GOLD_INGOT, 1));
        s.command(a, "nation create Turkiye", "nation_created");
        check(GoldCosts.countGold(a) == 0, "128 gold deducted across slots");
        check(registry.nations().size() == 1 && s.api.isNationLeader(a.getUUID()), "first nation founded from scratch");

        // Already-in-nation and duplicate nation name do not consume gold.
        giveGold(a, 128);
        s.command(a, "nation create Second", "!already_in_nation");
        check(GoldCosts.countGold(a) == 128, "already_in_nation preserved gold");
        clearInv(a);

        // B creates Izmir (2 gold -> 3 left), then fails duplicate nation name with 128 gold.
        b.setPos(24, 80, 8); // chunk (1, 0)
        s.command(b, "town create Izmir", "town_created");
        check(GoldCosts.countGold(b) == 3, "B paid 2 gold for Izmir");
        giveGold(b, 125); // total 128
        s.command(b, "nation create Turkiye", "!name_taken");
        check(GoldCosts.countGold(b) == 128, "duplicate nation name preserved gold");

        // Test 6: Claiming up to 100+ chunks while inside a nation ALWAYS costs a flat 1 gold ingot per chunk.
        int oldTownLimit = TerritoryConfig.LIMIT.get();
        int oldOpacLimit = ServerConfig.CONFIG.maxPlayerClaims.get();
        try {
            TerritoryConfig.LIMIT.set(150);
            ServerConfig.CONFIG.maxPlayerClaims.set(150);
            // Ankara has 1 chunk (0,0). Give exactly 99 gold to reach 100 chunks.
            giveGold(a, 99);
            for (int i = 1; i <= 99; i++) {
                int cx = 10 + i;
                a.setPos(cx * 16 + 8, 80, 8);
                int before = GoldCosts.countGold(a);
                s.command(a, "town claim", "land_claimed");
                check(GoldCosts.countGold(a) == before - 1, "chunk #" + (i + 1) + " must cost flat 1 gold");
            }
            check(GoldCosts.countGold(a) == 0, "99 additional chunks cost exactly 99 gold");
            s.checkCount(a, 100);

            // At 100 chunks, claiming the 101st chunk still costs flat 1 gold!
            giveGold(a, 1);
            a.setPos(110 * 16 + 8, 80, 8);
            s.command(a, "town claim", "land_claimed");
            check(GoldCosts.countGold(a) == 0, "101st chunk cost flat 1 gold");
            s.checkCount(a, 101);
            check(claims.get(dim, 110, 0) != null && claims.get(dim, 110, 0).getPlayerId().equals(a.getUUID()), "101st OPAC claim verified");
        } finally {
            TerritoryConfig.LIMIT.set(oldTownLimit);
            ServerConfig.CONFIG.maxPlayerClaims.set(oldOpacLimit);
        }

        // Test 8: Existing v0.6.0 national currency, minting, town & nation banks remain completely intact and independent.
        s.command(a, "nation currency create SteelLira SL HNL", "updated");
        s.command(a, "nation centralbank issue 500", "updated");
        s.command(a, "nation bank withdraw 200 HNL", "payment_done");
        s.command(a, "town bank deposit 50 HNL", "payment_done");
        s.command(a, "money pay HnCem 75 HNL", "payment_done");
        var ankara = s.api.getPlayerTown(a.getUUID()).orElseThrow();
        var turkiye = s.api.getPlayerNation(a.getUUID()).orElseThrow();
        check(money.getNationBalance(turkiye.id(), "HNL") == 300, "nation treasury intact");
        check(money.getTownBalance(ankara.id(), "HNL") == 50, "town bank intact");
        check(money.getBalance(a.getUUID(), "HNL") == 75 && money.getBalance(c.getUUID(), "HNL") == 75, "wallets intact");
        s.command(a, "nation economy", "national_economy");
        s.command(a, "town bank", "balance");
        s.command(a, "nation bank", "balance");

        // Having national currency does NOT substitute for gold ingots on claims.
        int oldTownLimit2 = TerritoryConfig.LIMIT.get();
        int oldOpacLimit2 = ServerConfig.CONFIG.maxPlayerClaims.get();
        try {
            TerritoryConfig.LIMIT.set(150);
            ServerConfig.CONFIG.maxPlayerClaims.set(150);
            a.setPos(111 * 16 + 8, 80, 8);
            s.command(a, "town claim", "!insufficient_gold");
            check(money.getBalance(a.getUUID(), "HNL") == 75, "national currency untouched by gold claim failure");
        } finally {
            TerritoryConfig.LIMIT.set(oldTownLimit2);
            ServerConfig.CONFIG.maxPlayerClaims.set(oldOpacLimit2);
        }
    }

    void verify() throws CommandSyntaxException {
        var dim = Level.OVERWORLD.location();
        var claims = OpenPACServerAPI.get(s.server).getServerClaimsManager();
        var a = s.a;
        var c = s.c;

        // Verify persistence after server restart.
        var ankara = s.api.getPlayerTown(a.getUUID()).orElseThrow();
        var turkiye = s.api.getPlayerNation(a.getUUID()).orElseThrow();
        check(ankara.name().equals("Ankara") && turkiye.name().equals("Turkiye"), "town and nation persisted");
        s.checkCount(a, 101);
        check(money.getNationBalance(turkiye.id(), "HNL") == 300 && money.getTownBalance(ankara.id(), "HNL") == 50
                && money.getBalance(a.getUUID(), "HNL") == 75 && money.getBalance(c.getUUID(), "HNL") == 75,
                "banking and currency persisted across restart");
        check(claims.get(dim, 0, 0) != null && claims.get(dim, 110, 0) != null, "OPAC claims persisted");

        // Verify anti-dupe protection even if an admin enables refundOnUnclaim = true:
        // Initial free chunk (0, 0) has paidGold=0 -> refunds 0 gold; paid chunk (110, 0) has paidGold=1 -> refunds 1 gold.
        clearInv(a);
        boolean oldRefund = TerritoryConfig.REFUND_ON_UNCLAIM.get();
        try {
            TerritoryConfig.REFUND_ON_UNCLAIM.set(true);
            a.setPos(8, 80, 8); // initial FREE chunk (0, 0)
            s.command(a, "town unclaim", "land_released");
            check(GoldCosts.countGold(a) == 0, "free initial chunk must NEVER refund gold even with refundOnUnclaim=true");

            a.setPos(110 * 16 + 8, 80, 8); // PAID chunk (110, 0)
            s.command(a, "town unclaim", "land_released");
            check(GoldCosts.countGold(a) == 1, "paid chunk refunded 1 gold when refundOnUnclaim=true");
        } finally {
            TerritoryConfig.REFUND_ON_UNCLAIM.set(oldRefund);
        }
        s.checkCount(a, 99);
    }

    /** Verifies backward compatibility when loading a real v0.6.0 save in v7.0.0. */
    void upgradeFromV6() throws CommandSyntaxException {
        var data = WorldRegistry.get(s.server);
        var berlin = data.namedTown("Berlin");
        var seine = data.namedNation("Seine");
        long berlinDem = money.getTownBalance(berlin.id(), "DEM");
        check(berlinDem == 34 || berlinDem == 42, "v0.6.0 DEM town balance intact (with optional UTC daily tax/upkeep): " + berlinDem);
        check(money.getNationBalance(seine.id(), "RHM") == 50, "v0.6.0 RHM customs treasury intact");

        // Existing v0.6.0 towns and nations remain intact with no retroactive fees; new town requires 2 gold.
        var newcomer = s.player("HnV7Newcomer");
        newcomer.setPos(500 * 16 + 8, 80, 500 * 16 + 8);
        clearInv(newcomer);
        s.command(newcomer, "town create NewTownV7", "!insufficient_gold");
        giveGold(newcomer, 2);
        s.command(newcomer, "town create NewTownV7", "town_created");
        check(GoldCosts.countGold(newcomer) == 0, "new town in upgraded world paid 2 gold");
        s.checkCount(newcomer, 1);

        // Legacy v0.6.0 territory must have paidGold=0 (cannot be duped even if refundOnUnclaim=true).
        for (var loc : data.territoryLocations(berlin.id())) {
            check(data.territory(loc).paidGold() == 0, "legacy v0.6.0 chunk has paidGold=0");
        }
    }
}
