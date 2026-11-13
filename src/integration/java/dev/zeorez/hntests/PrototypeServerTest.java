package dev.zeorez.hntests;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.api.HistoricalNationsApi;
import dev.zeorez.historicalnations.TerritoryConfig;
import dev.zeorez.historicalnations.core.Territory.Location;
import dev.zeorez.historicalnations.core.WorldRegistry;
import net.minecraft.core.Direction;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.config.ServerConfig;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Test-only Forge mod. Runs only when explicitly invoked in a disposable server. */
@Mod("hn_integration_tests")
public final class PrototypeServerTest {
    private boolean ran;
    private boolean saleOffered;
    private int saleTick;

    public PrototypeServerTest() {
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(TickEvent.ServerTickEvent event) {
        String phase = System.getProperty("hn.test.phase", "");
        MinecraftServer server = event.getServer();
        if (ran || phase.isEmpty() || event.phase != TickEvent.Phase.END || server.getTickCount() < 40) return;
        if (phase.equals("network")) {
            var a = server.getPlayerList().getPlayerByName("HnNetA");
            var b = server.getPlayerList().getPlayerByName("HnNetB");
            var api = HistoricalNationsApi.get(server);
            if (a != null && b != null && api.isTownMayor(a.getUUID())
                    && api.getPlayerTown(a.getUUID()).equals(api.getPlayerTown(b.getUUID()))) {
                try {
                    var suite = new Suite(server);
                    var apiMoney = dev.zeorez.historicalnations.api.EconomyApi.get(server);
                    var town = api.getPlayerTown(a.getUUID()).orElseThrow();
                    if (!saleOffered) {
                        suite.command(a,"town claim","land_claimed");
                        suite.command(a,"plot forsale 15","updated");
                        b.teleportTo(a.serverLevel(), a.getX(), a.getY(), a.getZ(), 0, 0);
                        EconomyTest.operator(server,"give player HnNetA 20 race");
                        EconomyTest.operator(server,"give player HnNetB 20 race");
                        saleOffered = true;
                        saleTick = server.getTickCount();
                        for (var bidder : java.util.List.of(a,b))
                            bidder.sendSystemMessage(Component.literal("HN_TEST_BID"));
                        return;
                    }
                    // Each client acknowledges its claim packet with a one-unit deposit on the same connection.
                    if (apiMoney.getTownBalance(town.id()) != 17 && server.getTickCount() - saleTick < 1200) return;
                    var title = dev.zeorez.historicalnations.api.Parcels.plot(server,a.level().dimension().location(),a.blockPosition()).orElseThrow();
                    check(title.price() == null && (a.getUUID().equals(title.owner()) || b.getUUID().equals(title.owner())), "race winner");
                    check(apiMoney.getTownBalance(town.id()) == 17 && apiMoney.getBalance(title.owner()) == 4
                            && apiMoney.getBalance(title.owner().equals(a.getUUID()) ? b.getUUID() : a.getUUID()) == 19, "race charged exactly once");
                    suite.command(a,"town unclaim","land_released");
                    suite.networkTerritory(a, b);
                    LogUtils.getLogger().info("HN INTEGRATION PASS network");
                } catch (Exception | AssertionError error) {
                    LogUtils.getLogger().error("HN INTEGRATION FAIL network", error);
                }
            } else if (server.getTickCount() < 12000) {
                return;
            } else {
                LogUtils.getLogger().error("HN INTEGRATION FAIL network: timeout");
            }
            ran = true;
            server.halt(false);
            return;
        }
        ran = true;
        try {
            var suite = new Suite(server);
            switch (phase) {
                case "setup" -> suite.setup();
                case "verify" -> suite.verify();
                case "territorySetup" -> suite.territorySetup();
                case "territoryVerify" -> suite.territoryVerify();
                case "municipalSetup" -> new MunicipalTest(suite).setup();
                case "municipalVerify" -> new MunicipalTest(suite).verify();
                case "boundaries" -> new MunicipalTest(suite).boundaries();
                case "economySetup" -> new EconomyTest(suite).setup();
                case "economyVerify" -> new EconomyTest(suite).verify();
                default -> throw new IllegalArgumentException("Unknown test phase " + phase);
            }
            LogUtils.getLogger().info("HN INTEGRATION PASS {}", phase);
        } catch (Exception | AssertionError error) {
            LogUtils.getLogger().error("HN INTEGRATION FAIL " + phase, error);
        } finally {
            server.halt(false);
        }
    }

    private static final class Output implements CommandSource {
        Component last;
        public void sendSystemMessage(Component message) { last = message; }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }

    static final class Suite {
        final MinecraftServer server;
        final HistoricalNationsApi api;
        final FakePlayer a, b, c, d;

        Suite(MinecraftServer server) {
            this.server = server;
            api = HistoricalNationsApi.get(server);
            a = player("HnAlice");
            b = player("HnBora");
            c = player("HnCem");
            d = player("HnDeniz");
        }
        FakePlayer player(String name) {
            var id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            var profile = new GameProfile(id, name);
            server.getProfileCache().add(profile);
            return new FakePlayer(server.overworld(), profile);
        }

        TranslatableContents command(ServerPlayer player, String command, String resultKey) throws CommandSyntaxException {
            var output = new Output();
            var source = player.createCommandSourceStack().withSource(output).withPermission(0);
            Component result;
            boolean error = resultKey.startsWith("!");
            try {
                int count = server.getCommands().getDispatcher().execute(command, source);
                check(count == 1 && !error, "Unexpected success: " + command);
                result = output.last;
            } catch (CommandSyntaxException rejected) {
                if (!error || !(rejected.getRawMessage() instanceof Component message)) throw rejected;
                result = message;
                resultKey = resultKey.substring(1);
            }
            check(result != null && result.getContents() instanceof TranslatableContents, "No translated result: " + command);
            var translated = (TranslatableContents) result.getContents();
            String expected = resultKey.startsWith("gui.") ? resultKey : "historical_nations." + resultKey;
            check(translated.getKey().equals(expected), command + " returned " + translated.getKey());
            return translated;
        }

        void networkTerritory(ServerPlayer mayor, ServerPlayer citizen) throws CommandSyntaxException {
            var opac = OpenPACServerAPI.get(server);
            var parties = opac.getPartyManager();
            command(citizen, "town leave", "town_left");
            var party = parties.createPartyForOwner(citizen);
            check(party != null, "Personal OPAC party setup");
            command(citizen, "town create Sinop", "town_created");
            command(mayor, "town claim", "land_claimed");
            checkBuild(mayor, citizen, true);
            command(citizen, "town disband", "town_disbanded");
            command(mayor, "town invite HnNetB", "invited");
            command(citizen, "town accept Bursa", "town_joined");
            checkBuild(mayor, citizen, false);
            new MunicipalTest(this).network(mayor,citizen);
            command(citizen, "town claim", "!mayor_only");
            command(citizen, "town unclaim", "!mayor_only");
            command(mayor, "town setmayor HnNetB", "!land_transfer");
            check(api.isTownMayor(mayor.getUUID()), "Mayor changed after rejected transfer");
            command(citizen, "town leave", "town_left");
            checkBuild(mayor, citizen, true);
            check(parties.getPartyByMember(citizen.getUUID()) == party, "Personal party changed");
            command(mayor, "town unclaim", "land_released");
            checkBuild(mayor, citizen, false);
        }

        void checkBuild(ServerPlayer mayor, ServerPlayer visitor, boolean blocked) {
            var world = mayor.serverLevel();
            var pos = mayor.blockPosition().below();
            world.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            var protection = OpenPACServerAPI.get(server).getChunkProtection();
            check(protection.onBlockInteraction(visitor, InteractionHand.MAIN_HAND, ItemStack.EMPTY, world,
                    pos, Direction.UP, true, false, true) == blocked, "OPAC breaking, expected blocked=" + blocked);
            check(protection.onEntityPlaceBlock(Blocks.STONE.defaultBlockState(), visitor, world, pos.above()) == blocked,
                    "OPAC placement, expected blocked=" + blocked);
        }

        void territorySetup() throws CommandSyntaxException {
            var dim = Level.OVERWORLD.location();
            var claims = OpenPACServerAPI.get(server).getServerClaimsManager();
            command(a, "town create Ankara", "town_created");
            command(b, "town create Izmir", "town_created");
            command(b, "nation create Ege", "nation_created");
            a.setPos(-17, 80, 33);
            command(a, "town claim", "land_claimed");
            var primary = claims.get(dim, -2, 2);
            check(primary != null && primary.getPlayerId().equals(a.getUUID()), "Missing physical claim");
            command(a, "town claim", "!land_occupied");
            b.setPos(-17, 80, 33);
            command(b, "town claim", "!land_occupied");
            command(b, "town unclaim", "!land_not_yours");
            command(b, "nation invite Ankara", "invited");
            command(a, "nation accept Ege", "nation_joined");
            command(a, "nation leave", "nation_left");
            command(b, "nation invite Ankara", "invited");
            command(a, "nation accept Ege", "nation_joined");
            command(b, "nation disband", "nation_disbanded");
            check(claims.get(dim, -2, 2) == primary, "Nation membership changed physical claim");
            var netherMayor = new FakePlayer(server.getLevel(Level.NETHER), a.getGameProfile());
            netherMayor.setPos(-17, 80, 33);
            command(netherMayor, "town claim", "land_claimed");
            checkCount(a, 2);
            a.setPos(-33, 80, 33);
            int townLimit = TerritoryConfig.LIMIT.get();
            check(townLimit == 16, "Unexpected default town limit");
            int nativeLimit = ServerConfig.CONFIG.maxPlayerClaims.get();
            try {
                TerritoryConfig.LIMIT.set(2);
                command(a, "town claim", "!land_limit");
                TerritoryConfig.LIMIT.set(townLimit);
                ServerConfig.CONFIG.maxPlayerClaims.set(2);
                command(a, "town claim", "!gui.xaero_claims_claim_limit_reached");
                check(claims.get(dim, -3, 2) == null, "Limit rejection wrote a claim");
            } finally {
                TerritoryConfig.LIMIT.set(townLimit);
                ServerConfig.CONFIG.maxPlayerClaims.set(nativeLimit);
            }
            var config = OpenPACServerAPI.get(server).getPlayerConfigManager().getLoadedConfig(a.getUUID());
            config.tryToSet(PlayerConfigOptions.ALLOWED_TO_CLAIM, false);
            try {
                command(a, "town claim", "!gui.xaero_claims_not_allowed_in_config");
                check(claims.get(dim, -3, 2) == null, "OPAC permission rejection wrote a claim");
            } finally {
                config.tryToSet(PlayerConfigOptions.ALLOWED_TO_CLAIM, true);
            }
            command(a, "town claim", "land_claimed");
            // Simulate a native unclaim and a personal reclaim at the same location.
            var removed = claims.tryToUnclaim(dim, a.getUUID(), dim, -3, 2, -3, 2, false);
            check(removed.getResultType() == xaero.pac.common.claims.result.api.ClaimResult.Type.SUCCESSFUL_UNCLAIM, "External unclaim");
            check(claims.claim(dim, a.getUUID(), primary.getSubConfigIndex(), -3, 2, false) != null, "Personal reclaim");
            checkCount(a, 2);
            check(claims.claim(dim, b.getUUID(), 0, -4, 2, false) != null, "Foreign fixture claim");
            a.setPos(-49, 80, 33);
            command(a, "town claim", "!land_occupied");
            // Persist a stale city relation over another player's real claim to exercise startup repair.
            var registry = WorldRegistry.get(server);
            registry.link(new Location(dim, -4, 2), registry.territory(new Location(dim, -2, 2)));
        }

        void territoryVerify() throws CommandSyntaxException {
            var dim = Level.OVERWORLD.location();
            var claims = OpenPACServerAPI.get(server).getServerClaimsManager();
            check(WorldRegistry.get(server).territory(new Location(dim, -4, 2)) == null, "Startup left stale link");
            checkCount(a, 2);
            a.setPos(-17, 80, 33);
            var info = command(a, "town claiminfo", "land_info");
            check(((Component) info.getArgs()[3]).getString().equals("Ankara"), "Restart lost town identity");
            checkClaim(a, "Ankara");
            var netherMayor = new FakePlayer(server.getLevel(Level.NETHER), a.getGameProfile());
            netherMayor.setPos(-17, 80, 33);
            // From the Nether, normal OPAC checks reject releasing the Overworld chunk.
            command(netherMayor, "town disband", "!gui.xaero_claims_claim_not_within_dimension");
            check(api.isTownMayor(a.getUUID()), "Partial cleanup deleted the town");
            if (claims.get(Level.NETHER.location(), -2, 2) != null)
                command(netherMayor, "town unclaim", "land_released");
            check(claims.get(Level.NETHER.location(), -2, 2) == null && claims.get(dim, -2, 2) != null, "Dimension mix-up");
            checkCount(a, 1);
            command(a, "town disband", "town_disbanded");
            check(claims.get(dim, -2, 2) == null && api.getPlayerTown(a.getUUID()).isEmpty(), "Town cleanup");
            check(claims.get(dim, -3, 2).getPlayerId().equals(a.getUUID()), "Removed former mayor's personal claim");
            check(claims.get(dim, -4, 2).getPlayerId().equals(b.getUUID()), "Removed foreign claim");
        }

        void checkCount(ServerPlayer player, int expected) throws CommandSyntaxException {
            check(command(player, "town claims", "land_count").getArgs()[1].equals(expected), "Expected " + expected + " town chunks");
        }

        void setup() throws CommandSyntaxException {
            check(api.getPlayerTown(a.getUUID()).isEmpty(), "Use a new test world");
            command(a, "town create Ankara", "town_created");
            command(b, "town create ANKARA", "!name_taken");
            command(b, "town create İzmir", "town_created");
            command(c, "town accept Ankara", "!no_invitation");
            command(a, "town invite HnCem", "invited");
            command(a, "town invite HnCem", "!already_invited");
            command(c, "town deny Ankara", "declined");
            command(a, "town invite HnCem", "invited");
            command(c, "town accept Ankara", "town_joined");
            command(c, "town create Çorum", "!already_citizen");
            command(c, "town disband", "!mayor_only");
            command(c, "town invite HnDeniz", "!mayor_only");
            command(c, "nation create Anadolu", "!mayor_only");
            command(a, "town leave", "!transfer_first");
            command(a, "town setmayor HnBora", "!invalid_successor");
            command(a, "nation create Anadolu", "nation_created");
            command(a, "town setmayor HnCem", "new_mayor");
            check(api.isNationLeader(c.getUUID()) && !api.isNationLeader(a.getUUID()), "Capital succession");
            command(a, "nation invite İzmir", "!leader_only");
            command(c, "nation invite İzmir", "invited");
            command(b, "nation deny Anadolu", "declined");
            command(c, "nation invite İzmir", "invited");
            command(c, "town invite HnDeniz", "invited");
            command(c, "nation leave", "!capital");
            command(c, "town disband", "!capital");

            a.setPos(-17, 80, 33);
            command(a, "town claimstatus", "unclaimed");
            var claims = OpenPACServerAPI.get(server).getServerClaimsManager();
            var dimension = server.overworld().dimension().location();
            var pos = a.chunkPosition();
            check(pos.x == -2 && pos.z == 2, "Chunk conversion");
            check(claims.claim(dimension, a.getUUID(), 0, pos.x, pos.z, false) != null, "OPAC test claim");
            checkClaim(a, "Ankara");

            var otherDimension = new FakePlayer(server.getLevel(Level.NETHER), a.getGameProfile());
            otherDimension.setPos(-17, 80, 33);
            var wilderness = command(otherDimension, "town claimstatus", "unclaimed");
            check(wilderness.getArgs()[0].equals("minecraft:the_nether"), "Claim queried in wrong dimension");
            command(otherDimension, "town info", "town_info");
        }

        void checkClaim(FakePlayer queryingPlayer, String affiliation) throws CommandSyntaxException {
            Object[] fields = command(queryingPlayer, "town claimstatus", "claimed").getArgs();
            check(fields[0].equals("minecraft:overworld") && fields[1].equals(-2) && fields[2].equals(2)
                    && fields[4].equals(a.getUUID().toString())
                    && ((Component) fields[5]).getString().equals(affiliation), "Claim fields: " + Arrays.toString(fields));
        }

        void verify() throws CommandSyntaxException {
            var capital = api.getPlayerTown(c.getUUID()).orElseThrow();
            var country = api.getPlayerNation(c.getUUID()).orElseThrow();
            check(capital.mayor().equals(c.getUUID()) && country.capitalTown().equals(capital.id()), "Persisted leadership");
            check(api.getPlayerTown(a.getUUID()).orElseThrow().id().equals(capital.id()), "Persisted citizenship");
            a.setPos(-17, 80, 33);
            checkClaim(a, "Ankara");
            command(d, "town accept Ankara", "town_joined");
            command(d, "nation accept Anadolu", "!mayor_only");
            command(b, "nation accept Anadolu", "nation_joined");
            check(api.getNationTowns(country.id()).size() == 2 && api.isPlayerCitizenOfNation(b.getUUID(), country.id()), "Nation join");
            command(b, "nation create Other", "!already_in_nation");
            command(b, "nation leave", "nation_left");
            check(api.getPlayerNation(b.getUUID()).isEmpty(), "Derived nationality after leave");
            command(c, "nation invite İzmir", "invited");
            command(b, "nation accept Anadolu", "nation_joined");
            command(b, "nation disband", "!leader_only");
            command(b, "nation towns", "towns");
            command(b, "nation list", "nations");
            command(b, "nation info Anadolu", "nation_info");
            command(b, "town list", "towns");
            command(b, "town members", "members");
            command(c, "nation disband", "nation_disbanded");
            check(api.getNationById(country.id()).isEmpty() && api.getPlayerNation(b.getUUID()).isEmpty()
                    && api.getPlayerTown(b.getUUID()).isPresent() && api.getPlayerTown(d.getUUID()).isPresent(), "Nation cleanup");
            command(a, "town leave", "town_left");
            Object relationship = command(a, "town claimstatus", "claimed").getArgs()[5];
            check(((Component) relationship).getContents() instanceof TranslatableContents t
                    && t.getKey().equals("historical_nations.none"), "Claim persisted after owner left town");
            command(c, "town disband", "town_disbanded");
            check(api.getTownById(capital.id()).isEmpty() && api.getPlayerTown(d.getUUID()).isEmpty(), "Town cleanup");
            command(c, "town create Ankara", "town_created");
            check(!api.getPlayerTown(c.getUUID()).orElseThrow().id().equals(capital.id()), "Recreated town reused identity");
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
