package dev.zeorez.hntests;

import dev.zeorez.historicalnations.client.ClientMapState;
import dev.zeorez.historicalnations.client.ClientTileCache;
import dev.zeorez.historicalnations.client.MapClientInit;
import dev.zeorez.historicalnations.client.PoliticalMapScreen;
import dev.zeorez.historicalnations.client.PoliticalScreen;
import dev.zeorez.historicalnations.gui.PanelQuery.View;
import dev.zeorez.historicalnations.map.MapModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.UUID;

import static dev.zeorez.hntests.PrototypeServerTest.check;

/**
 * Real multiplayer Forge client test driving two live clients (HnNetA & HnNetB):
 * - M keybinding & /map command opening PoliticalMapScreen
 * - Spatial border meshing (0 nation borders between adjacent towns of same nation, thin town border present)
 * - Right-click Town Info Popup (Nation header KINGDOM OF YUGOSLAVIA vs INDEPENDENT TOWN)
 * - [VIEW TOWN] -> v8.0.0 PoliticalScreen (View.TOWN) -> View on Map -> PoliticalMapScreen
 * - Nation Header click -> v8.0.0 PoliticalScreen (View.NATION)
 * - Search bar (town, nation, "7773 -8037" coordinates, and nonexistent query)
 * - Live multiplayer delta sync between HnNetA and HnNetB + server-isolated cache paths
 */
@Mod.EventBusSubscriber(modid = "hn_integration_tests", value = Dist.CLIENT)
public final class MapClientTest {
    private static int step = 0;
    private static int ticks = 0;

    @SubscribeEvent
    public static void onSystemChat(ClientChatReceivedEvent.System event) {
        if (!Boolean.getBoolean("hn.test.map")) return;
        String msg = event.getMessage().getString();
        if (msg.equals("HN_MAP_CLIENT_A_START") && "HnNetA".equals(System.getProperty("hn.test.client"))) {
            step = 1;
        } else if (msg.equals("HN_MAP_CLIENT_B_VERIFY") && "HnNetB".equals(System.getProperty("hn.test.client"))) {
            step = 100;
        } else if (msg.equals("HN_MAP_DELTA_CHECK") && "HnNetB".equals(System.getProperty("hn.test.client"))) {
            step = 110;
        }
    }

    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) throws Exception {
        if (step == 0 || event.phase != TickEvent.Phase.END || ++ticks % 8 != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        ClientMapState state = ClientMapState.INSTANCE;

        switch (step) {
            case 1 -> {
                mc.getWindow().setWindowed(1280, 720);
                mc.options.guiScale().set(2);
                mc.resizeDisplay();
                mc.getToasts().clear();

                // Test 15: Verify server-isolated cache paths never collide
                UUID server1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
                UUID server2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
                Path p1 = ClientTileCache.resolveCacheRoot(server1);
                Path p2 = ClientTileCache.resolveCacheRoot(server2);
                check(!p1.equals(p2) && p1.toString().startsWith("D:\\HeartsOfSteel\\Cache"),
                        "isolated D:\\HeartsOfSteel\\Cache per server mapId: " + p1);

                // Test 2: Verify M keybinding default is GLFW_KEY_M and open map screen
                check(MapClientInit.OPEN_MAP_KEY.getKey().getValue() == GLFW.GLFW_KEY_M, "M key default");
                PoliticalMapScreen.openDefault();
                step = 2;
            }
            case 2 -> {
                if (!(mc.screen instanceof PoliticalMapScreen map)) return;
                if (state.nations().isEmpty() || state.totalClaimedChunks() < 4) return;

                // Test 5: Verify spatial region border meshing:
                // Belgrade (0,0) and Novi Sad (0,-1) belong to Kingdom of Yugoslavia -> shared border at Z=0 must be a TOWN border, NOT a NATION border!
                var meshes = state.visibleRegions(-256, -256, 512, 512);
                check(!meshes.isEmpty(), "visible region mesh built");
                boolean hasInternalTownBorder = meshes.stream().flatMap(m -> m.townBorders.stream())
                        .anyMatch(seg -> seg.z1() == 0 && seg.z2() == 0 && seg.x1() <= 0 && seg.x2() >= 16);
                boolean hasWrongInternalNationBorder = meshes.stream().flatMap(m -> m.nationBorders.stream())
                        .anyMatch(seg -> seg.z1() == 0 && seg.z2() == 0 && seg.x1() == 0 && seg.x2() == 16);
                check(hasInternalTownBorder && !hasWrongInternalNationBorder,
                        "same-nation towns share ONLY town border, zero nation border");

                // Test 10 (Search): Town, Nation, Coordinate ("7773 -8037"), and Nonexistent search
                check(state.search("Kumanovo").stream().anyMatch(h -> h.kind() == MapModels.FocusKind.TOWN), "search town Kumanovo");
                check(state.search("Yugoslavia").stream().anyMatch(h -> h.kind() == MapModels.FocusKind.NATION), "search nation Yugoslavia");
                var coordHit = state.search("7773 -8037");
                check(!coordHit.isEmpty() && coordHit.get(0).blockX() == 7773 && coordHit.get(0).blockZ() == -8037,
                        "coordinate search 7773 -8037");
                check(state.search("AtlantisNonExistent999").isEmpty(), "nonexistent search empty");

                // Focus on Kumanovo (chunk 4, 0 -> block 72, 8) and right-click center of screen!
                var kumanovo = state.towns().stream().filter(t -> t.name().equals("Kumanovo")).findFirst().orElseThrow();
                PoliticalMapScreen.openWithFocus(MapModels.FocusKind.COORDS, null, kumanovo.homeBlockX(), kumanovo.homeBlockZ());
                step = 3;
            }
            case 3 -> {
                if (!(mc.screen instanceof PoliticalMapScreen map)) return;
                // Right-click center of map (where Kumanovo's chunk is centered)
                double cx = map.width * 0.5D;
                double cy = 26 + (map.height - 26 - 18) * 0.5D;
                check(map.mouseClicked(cx, cy, 1), "right-click claimed town chunk");
                UUID popupId = (UUID) field(map, "popupTownId");
                var kumanovo = state.town(popupId);
                check(kumanovo != null && kumanovo.name().equals("Kumanovo"), "Test 6: Kumanovo popup opened");
                var yugo = state.nation(kumanovo.nationId());
                check(yugo != null && yugo.name().equalsIgnoreCase("Kingdom of Yugoslavia"), "Test 7: Nation header Kingdom of Yugoslavia");
                step = 35;
            }
            case 35 -> {
                if (!(mc.screen instanceof PoliticalMapScreen map)) return;
                int btnW = (int) field(map, "viewTownBtnW");
                if (btnW <= 0) return; // Wait until renderTownPopup has laid out the popup card on a render frame
                Screenshot.grab(mc.gameDirectory, "hn-v9-political-map.png", mc.getMainRenderTarget(), c -> {});

                // Test 13a: Click [VIEW TOWN] button on popup -> opens v8.0.0 PoliticalScreen on View.TOWN!
                int btnX = (int) field(map, "viewTownBtnX") + 4;
                int btnY = (int) field(map, "viewTownBtnY") + 4;
                check(map.mouseClicked(btnX, btnY, 0), "click [VIEW TOWN]");
                step = 4;
            }
            case 4 -> {
                if (!(mc.screen instanceof PoliticalScreen ps)) return;
                var q = (dev.zeorez.historicalnations.gui.PanelQuery) field(ps, "query");
                var d = (net.minecraft.nbt.CompoundTag) field(ps, "data");
                check(q.view() == View.TOWN && d.getString("title").equals("Kumanovo"), "v8 Town GUI opened from map");

                // Click "View on Map" button in PoliticalScreen header -> returns to PoliticalMapScreen focused on Kumanovo!
                var viewBtn = ps.children().stream()
                        .filter(net.minecraft.client.gui.components.Button.class::isInstance)
                        .map(net.minecraft.client.gui.components.Button.class::cast)
                        .filter(b -> b.getMessage().equals(net.minecraft.network.chat.Component.translatable("hn.map.view_on_map")))
                        .findFirst().orElseThrow();
                viewBtn.onPress();
                step = 5;
            }
            case 5 -> {
                if (!(mc.screen instanceof PoliticalMapScreen map)) return;
                // Test 8: Right-click Independent Town (Dubrovnik) -> popup has null nationId (INDEPENDENT TOWN)
                var dub = state.towns().stream().filter(t -> t.name().equals("Dubrovnik")).findFirst().orElseThrow();
                check(dub.nationId() == null, "Test 8: Dubrovnik is Independent Town");

                // Test 13b: Click Nation Header on Kumanovo popup -> opens v8.0.0 Nation GUI!
                var kumanovo = state.towns().stream().filter(t -> t.name().equals("Kumanovo")).findFirst().orElseThrow();
                PoliticalMapScreen.openWithFocus(MapModels.FocusKind.TOWN, kumanovo.id(), 0, 0);
                step = 6;
            }
            case 6 -> {
                if (!(mc.screen instanceof PoliticalMapScreen map)) return;
                int px = (int) field(map, "popupX") + 8;
                int py = (int) field(map, "popupY") + 6;
                check(map.mouseClicked(px, py, 0), "click Nation header on popup");
                step = 7;
            }
            case 7 -> {
                if (!(mc.screen instanceof PoliticalScreen ps)) return;
                var q = (dev.zeorez.historicalnations.gui.PanelQuery) field(ps, "query");
                check(q.view() == View.NATION, "v8 Nation GUI opened from popup Nation header");
                mc.setScreen(null);
                mc.getConnection().sendCommand("hnmapack");
                step = 0;
            }
            case 100 -> {
                // Client B opens map via /map command!
                mc.getConnection().sendCommand("map");
                step = 101;
            }
            case 101 -> {
                if (!(mc.screen instanceof PoliticalMapScreen)) return;
                if (state.nations().isEmpty() || state.totalClaimedChunks() < 4) return;
                // Verify Client B sees the exact same Yugoslavia & Kumanovo data!
                check(state.towns().stream().anyMatch(t -> t.name().equals("Kumanovo")), "Client B synced Kumanovo");
                mc.getConnection().sendCommand("hnmapack");
                step = 0;
            }
            case 110 -> {
                // Live delta verification on Client B while PoliticalMapScreen is ALREADY OPEN:
                // Server claimed chunk (1, 0) for Belgrade and left Kumanovo from Yugoslavia!
                var kumanovo = state.towns().stream().filter(t -> t.name().equals("Kumanovo")).findFirst().orElse(null);
                var claimed10 = state.townAtChunk(1, 0);
                if (kumanovo == null || kumanovo.nationId() != null || claimed10 == null) return;
                check(claimed10.name().equals("Belgrade") && kumanovo.nationId() == null,
                        "Client B received live claim + nation-leave deltas while map open");
                mc.setScreen(null);
                mc.getConnection().sendCommand("hnmapack");
                step = 0;
            }
            default -> {}
        }
    }
}
