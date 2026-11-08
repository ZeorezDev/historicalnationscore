package dev.zeorez.hntests;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import dev.zeorez.historicalnations.api.HistoricalNationsApi;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import xaero.pac.common.server.api.OpenPACServerAPI;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Test-only Forge mod. Runs only when explicitly invoked in a disposable server. */
@Mod("hn_integration_tests")
public final class PrototypeServerTest {
    private boolean ran;

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
                LogUtils.getLogger().info("HN INTEGRATION PASS network");
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

    private static final class Suite {
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
            check(translated.getKey().equals("historical_nations." + resultKey), command + " returned " + translated.getKey());
            return translated;
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
