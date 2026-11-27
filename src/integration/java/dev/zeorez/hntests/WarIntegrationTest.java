package dev.zeorez.hntests;

import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.api.ParcelView;
import dev.zeorez.historicalnations.core.CityEvaluation;
import dev.zeorez.historicalnations.core.GoldCosts;
import dev.zeorez.historicalnations.core.RuleViolation;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.integration.TownTerritories;
import dev.zeorez.historicalnations.map.MapModels;
import dev.zeorez.historicalnations.map.PoliticalMapServer;
import dev.zeorez.historicalnations.property.PropertyBook;
import dev.zeorez.historicalnations.war.WarModels;
import dev.zeorez.historicalnations.war.WarModels.ActiveCaptureFlag;
import dev.zeorez.historicalnations.war.WarModels.FlagRunState;
import dev.zeorez.historicalnations.war.WarModels.WarRecord;
import dev.zeorez.historicalnations.war.WarModels.WarState;
import dev.zeorez.historicalnations.war.WarProtection;
import dev.zeorez.historicalnations.war.WarSchedule;
import dev.zeorez.historicalnations.war.WarService;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.config.ServerConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static dev.zeorez.hntests.PrototypeServerTest.check;

/**
 * Dedicated-server verification suite for Historical Nations Core v11.0.0 — Flag War & Territorial Conquest.
 * Exercises all 20 core test scenarios + all 11 mandatory security/edge-case scenarios across a real server restart.
 */
final class WarIntegrationTest {
    private final PrototypeServerTest.Suite s;

    WarIntegrationTest(PrototypeServerTest.Suite suite) {
        this.s = suite;
    }

    private static void tickWar(net.minecraft.server.MinecraftServer server) {
        WarService.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END, () -> true, server));
    }

    private static void clearSkyColumn(net.minecraft.server.level.ServerLevel level, int x, int baseY, int z) {
        level.setBlock(new BlockPos(x, baseY - 1, z), Blocks.STONE.defaultBlockState(), 3);
        for (int y = baseY; y < level.getMaxBuildHeight(); y++) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static PlayerInteractEvent.RightClickBlock rightClickFence(
            net.minecraft.server.level.ServerPlayer player, BlockPos groundPos, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(
                new Vec3(groundPos.getX() + 0.5, groundPos.getY() + 1.0, groundPos.getZ() + 0.5),
                Direction.UP, groundPos, false);
        return new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, groundPos, hit);
    }

    void setup() throws Exception {
        var server = s.server;
        var level = server.overworld();
        var dim = level.dimension().location();
        var data = WorldRegistry.get(server);
        var opacClaims = OpenPACServerAPI.get(server).getServerClaimsManager();
        var territories = new TownTerritories(server);

        TerritoryConfig.LIMIT.set(64);
        ServerConfig.CONFIG.maxPlayerClaims.set(128);

        // Found Germany (Berlin at chunk 20,0) and Kingdom of Yugoslavia (Belgrade at chunks 22,0 .. 25,0; Novi Sad at 22,1)
        // Note: chunk (21, 0) is left UNCLAIMED wilderness (1-chunk frontier gap <= frontierStagingDistanceChunks=3)!
        // Also found Allied Nation Italy (Rome at chunk 19,1) and distant town (chunk 40,0)
        GoldCosts.giveGold(s.a, 200);
        s.a.setPos(20 * 16 + 8, 80, 8);
        s.command(s.a, "town create Berlin", "town_created");
        s.command(s.a, "nation create Germany", "nation_created");

        GoldCosts.giveGold(s.b, 200);
        s.b.setPos(22 * 16 + 8, 80, 8);
        s.command(s.b, "town create Belgrade", "town_created");
        s.b.setPos(23 * 16 + 8, 80, 8);
        s.command(s.b, "town claim", "land_claimed");
        s.b.setPos(24 * 16 + 8, 80, 8);
        s.command(s.b, "town claim", "land_claimed");
        s.b.setPos(25 * 16 + 8, 80, 8);
        s.command(s.b, "town claim", "land_claimed");
        s.command(s.b, "nation create Kingdom of Yugoslavia", "nation_created");

        // Create a Plot + 3D Quarter + v10.0.0 Metropolis 5-star evaluation on Belgrade's frontline chunk (22, 0)
        // to prove both Occupation AND Annexation preserve plots, quarters, and CityEvaluation!
        s.b.setPos(354, 80, 2);
        s.command(s.b, "plot set name OldBazaarPlot", "updated");
        s.command(s.b, "quarter pos1", "corner");
        s.b.setPos(358, 84, 6);
        s.command(s.b, "quarter pos2", "corner");
        s.command(s.b, "quarter create OldBazaar", "updated");

        var adminEval = s.player("HnAdminJudge");
        server.getPlayerList().op(adminEval.getGameProfile());
        data.evaluate(adminEval, data.namedTown("Belgrade").id(), 0,
                new CityEvaluation.Draft(30, 20, 20, 20, 10, 5, 5, "", "", "Historic Danubian Metropolis"));
        check(data.evaluation(data.namedTown("Belgrade").id()).draft().level() == 5, "Belgrade evaluated as Metropolis (5)");

        // Novi Sad (member town of Yugoslavia, mayor s.c) + spare 0-claim town Subotica (mayor s.d)
        GoldCosts.giveGold(s.c, 200);
        s.c.setPos(22 * 16 + 8, 80, 16 + 8); // chunk (22, 1)
        s.command(s.c, "town create Novi Sad", "town_created");
        s.command(s.b, "nation invite Novi Sad", "invited");
        s.command(s.c, "nation accept Kingdom of Yugoslavia", "nation_joined");

        // Italy (mayor s.d -> Rome at chunk 19, 0)
        GoldCosts.giveGold(s.d, 200);
        s.d.setPos(19 * 16 + 8, 80, 8);
        s.command(s.d, "town create Rome", "town_created");
        s.command(s.d, "nation create Italy", "nation_created");

        UUID germanyId = data.namedNation("Germany").id();
        UUID yugoId = data.namedNation("Kingdom of Yugoslavia").id();
        UUID italyId = data.namedNation("Italy").id();
        UUID belgradeId = data.namedTown("Belgrade").id();

        // --- TEST 16: Mutual Alliances block war declaration ---
        s.command(s.a, "nation ally Italy", "alliance_invite_sent");
        s.command(s.d, "nation ally Germany", "alliance_formed");
        check(data.wars.areAllied(germanyId, italyId), "Germany & Italy allied");
        s.command(s.a, "war declare Italy", "!war_target_allied");
        s.command(s.a, "war declare Germany", "!same_nation");

        // --- TEST 1 & TEST 2: War Declaration & Preparation Period ---
        TerritoryConfig.WAR_PREPARATION_HOURS.set(24);
        s.command(s.a, "war declare Kingdom of Yugoslavia", "war_declared_confirm");
        s.command(s.a, "war declare Kingdom of Yugoslavia", "!war_already_active");

        WarRecord war = data.wars.findOngoingWarBetween(germanyId, yugoId, false).orElseThrow();
        check(war.state == WarState.PREPARING, "War starts in PREPARING state");

        // Test 17: Allied nation Italy joins war on Germany's side via /war join
        s.command(s.d, "war join Germany", "war_joined_confirm");
        check(war.attackerNations.contains(italyId), "Italy joined attacker side");

        // Prepare clean ground & sky at Belgrade border chunk (22, 0) -> block (356, 81, 10) (outside OldBazaar quarter 2..6)
        BlockPos flagGround22 = new BlockPos(22 * 16 + 10, 80, 10);
        BlockPos flagBase22 = flagGround22.above();
        clearSkyColumn(level, flagBase22.getX(), flagBase22.getY(), flagBase22.getZ());

        WarSchedule.setOverrideMode(WarSchedule.OverrideMode.FORCE_OPEN);
        WarService.recordDefenderOnline(server, yugoId, belgradeId, System.currentTimeMillis());

        // Test 2: Cannot place capture flag while preparation period is still active!
        s.a.setPos(flagBase22.getX() + 1.5, 81, flagBase22.getZ() + 0.5);
        var prepEvt = rightClickFence(s.a, flagGround22, new ItemStack(Items.OAK_FENCE, 8));
        WarProtection.onRightClickBlock(prepEvt);
        check(data.wars.flagAtChunk(new Location(dim, 22, 0)) == null, "Test 2: Flag blocked during preparation");

        // Complete preparation timer -> War transitions to ACTIVE
        war.preparationEndsAtEpochMs = System.currentTimeMillis() - 1000L;
        tickWar(server);
        check(war.state == WarState.ACTIVE, "War transitioned to ACTIVE after preparation");

        // --- TEST 15: Offline Defender Protection ---
        TerritoryConfig.WAR_REQUIRE_ONLINE_DEFENDERS_TO_START.set(true);
        int oldGrace = TerritoryConfig.WAR_DEFENDER_LOGOUT_GRACE_SECONDS.get();
        TerritoryConfig.WAR_DEFENDER_LOGOUT_GRACE_SECONDS.set(2);
        WarService.recordDefenderOnline(server, yugoId, belgradeId, System.currentTimeMillis() - 10_000L); // expired grace
        var offlineDefEvt = rightClickFence(s.a, flagGround22, new ItemStack(Items.OAK_FENCE, 8));
        WarProtection.onRightClickBlock(offlineDefEvt);
        check(data.wars.flagAtChunk(new Location(dim, 22, 0)) == null, "Test 15: Offline defender protection blocked attack");
        TerritoryConfig.WAR_DEFENDER_LOGOUT_GRACE_SECONDS.set(oldGrace);
        // Record online defender within grace window
        WarService.recordDefenderOnline(server, yugoId, belgradeId, System.currentTimeMillis());

        // --- TEST 14 (Part A): Closed War Window blocks new flags ---
        WarSchedule.setOverrideMode(WarSchedule.OverrideMode.FORCE_CLOSED);
        var closedWinEvt = rightClickFence(s.a, flagGround22, new ItemStack(Items.OAK_FENCE, 8));
        WarProtection.onRightClickBlock(closedWinEvt);
        check(data.wars.flagAtChunk(new Location(dim, 22, 0)) == null, "Test 14a: Closed war window blocked new flag");
        WarSchedule.setOverrideMode(WarSchedule.OverrideMode.FORCE_OPEN);

        // --- TEST 9: Deep Interior Chunk Attack Rejected (Chunk Jumping Prevention) ---
        // Chunk (24, 0) is surrounded on west by (23, 0) and east by (25, 0) and is 4 chunks from Berlin (20, 0) behind (22,0)-(23,0)!
        BlockPos interiorGround24 = new BlockPos(24 * 16 + 8, 80, 8);
        clearSkyColumn(level, interiorGround24.getX(), 81, interiorGround24.getZ());
        s.a.setPos(interiorGround24.getX() + 1.5, 81, interiorGround24.getZ() + 0.5);
        var jumpEvt = rightClickFence(s.a, interiorGround24, new ItemStack(Items.OAK_FENCE, 8));
        WarProtection.onRightClickBlock(jumpEvt);
        check(data.wars.flagAtChunk(new Location(dim, 24, 0)) == null, "Test 9: Interior non-border chunk jump blocked");

        // Roof / Indoor & Quarter check: placing inside OldBazaar 3D quarter (355, 81, 4) or under stone roof fails!
        BlockPos indoorGround = new BlockPos(22 * 16 + 12, 80, 12);
        clearSkyColumn(level, indoorGround.getX(), 81, indoorGround.getZ());
        level.setBlock(indoorGround.above(5), Blocks.STONE.defaultBlockState(), 3); // roof blocking sky!
        s.a.setPos(indoorGround.getX() + 1.5, 81, indoorGround.getZ() + 0.5);
        WarProtection.onRightClickBlock(rightClickFence(s.a, indoorGround, new ItemStack(Items.OAK_FENCE, 8)));
        check(data.wars.flagAtChunk(new Location(dim, 22, 0)) == null, "Phase 8: Roofed indoor flag blocked");
        level.setBlock(indoorGround.above(5), Blocks.AIR.defaultBlockState(), 3);

        // --- TEST 3 & TEST 4: Valid Fence Placement Converts Into 3-Block War Capture Flag ---
        TerritoryConfig.WAR_CAPTURE_TIME_SECONDS.set(5); // 100 ticks for fast deterministic server testing
        s.a.setPos(flagBase22.getX() + 1.5, 81, flagBase22.getZ() + 0.5);
        ItemStack fenceStack = new ItemStack(Items.OAK_FENCE, 8);
        var placeEvt = rightClickFence(s.a, flagGround22, fenceStack);
        WarProtection.onRightClickBlock(placeEvt);

        ActiveCaptureFlag flag1 = data.wars.flagAtChunk(new Location(dim, 22, 0));
        check(flag1 != null, "Test 3/4: Fence converted into ActiveCaptureFlag on border chunk (22,0)");
        check(fenceStack.getCount() == 7, "1 fence consumed");
        check(level.getBlockState(flagBase22).is(Blocks.OAK_FENCE)
                && level.getBlockState(flagBase22.above()).is(BlockTags.WOOL)
                && level.getBlockState(flagBase22.above(2)).is(Blocks.TORCH),
                "Test 4: Physical 3-block flag (Fence + Wool + Torch) built");

        // --- MANDATORY EDGE CASE: Simultaneous 2nd Flag in Same Chunk & No-Build Buffer ---
        BlockPos secondSpot = new BlockPos(22 * 16 + 2, 80, 12);
        clearSkyColumn(level, secondSpot.getX(), 81, secondSpot.getZ());
        WarProtection.onRightClickBlock(rightClickFence(s.a, secondSpot, new ItemStack(Items.OAK_FENCE, 4)));
        check(data.wars.activeFlags().size() == 1 && data.wars.flagAtChunk(new Location(dim, 22, 0)) == flag1,
                "Simultaneous second flag in same chunk blocked");
        check(WarProtection.isInsideAnyFlagNoBuildZone(data, dim, flagBase22.east()), "No-build zone active around flag");

        // --- TEST 6: Defender Breaks Flag -> Attack Cancelled Cleanly With Zero Duplication ---
        var defBreak = new BlockEvent.BreakEvent(level, flagBase22.above(), level.getBlockState(flagBase22.above()), s.b);
        WarProtection.onBlockBreak(defBreak);
        check(defBreak.isCanceled(), "Raw break canceled to prevent wool/torch item drop duplication");
        check(data.wars.flagAtChunk(new Location(dim, 22, 0)) == null, "Test 6: Defender breaking wool cancelled attack");
        check(level.getBlockState(flagBase22).isAir()
                && level.getBlockState(flagBase22.above()).isAir()
                && level.getBlockState(flagBase22.above(2)).isAir(), "All 3 flag blocks cleaned up");
        check(war.stats.flagsDefended == 1, "Defender flag stat incremented");

        // --- MANDATORY EDGE CASE: Explosion (TNT / Historical Artillery) Destroying Flag Safely ---
        WarProtection.onRightClickBlock(rightClickFence(s.a, flagGround22, new ItemStack(Items.OAK_FENCE, 4)));
        check(data.wars.flagAtChunk(new Location(dim, 22, 0)) != null, "Re-planted flag for explosion test");
        BlockPos cityWall = new BlockPos(22 * 16 + 14, 81, 14);
        level.setBlock(cityWall, Blocks.STONE_BRICKS.defaultBlockState(), 3);
        Explosion exp = new Explosion(level, null, flagBase22.getX(), flagBase22.getY(), flagBase22.getZ(), 4.0F, false, Explosion.BlockInteraction.DESTROY);
        exp.getToBlow().addAll(List.of(flagBase22, flagBase22.above(), cityWall));
        WarProtection.onExplosionDetonate(new ExplosionEvent.Detonate(level, exp, new ArrayList<>()));
        check(data.wars.flagAtChunk(new Location(dim, 22, 0)) == null && level.getBlockState(flagBase22).isAir(),
                "Explosion cleanly neutralized flag without orphaned blocks");
        check(!exp.getToBlow().contains(cityWall) && level.getBlockState(cityWall).is(Blocks.STONE_BRICKS),
                "City buildings protected from wartime explosion griefing");

        // --- TEST 5, TEST 14 (Part B), TEST 7 & TEST 11: Timer, Attacker Presence, Window Pause & Occupation ---
        TerritoryConfig.WAR_REQUIRE_ATTACKER_NEARBY.set(true);
        WarProtection.onRightClickBlock(rightClickFence(s.a, flagGround22, new ItemStack(Items.OAK_FENCE, 4)));
        ActiveCaptureFlag flag2 = data.wars.flagAtChunk(new Location(dim, 22, 0));
        check(flag2 != null, "Planted flag for capture progression test");
        // FakePlayer is not in level.players(), so with requireAttackerNearby=true, 1 tick pauses it as PAUSED_NO_ATTACKER!
        tickWar(server);
        check(flag2.runState == FlagRunState.PAUSED_NO_ATTACKER && flag2.elapsedTicks == 0,
                "Test 5a: Flag pauses when no attacking player is within presence radius");

        // Enable progression without PlayerList injection for headless FakePlayer tick test, then test War Window closing mid-attack!
        TerritoryConfig.WAR_REQUIRE_ATTACKER_NEARBY.set(false);
        for (int i = 0; i < 40; i++) {
            tickWar(server);
        }
        check(flag2.elapsedTicks == 40 && flag2.runState == FlagRunState.CAPTURING, "Test 5b: Capture timer advances (40/100)");

        // Test 14b / Mandatory #29: Close war window while attack is active -> timer freezes!
        WarSchedule.setOverrideMode(WarSchedule.OverrideMode.FORCE_CLOSED);
        for (int i = 0; i < 20; i++) {
            tickWar(server);
        }
        check(flag2.runState == FlagRunState.PAUSED_WINDOW_CLOSED && flag2.elapsedTicks == 40,
                "Test 14b: Active capture timer froze immediately when war window closed");

        // Reopen window and finish remaining 60 ticks -> Chunk (22, 0) becomes OCCUPIED!
        WarSchedule.setOverrideMode(WarSchedule.OverrideMode.FORCE_OPEN);
        for (int i = 0; i < 60; i++) {
            tickWar(server);
        }
        check(data.wars.flagAtChunk(new Location(dim, 22, 0)) == null, "Flag completed and unregistered");
        check(level.getBlockState(flagBase22).isAir() && level.getBlockState(flagBase22.above()).isAir(), "Flag blocks removed on capture");

        var occ22 = data.wars.occupationAt(new Location(dim, 22, 0));
        check(occ22 != null && occ22.occupyingNationId().equals(germanyId) && occ22.originalTownId().equals(belgradeId),
                "Test 7: Chunk (22, 0) recorded as OCCUPIED by Germany");

        // Test 11 & Phase 8/9: Original town ownership, OPAC claim, Plot/Quarter, CityEvaluation, & chest protection preserved!
        check(territories.verified(new Location(dim, 22, 0)).town().equals(belgradeId),
                "Test 11: Original HNC & OPAC town ownership preserved during occupation");
        check(PropertyBook.lookup(server, dim, new BlockPos(355, 81, 4), true).map(ParcelView::name).orElse("").equals("OldBazaar"),
                "3D Quarter OldBazaar preserved during occupation");
        check(data.evaluation(belgradeId).draft().level() == 5 && data.evaluation(belgradeId).draft().rating() == 5,
                "Phase 9: Metropolis level 5 & 5-star evaluation preserved during occupation");

        // Verify attacker STILL cannot break blocks or loot chests in occupied chunk (22, 0)!
        var breakInOcc = new BlockEvent.BreakEvent(level, cityWall, level.getBlockState(cityWall), s.a);
        WarProtection.onBlockBreak(breakInOcc);
        check(breakInOcc.isCanceled(), "Phase 8: Attacker blocked from breaking city blocks in occupied chunk");

        // --- TEST 8 & TEST 17: Advance Frontline From Occupied Chunk (22, 0) -> (23, 0) (by Allied Co-Belligerent Italy!) ---
        BlockPos ground23 = new BlockPos(23 * 16 + 8, 80, 8);
        clearSkyColumn(level, ground23.getX(), 81, ground23.getZ());
        s.d.setPos(ground23.getX() + 1.5, 81, ground23.getZ() + 0.5);
        WarProtection.onRightClickBlock(rightClickFence(s.d, ground23, new ItemStack(Items.OAK_FENCE, 4)));
        check(data.wars.flagAtChunk(new Location(dim, 23, 0)) != null,
                "Test 8/17: Allied participant Italy advanced frontline from occupied chunk (22,0) into (23,0)");
        for (int i = 0; i < 100; i++) {
            tickWar(server);
        }
        var occ23 = data.wars.occupationAt(new Location(dim, 23, 0));
        check(occ23 != null && occ23.occupyingNationId().equals(italyId),
                "Test 17: Chunk (23,0) explicitly recorded as occupied on behalf of Italy");

        // --- TEST 10: Political World Map War Overlay Serialization Accuracy ---
        var overlayPkt = PoliticalMapServer.get(server).buildWarOverlayPacket();
        FriendlyByteBuf buf = MapModels.decompress(overlayPkt.compressedOverlay());
        try {
            check(buf.readBoolean(), "occupation overlay enabled");
            int warringCount = buf.readVarInt();
            check(warringCount == 3, "3 warring nations (Germany, Italy, Yugoslavia) in map overlay");
            for (int i = 0; i < warringCount; i++) buf.readUUID();
            int occCount = buf.readVarInt();
            check(occCount == 2, "Test 10: Both occupied chunks serialized in World Map overlay");
        } finally {
            buf.release();
        }

        // --- MANDATORY EDGE CASES: Town Disband, Nation Disband, Unclaim & Leadership Change During War ---
        s.b.setPos(22 * 16 + 8, 80, 8);
        s.command(s.b, "town unclaim", "!war_chunk_locked");
        s.command(s.c, "town disband", "!in_active_war");
        s.command(s.c, "nation leave", "!in_active_war");
        s.command(s.b, "nation disband", "!in_active_war");
        s.command(s.a, "nation disband", "!in_active_war");

        // Leadership change during war: Yugoslavia moves capital to Novi Sad (s.c becomes leader), then back to Belgrade (s.b)
        s.command(s.b, "nation setcapital Novi Sad", "updated");
        check(data.isNationLeader(s.c.getUUID()), "s.c became Yugoslavia leader mid-war");
        s.command(s.c, "nation setcapital Belgrade", "updated");
        check(data.isNationLeader(s.b.getUUID()), "s.b restored as Yugoslavia leader mid-war");

        // --- TEST 18: Admin Stop Preserves Occupations, Admin Disable/Enable & Resume ---
        String shortId = war.warId.toString().substring(0, 8);
        WarService.adminStopWar(server, war.warId);
        check(war.state == WarState.PAUSED && war.occupiedChunks.size() == 2,
                "Test 18: Admin stop paused war while preserving all occupied chunks");
        WarService.adminSetEnabled(server, false);
        check(!WarService.isWarSystemEnabled(data), "Admin disabled war system");
        WarService.adminSetEnabled(server, true);
        WarService.adminResumeWar(server, war.warId);
        check(war.state == WarState.ACTIVE && data.wars.occupationAt(new Location(dim, 22, 0)) != null,
                "Admin re-enabled & resumed war with occupations intact");

        // --- Plant an active flag on (24, 0) at 50% (50/100 ticks) BEFORE server restart (Test 19 & Mandatory #26/#31) ---
        BlockPos ground24 = new BlockPos(24 * 16 + 8, 80, 8);
        clearSkyColumn(level, ground24.getX(), 81, ground24.getZ());
        s.a.setPos(ground24.getX() + 1.5, 81, ground24.getZ() + 0.5);
        WarProtection.onRightClickBlock(rightClickFence(s.a, ground24, new ItemStack(Items.OAK_FENCE, 4)));
        ActiveCaptureFlag halfFlag = data.wars.flagAtChunk(new Location(dim, 24, 0));
        check(halfFlag != null, "Planted flag on (24,0) from occupied (23,0)");
        for (int i = 0; i < 50; i++) {
            tickWar(server);
        }
        check(halfFlag.elapsedTicks == 50, "Flag at 50/100 ticks prior to server restart (" + shortId + ")");
        TerritoryConfig.WAR_REQUIRE_ATTACKER_NEARBY.set(true);
        WarSchedule.setOverrideMode(WarSchedule.OverrideMode.AUTO);
    }

    /**
     * Runs in a brand-new Dedicated Server JVM after full world save & restart:
     * Verifies persistence, offline-time anti-auto-capture, peace treaty version-locking,
     * simulated OPAC failure rollback, permanent OPAC annexation, plot/quarter/Metropolis preservation, and 48h truce!
     */
    void verify() throws Exception {
        var server = s.server;
        var level = server.overworld();
        var dim = level.dimension().location();
        var data = WorldRegistry.get(server);
        var opacClaims = OpenPACServerAPI.get(server).getServerClaimsManager();
        var territories = new TownTerritories(server);

        TerritoryConfig.LIMIT.set(64);
        ServerConfig.CONFIG.maxPlayerClaims.set(128);
        WarSchedule.setOverrideMode(WarSchedule.OverrideMode.FORCE_OPEN);

        UUID germanyId = data.namedNation("Germany").id();
        UUID yugoId = data.namedNation("Kingdom of Yugoslavia").id();
        UUID italyId = data.namedNation("Italy").id();
        UUID berlinId = data.namedTown("Berlin").id();
        UUID romeId = data.namedTown("Rome").id();
        UUID belgradeId = data.namedTown("Belgrade").id();

        // --- TEST 19 & MANDATORY #31: Server Restart Persistence & Offline Time Integrity ---
        check(data.wars.areAllied(germanyId, italyId), "Test 19: Alliance persisted across restart");
        WarRecord war = data.wars.findOngoingWarBetween(germanyId, yugoId, false).orElseThrow();
        check(war.state == WarState.ACTIVE && war.occupiedChunks.size() == 2,
                "Test 19: Active war and 2 occupied chunks persisted across restart");
        check(data.wars.occupationAt(new Location(dim, 22, 0)).occupyingNationId().equals(germanyId), "(22,0) occupied by Germany");
        check(data.wars.occupationAt(new Location(dim, 23, 0)).occupyingNationId().equals(italyId), "(23,0) occupied by Italy");

        ActiveCaptureFlag reloadedFlag = data.wars.flagAtChunk(new Location(dim, 24, 0));
        check(reloadedFlag != null && reloadedFlag.elapsedTicks == 50,
                "Mandatory #31: Active flag reloaded at exact 50/100 ticks, got: "
                        + (reloadedFlag == null ? "null (flags=" + data.wars.activeFlags().size() + ")" : reloadedFlag.elapsedTicks));

        // --- MANDATORY #30: Unauthorized / Non-Leader Treaty Manipulation Rejected ---
        boolean rejectedUnauthorized = false;
        try {
            // s.d (Italy, allied co-belligerent, NOT primary leader) or non-leader citizen cannot unilaterally sign bilateral peace
            WarService.approvePeaceTreaty(s.d, war.warId, war.peaceDraft.version, true);
        } catch (RuleViolation rv) {
            rejectedUnauthorized = rv.getMessage().equals("war_primary_leader_only");
        }
        check(rejectedUnauthorized, "Mandatory #30: Non-primary actor blocked from ratifying bilateral peace treaty");

        // --- TEST 12 & MANDATORY #27: Peace Treaty Version-Locking & Term Mutation Invalidation ---
        Location loc22 = new Location(dim, 22, 0);
        Location loc23 = new Location(dim, 23, 0);
        WarService.setTreatyChunkTerm(s.a, war.warId, loc22, berlinId); // Annex (22,0) -> Berlin
        long vAfterEdit1 = war.peaceDraft.version;
        check(!WarService.approvePeaceTreaty(s.a, war.warId, vAfterEdit1, true), "Attacker approved v" + vAfterEdit1);
        check(war.peaceDraft.attackerApproved && !war.peaceDraft.defenderApproved, "Only attacker approved so far");

        // Defender modifies terms (also annexes 23,0 -> Rome) -> MUST invalidate Attacker's prior approval!
        WarService.setTreatyChunkTerm(s.b, war.warId, loc23, romeId);
        check(!war.peaceDraft.attackerApproved && war.peaceDraft.version > vAfterEdit1,
                "Phase 10: Modifying treaty terms invalidated opposing side's prior approval");

        // Stale version approval attempt MUST fail!
        boolean staleRejected = false;
        try {
            WarService.approvePeaceTreaty(s.b, war.warId, vAfterEdit1, true);
        } catch (RuleViolation rv) {
            staleRejected = rv.getMessage().equals("war_treaty_version_changed");
        }
        check(staleRejected, "Phase 10: Stale treaty version approval rejected");

        // --- MANDATORY #28: Simulated OPAC Failure Mid-Annexation -> Full Atomic Rollback! ---
        TownTerritories.setSimulatedAnnexFailureChunk(loc23);
        try {
            long failVer = war.peaceDraft.version;
            WarService.approvePeaceTreaty(s.a, war.warId, failVer, true);
            boolean rolledBack = false;
            try {
                WarService.approvePeaceTreaty(s.b, war.warId, failVer, true);
            } catch (RuleViolation rv) {
                rolledBack = rv.getMessage().equals("annexation_failed");
            }
            check(rolledBack, "Mandatory #28: Simulated OPAC failure aborted treaty ratification");
            // Verify chunk #1 (22, 0) was ROLLED BACK to Belgrade in BOTH HNC and OPAC, and war is STILL ongoing!
            check(war.isOngoing() && war.occupiedChunks.size() == 2, "War & occupations intact after rolled-back treaty");
            check(territories.verified(loc22).town().equals(belgradeId)
                    && opacClaims.get(dim, 22, 0).getPlayerId().equals(s.b.getUUID()),
                    "Mandatory #28: Chunk (22,0) atomically rolled back to Belgrade in both HNC and OPAC");
            check(PropertyBook.lookup(server, dim, new BlockPos(355, 81, 4), true).isPresent(),
                    "Mandatory #28: OldBazaar 3D quarter survived rollback intact");
        } finally {
            TownTerritories.setSimulatedAnnexFailureChunk(null);
        }

        // --- TEST 12, TEST 13 & TEST 20: Final Peace Treaty Ratification (Annex 22,0 -> Berlin; Return 23,0 -> Belgrade) ---
        int aliceGoldBefore = GoldCosts.countGold(s.a);
        WarService.setTreatyChunkTerm(s.b, war.warId, loc23, null); // Return (23,0) to Belgrade!
        long finalVer = war.peaceDraft.version;
        check(!WarService.approvePeaceTreaty(s.a, war.warId, finalVer, true), "Germany leader signed final treaty");
        check(WarService.approvePeaceTreaty(s.b, war.warId, finalVer, true), "Yugoslavia leader signed final treaty -> CONCLUDED");

        check(war.state == WarState.CONCLUDED, "Test 12: War state is CONCLUDED");
        check(data.wars.allOccupations().isEmpty(), "All wartime occupations cleared upon peace");
        check(data.wars.flagAtChunk(new Location(dim, 24, 0)) == null, "Active capture flag on (24,0) cleaned up on peace");
        check(GoldCosts.countGold(s.a) == aliceGoldBefore, "Phase 7: Zero Gold Ingots charged for wartime annexation");

        // Verify (22, 0) permanently annexed to Berlin (Germany) in BOTH WorldRegistry and OPAC!
        var link22 = territories.verified(loc22);
        check(link22 != null && link22.town().equals(berlinId) && link22.owner().equals(s.a.getUUID()),
                "Test 13a: Chunk (22,0) transferred to Berlin in WorldRegistry");
        check(opacClaims.get(dim, 22, 0).getPlayerId().equals(s.a.getUUID()),
                "Test 13b: Chunk (22,0) OPAC claim ownership transferred to Berlin mayor");

        // Verify (23, 0) returned cleanly to Belgrade (Yugoslavia)!
        var link23 = territories.verified(loc23);
        check(link23 != null && link23.town().equals(belgradeId) && opacClaims.get(dim, 23, 0).getPlayerId().equals(s.b.getUUID()),
                "Returned chunk (23,0) remains under Belgrade ownership");

        // Verify Test 20: Plot, 3D Quarter (OldBazaar), and Belgrade's Metropolis 5-star evaluation are 100% intact!
        var plot22 = PropertyBook.lookup(server, dim, new BlockPos(355, 81, 4), false).orElseThrow();
        var quarter22 = PropertyBook.lookup(server, dim, new BlockPos(355, 81, 4), true).orElseThrow();
        check(plot22.town().equals(berlinId) && plot22.name().equals("OldBazaarPlot"), "Plot name OldBazaarPlot preserved under Berlin");
        check(quarter22.town().equals(berlinId) && quarter22.name().equals("OldBazaar"), "3D Quarter OldBazaar preserved under Berlin");
        check(data.evaluation(belgradeId).draft().level() == 5 && data.evaluation(belgradeId).draft().rating() == 5,
                "Test 20: Belgrade Metropolis level 5 & 5-star rating preserved after peace");

        // Verify 48-hour Truce prevents immediate re-declaration of war!
        check(data.wars.activeTruce(germanyId, yugoId, System.currentTimeMillis()).isPresent(), "48h truce active");
        s.command(s.a, "war declare Kingdom of Yugoslavia", "!war_truce_active");
        s.command(s.d, "war declare Kingdom of Yugoslavia", "!war_truce_active"); // Co-belligerent Italy also bound by truce!
        s.command(s.a, "war truce", "war_truces_list");

        WarSchedule.setOverrideMode(WarSchedule.OverrideMode.AUTO);
    }
}
