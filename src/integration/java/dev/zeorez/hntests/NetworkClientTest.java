package dev.zeorez.hntests;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Drives the same command packets and invitation feedback used by two ordinary clients. */
@Mod.EventBusSubscriber(modid = "hn_integration_tests", value = Dist.CLIENT)
public final class NetworkClientTest {
    private static final String ACTOR = System.getProperty("hn.test.client", "");
    private static boolean created;
    private static int attempts;
    private static int readyTicks;
    private static String pending;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (ACTOR.isEmpty() || event.phase != TickEvent.Phase.END) return;
        var minecraft = Minecraft.getInstance();
        boolean ready = minecraft.getOverlay() == null && (minecraft.screen instanceof TitleScreen
                || minecraft.screen instanceof DisconnectedScreen);
        readyTicks = ready ? readyTicks + 1 : 0;
        if (readyTicks == 60 && attempts < 3) {
            attempts++;
            readyTicks = 0;
            var address = "127.0.0.1:25486";
            ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(address),
                    new ServerData("Historical Nations test", address, false), false);
        }
        var connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null) return;
        if (ACTOR.equals("HnNetA") && !created && connection.getPlayerInfo("HnNetB") != null) {
            created = true;
            pending = "town create Bursa";
        }
        if (pending != null) {
            connection.sendCommand(pending);
            pending = null;
        }
    }

    @SubscribeEvent
    public static void message(ClientChatReceivedEvent.System event) {
        if (ACTOR.equals("HnNetB") && event.getMessage().getString().startsWith("HN_TEST_TRADE ")) {
            var ids = event.getMessage().getString().split(" ");
            var connection = Minecraft.getInstance().getConnection();
            connection.sendCommand("trade accept "+ids[1]);
            connection.sendCommand("trade accept "+ids[1]);
            connection.sendCommand("trade accept "+ids[2]);
            connection.sendCommand("town bank deposit 1");
        }
        if (!ACTOR.isEmpty() && event.getMessage().getString().equals("HN_TEST_BID")) {
            var connection = Minecraft.getInstance().getConnection();
            connection.sendCommand("plot claim");
            connection.sendCommand("town bank deposit 1");
        }
        if (!(event.getMessage().getContents() instanceof TranslatableContents text)) return;
        if (ACTOR.equals("HnNetA") && text.getKey().equals("historical_nations.town_created"))
            pending = "town invite HnNetB";
        if (ACTOR.equals("HnNetB") && text.getKey().equals("historical_nations.town_invitation"))
            pending = "town accept Bursa";
    }

    @SubscribeEvent
    public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        if (!ACTOR.isEmpty() && event.getPlayer() != null) Minecraft.getInstance().stop();
    }
}
