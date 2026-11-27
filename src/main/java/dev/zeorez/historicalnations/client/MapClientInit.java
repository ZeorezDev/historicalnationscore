package dev.zeorez.historicalnations.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zeorez.historicalnations.HistoricalNations;
import dev.zeorez.historicalnations.map.MapCalibration;
import dev.zeorez.historicalnations.map.MapNetwork;
import dev.zeorez.historicalnations.map.WorldBoundsEnforcer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
        dev.zeorez.historicalnations.war.WarNetwork.setClientHandler(PeaceTreatyScreen::handlePacket);
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
        private static long lastClientWarnMs = 0L;

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
        public static void onClientPlayerTick(TickEvent.PlayerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || !event.side.isClient()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || event.player != mc.player || !mc.player.isAlive()) return;

            MapCalibration cal = ClientMapState.INSTANCE.calibration();
            if (!cal.enforceWorldBounds()) return;
            if (!mc.player.level().dimension().location().equals(cal.dimension())) return;

            double minX = cal.westBlockX() + WorldBoundsEnforcer.PLAYER_MARGIN;
            double maxX = Math.max(minX, (cal.eastBlockX() + 1.0D) - WorldBoundsEnforcer.PLAYER_MARGIN);
            double minZ = cal.northBlockZ() + WorldBoundsEnforcer.PLAYER_MARGIN;
            double maxZ = Math.max(minZ, (cal.southBlockZ() + 1.0D) - WorldBoundsEnforcer.PLAYER_MARGIN);

            Entity root = mc.player.getRootVehicle();
            boolean rootOut = root.getX() < minX || root.getX() > maxX || root.getZ() < minZ || root.getZ() > maxZ;
            boolean playerOut = mc.player.getX() < minX || mc.player.getX() > maxX || mc.player.getZ() < minZ || mc.player.getZ() > maxZ;
            if (!rootOut && !playerOut) return;

            clampEntityClient(root, minX, maxX, minZ, maxZ);
            if (root != mc.player) {
                clampEntityClient(mc.player, minX, maxX, minZ, maxZ);
            }

            long now = System.currentTimeMillis();
            if (now - lastClientWarnMs >= 2_000L) {
                lastClientWarnMs = now;
                mc.player.displayClientMessage(Component.translatable("hn.map.world_border_reached"), true);
            }
        }

        private static void clampEntityClient(Entity entity, double minX, double maxX, double minZ, double maxZ) {
            double cx = Mth.clamp(entity.getX(), minX, maxX);
            double cz = Mth.clamp(entity.getZ(), minZ, maxZ);
            Vec3 vel = entity.getDeltaMovement();
            double vx = ((entity.getX() < minX && vel.x < 0.0D) || (entity.getX() > maxX && vel.x > 0.0D)) ? 0.0D : vel.x;
            double vz = ((entity.getZ() < minZ && vel.z < 0.0D) || (entity.getZ() > maxZ && vel.z > 0.0D)) ? 0.0D : vel.z;
            entity.setDeltaMovement(vx, vel.y, vz);
            entity.setPos(cx, entity.getY(), cz);
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientMapState.INSTANCE.onDisconnect();
        }
    }
}
