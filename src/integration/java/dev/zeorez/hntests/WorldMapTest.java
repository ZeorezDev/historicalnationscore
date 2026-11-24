package dev.zeorez.hntests;

import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.core.GoldCosts;
import dev.zeorez.historicalnations.core.Territory;
import dev.zeorez.historicalnations.core.WorldRegistry;
import dev.zeorez.historicalnations.map.AnvilTileGenerator;
import dev.zeorez.historicalnations.map.MapCalibration;
import dev.zeorez.historicalnations.map.MapModels;
import dev.zeorez.historicalnations.map.MapModels.DeltaPayload;
import dev.zeorez.historicalnations.map.NationPalette;
import dev.zeorez.historicalnations.map.PoliticalMapServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.config.ServerConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static dev.zeorez.hntests.PrototypeServerTest.check;

/**
 * Dedicated-server verification for Historical Nations Core v9.0.0 Political World Map:
 * - MOTFE 1:500 calibration & exact block/chunk coordinates
 * - Real Anvil (.mca) -> PNG pyramid (z0..z7) off-thread generation into D:\HeartsOfSteel\MapTiles
 * - OPAC town claim verification vs personal OPAC claim exclusion & unclaimed gap preservation
 * - Nation colors, custom hex color, rename, capital transfer, and incremental DeltaPayload accuracy
 * - 25,000-claim / 1,000-town / 200-nation compression & zero-chunk-load performance stress check
 * - Server restart persistence (mapId, colors, claims, capitals)
 */
final class WorldMapTest {
    private final PrototypeServerTest.Suite s;

    WorldMapTest(PrototypeServerTest.Suite suite) {
        this.s = suite;
    }

    void setup() throws Exception {
        var server = s.server;
        var data = WorldRegistry.get(server);
        var mapServer = PoliticalMapServer.get(server);
        var dim = Level.OVERWORLD.location();
        var opacClaims = OpenPACServerAPI.get(server).getServerClaimsManager();

        // 1. Verify default v9.0.0 config & 1:500 MOTFE Earth calibration math
        check(TerritoryConfig.MAP_ENABLED.get(), "politicalMap.enabled default true");
        check(TerritoryConfig.EARTH_SCALE.get() == 500, "earthMap.scale default 500");
        check(TerritoryConfig.EARTH_MIN_BLOCK_X.get() == -36864 && TerritoryConfig.EARTH_MAX_BLOCK_X.get() == 36863, "1:500 X bounds");
        check(TerritoryConfig.EARTH_MIN_BLOCK_Z.get() == -18432 && TerritoryConfig.EARTH_MAX_BLOCK_Z.get() == 18431, "1:500 Z bounds");
        check(Math.abs(TerritoryConfig.EARTH_BLOCKS_PER_DEGREE.get() - 204.8D) < 0.001D, "204.8 blocks/degree");

        MapCalibration calibrated = new MapCalibration(500, dim, true, true,
                -36864, 36863, -18432, 18431, 0, 0, 204.8D);
        // Verify Belgrade (~44.81N, 20.46E) and user prompt example (7773, -8037)
        check(Math.abs(calibrated.longitudeToBlockX(20.46D) - 4190) <= 1, "Belgrade X calibration");
        check(Math.abs(calibrated.latitudeToBlockZ(44.81D) - (-9177)) <= 1, "Belgrade Z calibration");
        check(calibrated.formatGeo(7773, -8037).equals("39.24°N, 37.95°E"), "7773 -8037 geo readout");

        int oldTownLimit = TerritoryConfig.LIMIT.get();
        int oldOpacLimit = ServerConfig.CONFIG.maxPlayerClaims.get();
        TerritoryConfig.LIMIT.set(64);
        ServerConfig.CONFIG.maxPlayerClaims.set(128);
        try {
            // 2. Found Belgrade (a), Kumanovo (b), Novi Sad (c) under Kingdom of Yugoslavia, plus Independent Town (d)
            // Keep deliberate unclaimed gap chunks between Belgrade (chunk 10, -20) and Kumanovo (chunk 14, -20)!
            GoldCosts.giveGold(s.a, 135);
            s.a.setPos(10 * 16 + 8, 80, -20 * 16 + 8); // Belgrade at chunk (10, -20)
            s.command(s.a, "town create Belgrade", "town_created");
            s.a.setPos(11 * 16 + 8, 80, -20 * 16 + 8); // Belgrade second chunk (11, -20)
            s.command(s.a, "town claim", "land_claimed");
            s.command(s.a, "nation create Kingdom of Yugoslavia", "nation_created");
            s.command(s.a, "nation color #B83A3A", "updated");

            GoldCosts.giveGold(s.b, 5);
            s.b.setPos(14 * 16 + 8, 80, -20 * 16 + 8); // Kumanovo at chunk (14, -20) — chunks 12 & 13 left UNCLAIMED!
            s.command(s.b, "town create Kumanovo", "town_created");
            s.b.setPos(14 * 16 + 8, 80, -19 * 16 + 8); // Kumanovo second chunk (14, -19)
            s.command(s.b, "town claim", "land_claimed");

            GoldCosts.giveGold(s.c, 5);
            s.c.setPos(11 * 16 + 8, 80, -21 * 16 + 8); // Novi Sad at (11, -21), directly adjacent to Belgrade (11, -20)!
            s.command(s.c, "town create Novi Sad", "town_created");

            s.command(s.a, "nation invite Kumanovo", "invited");
            s.command(s.b, "nation accept Kingdom of Yugoslavia", "nation_joined");
            s.command(s.a, "nation invite Novi Sad", "invited");
            s.command(s.c, "nation accept Kingdom of Yugoslavia", "nation_joined");

            // Independent town (Dubrovnik) not in any nation
            GoldCosts.giveGold(s.d, 5);
            s.d.setPos(6 * 16 + 8, 80, -18 * 16 + 8); // chunk (6, -18)
            s.command(s.d, "town create Dubrovnik", "town_created");

            // Create a personal OPAC claim at (12, -20) (between Belgrade & Kumanovo) — MUST NOT appear as political territory!
            check(opacClaims.claim(dim, s.a.getUUID(), 0, 12, -20, false) != null, "personal OPAC claim fixture");

            // 3. Capture authoritative snapshot without forcing chunk loads
            int loadedBefore = server.overworld().getChunkSource().getLoadedChunksCount();
            var snap1 = mapServer.ensureSnapshot();
            int loadedAfter = server.overworld().getChunkSource().getLoadedChunksCount();
            check(loadedAfter <= loadedBefore, "map snapshot must NEVER force-load world chunks");

            UUID yugoId = data.namedNation("Kingdom of Yugoslavia").id();
            UUID belgradeId = data.namedTown("Belgrade").id();
            UUID kumanovoId = data.namedTown("Kumanovo").id();
            UUID noviSadId = data.namedTown("Novi Sad").id();
            UUID dubrovnikId = data.namedTown("Dubrovnik").id();

            var yugo = snap1.nations().get(yugoId);
            check(yugo != null && yugo.colorRgb() == 0xB83A3A && yugo.townCount() == 3 && yugo.claimCount() == 5,
                    "Yugoslavia color, 3 towns, 5 verified chunks");
            check(snap1.claims().get(MapModels.packChunk(10, -20)).equals(belgradeId), "Belgrade chunk (10,-20)");
            check(snap1.claims().get(MapModels.packChunk(14, -20)).equals(kumanovoId), "Kumanovo chunk (14,-20)");
            check(snap1.claims().get(MapModels.packChunk(11, -21)).equals(noviSadId), "Novi Sad chunk (11,-21)");
            check(snap1.claims().get(MapModels.packChunk(6, -18)).equals(dubrovnikId), "Independent Dubrovnik chunk");
            // Unclaimed gap (13,-20) and personal OPAC claim (12,-20) MUST be absent from political claims!
            check(!snap1.claims().containsKey(MapModels.packChunk(13, -20)), "unclaimed gap excluded");
            check(!snap1.claims().containsKey(MapModels.packChunk(12, -20)), "personal OPAC claim excluded");
            check(snap1.towns().get(dubrovnikId).nationId() == null, "Dubrovnik independent");

            // 4. Live Delta tests: Unclaim, Town leaves nation, Capital change, Rename, Color change
            s.b.setPos(14 * 16 + 8, 80, -19 * 16 + 8);
            s.command(s.b, "town unclaim", "land_released");
            var snapAfterUnclaim = mapServer.ensureSnapshot();
            DeltaPayload dUnclaim = PoliticalMapServer.computeDelta(snap1, snapAfterUnclaim);
            check(dUnclaim.changedClaims().size() == 1
                    && dUnclaim.changedClaims().get(0).chunkX() == 14
                    && dUnclaim.changedClaims().get(0).chunkZ() == -19
                    && dUnclaim.changedClaims().get(0).townId() == null, "unclaim delta exact");

            s.command(s.b, "nation leave", "nation_left");
            var snapAfterLeave = mapServer.ensureSnapshot();
            DeltaPayload dLeave = PoliticalMapServer.computeDelta(snapAfterUnclaim, snapAfterLeave);
            check(dLeave.upsertedTowns().stream().anyMatch(t -> t.id().equals(kumanovoId) && t.nationId() == null),
                    "town leave nation delta");

            // Rejoin Kumanovo, test setcapital + color + rename commands
            s.command(s.a, "nation invite Kumanovo", "invited");
            s.command(s.b, "nation accept Kingdom of Yugoslavia", "nation_joined");
            s.command(s.a, "nation setcapital Novi Sad", "updated");
            check(data.namedNation("Kingdom of Yugoslavia").capitalTown().equals(noviSadId), "capital moved to Novi Sad");
            // Novi Sad's mayor (c) is now Nation Leader! Move capital back to Belgrade and customize color.
            s.command(s.c, "nation setcapital Belgrade", "updated");
            s.command(s.a, "nation color #C43E3E", "updated");
            s.command(s.d, "town rename Ragusa", "updated");
            s.command(s.d, "town rename Dubrovnik", "updated");

            // 5. Real Anvil (.mca) -> PNG Pyramid test on D:\HeartsOfSteel\MapTiles (using saved spawn region!)
            server.saveEverything(true, true, true);
            Path regionDir = server.getWorldPath(LevelResource.ROOT).resolve("region");
            Path testTileDir = Path.of("D:/HeartsOfSteel/MapTiles/test_v900");
            int rendered = AnvilTileGenerator.renderWorldRegions(regionDir, testTileDir, calibrated, 2);
            check(rendered >= 1, "rendered at least 1 real .mca region into PNG pyramid");
            Path dimFolder = testTileDir.resolve(MapCalibration.dimensionFolder(dim));
            check(Files.isRegularFile(testTileDir.resolve("calibration.json")), "calibration.json written");
            for (int lod = 0; lod <= MapCalibration.MAX_LOD; lod++) {
                try (var files = Files.list(dimFolder.resolve("z" + lod))) {
                    check(files.anyMatch(p -> p.toString().endsWith(".png")), "PNG tile generated at z" + lod);
                }
            }

            // 6. Scale & Compression Stress Benchmark (200 Nations, 1,000 Towns, 25,000 Claims) on an isolated registry
            verifyLargeScalePerformance();
        } finally {
            TerritoryConfig.LIMIT.set(oldTownLimit);
            ServerConfig.CONFIG.maxPlayerClaims.set(oldOpacLimit);
        }
    }

    private void verifyLargeScalePerformance() {
        // Build a synthetic 25,000-claim DeltaPayload & verify compressed wire size + decode speed
        var nations = new java.util.ArrayList<MapModels.NationSummary>(200);
        var towns = new java.util.ArrayList<MapModels.TownSummary>(1000);
        var claims = new java.util.ArrayList<MapModels.ClaimEntry>(25_000);

        for (int n = 0; n < 200; n++) {
            UUID nid = new UUID(1000L, n);
            UUID capId = new UUID(2000L, n * 5L);
            int col = NationPalette.pickDistinct(nations.stream().map(MapModels.NationSummary::colorRgb).toList(), nid);
            nations.add(new MapModels.NationSummary(nid, "Nation_" + n, capId, "Leader_" + n, col, 5, 25, 125, "TRY"));
            for (int t = 0; t < 5; t++) {
                int ti = n * 5 + t;
                UUID tid = new UUID(2000L, ti);
                int baseCx = (ti % 50) * 20 - 500;
                int baseCz = (ti / 50) * 20 - 200;
                towns.add(new MapModels.TownSummary(tid, "Town_" + ti, "Mayor_" + ti, nid, 5, 25, baseCx * 16 + 8, baseCz * 16 + 8, true));
                for (int dx = 0; dx < 5; dx++) {
                    for (int dz = 0; dz < 5; dz++) {
                        claims.add(new MapModels.ClaimEntry(baseCx + dx, baseCz + dz, tid));
                    }
                }
            }
        }
        check(claims.size() == 25_000, "25,000 synthetic claims prepared");
        long t0 = System.nanoTime();
        DeltaPayload bulk = new DeltaPayload(nations, java.util.List.of(), towns, java.util.List.of(), claims);
        byte[] compressed = bulk.encodeCompressed();
        DeltaPayload decoded = DeltaPayload.decodeCompressed(compressed);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        check(compressed.length < MapModels.MAX_COMPRESSED_PACKET_BYTES,
                "25k claims + 1k towns + 200 nations compressed to " + compressed.length + " bytes");
        check(decoded.changedClaims().size() == 25_000 && decoded.upsertedTowns().size() == 1000
                && decoded.upsertedNations().size() == 200, "lossless decompression of 25k claims");
        check(elapsedMs < 1500L, "25k claim codec completed in " + elapsedMs + " ms");
    }

    /** Second server JVM process: verifies persistence of mapId, custom nation colors, capitals, and claims. */
    void verify() {
        var data = WorldRegistry.get(s.server);
        var snap = PoliticalMapServer.get(s.server).ensureSnapshot();

        var yugo = data.namedNation("Kingdom of Yugoslavia");
        var belgrade = data.namedTown("Belgrade");
        var kumanovo = data.namedTown("Kumanovo");
        var noviSad = data.namedTown("Novi Sad");
        var dubrovnik = data.namedTown("Dubrovnik");

        check(snap.mapId().equals(data.mapId()), "mapId persisted");
        check(data.nationColor(yugo.id()) == 0xC43E3E && snap.nations().get(yugo.id()).colorRgb() == 0xC43E3E,
                "custom nation color #C43E3E persisted across restart");
        check(yugo.capitalTown().equals(belgrade.id()), "capital persisted");
        check(snap.nations().get(yugo.id()).townCount() == 3 && snap.nations().get(yugo.id()).claimCount() == 4,
                "Yugoslavia 3 towns & 4 claims persisted after restart");
        check(snap.claims().get(MapModels.packChunk(10, -20)).equals(belgrade.id())
                && snap.claims().get(MapModels.packChunk(14, -20)).equals(kumanovo.id())
                && snap.claims().get(MapModels.packChunk(11, -21)).equals(noviSad.id())
                && snap.claims().get(MapModels.packChunk(6, -18)).equals(dubrovnik.id()),
                "all town territories persisted");
        check(!snap.claims().containsKey(MapModels.packChunk(14, -19)), "unclaimed chunk stayed removed");
        check(!snap.claims().containsKey(MapModels.packChunk(12, -20)), "personal OPAC claim still excluded on restart");
    }
}
