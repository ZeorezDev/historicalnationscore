package dev.zeorez.historicalnations.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zeorez.historicalnations.HistoricalNations;
import dev.zeorez.historicalnations.map.MapNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only initialization for the Political World Map (keybinding M, network receiver, and session cleanup).
 * Isolated under Dist.CLIENT so Dedicated Servers never load GLFW, KeyMapping, or Screen classes.
 */
public final class MapClientInit {
    public static final KeyMapping OPEN_MAP_KEY = new KeyMapping(
            "key.historical_nations.open_map",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.historical_nations"
    );

    private MapClientInit() {}

    public static void initClientNetwork() {
        MapNetwork.setClientHandler(ClientMapState.INSTANCE);
    }

    @Mod.EventBusSubscriber(modid = HistoricalNations.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MAP_KEY);
        }
    }

    @Mod.EventBusSubscriber(modid = HistoricalNations.ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBusEvents {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (OPEN_MAP_KEY.consumeClick()) {
                if (mc.player != null && mc.screen == null) {
                    PoliticalMapScreen.openDefault();
                }
            }
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientMapState.INSTANCE.onDisconnect();
        }
    }
}
