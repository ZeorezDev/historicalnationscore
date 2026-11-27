package dev.zeorez.historicalnations.map;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authoritative rectangular world-map boundary enforcement for Dedicated & Integrated Servers.
 *
 * Vanilla Minecraft's WorldBorder is strictly square, whereas equirectangular Earth maps (e.g. MOTFE 1:500:
 * 73,728 x 36,864 blocks) are 2:1 rectangles. This enforcer keeps players, vehicles/ships/aircraft,
 * ender pearls, chorus fruit, teleports, dimension transitions, and chunk claims strictly inside the
 * configured Earth map bounds with O(1) in-memory per-tick checks and zero out-of-bounds chunk loads.
 */
public final class WorldBoundsEnforcer {
    public static final double PLAYER_MARGIN = 0.5D;
    private static final int CACHE_REFRESH_TICKS = 100;
    private static final int WARN_COOLDOWN_TICKS = 40;

    private static final Map<MinecraftServer, ActiveBounds> SERVER_BOUNDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<UUID, Integer> LAST_WARN_TICK = new ConcurrentHashMap<>();

    private WorldBoundsEnforcer() {}

    public record ActiveBounds(
            ResourceLocation dimension,
            boolean enforce,
            double minX,
            double maxX,
            double minZ,
            double maxZ,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            int syncedTick
    ) {
        public static ActiveBounds from(MapCalibration cal, int tick) {
            int west = cal.westBlockX();
            int east = cal.eastBlockX();
            int north = cal.northBlockZ();
            int south = cal.southBlockZ();
            double minX = west + PLAYER_MARGIN;
            double maxX = Math.max(minX, (east + 1.0D) - PLAYER_MARGIN);
            double minZ = north + PLAYER_MARGIN;
            double maxZ = Math.max(minZ, (south + 1.0D) - PLAYER_MARGIN);
            return new ActiveBounds(
                    cal.dimension(),
                    cal.enforceWorldBounds(),
                    minX, maxX, minZ, maxZ,
                    west >> 4, east >> 4, north >> 4, south >> 4,
                    tick
            );
        }

        public boolean isOutside(double x, double z) {
            return x < minX || x > maxX || z < minZ || z > maxZ;
        }
    }

    public static ActiveBounds bounds(MinecraftServer server) {
        int now = server.getTickCount();
        ActiveBounds cached = SERVER_BOUNDS.get(server);
        if (cached != null && now - cached.syncedTick() >= 0 && now - cached.syncedTick() < CACHE_REFRESH_TICKS) {
            return cached;
        }
        ActiveBounds fresh = ActiveBounds.from(MapCalibration.fromServer(server), now);
        SERVER_BOUNDS.put(server, fresh);
        return fresh;
    }

    public static void invalidate(MinecraftServer server) {
        if (server != null) {
            SERVER_BOUNDS.remove(server);
        }
    }

    public static boolean isChunkInsideBounds(MinecraftServer server, ResourceLocation dimension, int chunkX, int chunkZ) {
        if (server == null || dimension == null) return true;
        ActiveBounds b = bounds(server);
        if (!b.enforce() || !b.dimension().equals(dimension)) return true;
        return chunkX >= b.minChunkX() && chunkX <= b.maxChunkX()
                && chunkZ >= b.minChunkZ() && chunkZ <= b.maxChunkZ();
    }

    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer sp)) return;
        if (sp.server == null || !sp.isAlive()) return;
        if (!(sp instanceof FakePlayer) && sp.hasDisconnected()) return;

        enforcePlayerPosition(sp);
    }

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || sp.server == null) return;
        PoliticalMapServer.get(sp.server).sendCalibrationHint(sp);
        enforcePlayerPosition(sp);
    }

    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && sp.server != null) {
            enforcePlayerPosition(sp);
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && sp.server != null) {
            enforcePlayerPosition(sp);
        }
    }

    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            LAST_WARN_TICK.remove(event.getEntity().getUUID());
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        SERVER_BOUNDS.remove(event.getServer());
        LAST_WARN_TICK.clear();
    }

    public static void onEntityTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel sl)) return;
        ActiveBounds b = bounds(sl.getServer());
        if (!b.enforce() || !sl.dimension().location().equals(b.dimension())) return;
        if (!b.isOutside(event.getTargetX(), event.getTargetZ())) return;

        ServerPlayer targetPlayer = findServerPlayer(entity);
        if (targetPlayer == null) return;

        if (event instanceof EntityTeleportEvent.EnderPearl || event instanceof EntityTeleportEvent.ChorusFruit) {
            event.setCanceled(true);
            notifyBorder(targetPlayer);
            return;
        }

        event.setTargetX(Mth.clamp(event.getTargetX(), b.minX(), b.maxX()));
        event.setTargetZ(Mth.clamp(event.getTargetZ(), b.minZ(), b.maxZ()));
        notifyBorder(targetPlayer);
    }

    public static boolean enforcePlayerPosition(ServerPlayer sp) {
        ActiveBounds b = bounds(sp.server);
        if (!b.enforce() || !sp.serverLevel().dimension().location().equals(b.dimension())) {
            return false;
        }

        Entity root = sp.getRootVehicle();
        boolean rootOut = b.isOutside(root.getX(), root.getZ());
        boolean playerOut = b.isOutside(sp.getX(), sp.getZ());
        if (!rootOut && !playerOut) {
            return false;
        }

        double refX = rootOut ? root.getX() : sp.getX();
        double refZ = rootOut ? root.getZ() : sp.getZ();
        double clampedX = Mth.clamp(refX, b.minX(), b.maxX());
        double clampedZ = Mth.clamp(refZ, b.minZ(), b.maxZ());

        clampOutwardVelocity(root, b);
        if (root != sp) {
            clampOutwardVelocity(sp, b);
        }

        if (Math.hypot(refX - clampedX, refZ - clampedZ) > 4.0D) {
            root.resetFallDistance();
            sp.resetFallDistance();
        }

        if (sp instanceof FakePlayer || sp.connection == null) {
            root.moveTo(clampedX, root.getY(), clampedZ, root.getYRot(), root.getXRot());
            if (root != sp) {
                sp.moveTo(clampedX, sp.getY(), clampedZ, sp.getYRot(), sp.getXRot());
            }
        } else if (root != sp) {
            root.teleportTo(clampedX, root.getY(), clampedZ);
        } else {
            sp.connection.teleport(clampedX, sp.getY(), clampedZ, sp.getYRot(), sp.getXRot());
        }

        notifyBorder(sp);
        return true;
    }

    private static void clampOutwardVelocity(Entity entity, ActiveBounds b) {
        Vec3 vel = entity.getDeltaMovement();
        double vx = ((entity.getX() < b.minX() && vel.x < 0.0D) || (entity.getX() > b.maxX() && vel.x > 0.0D)) ? 0.0D : vel.x;
        double vz = ((entity.getZ() < b.minZ() && vel.z < 0.0D) || (entity.getZ() > b.maxZ() && vel.z > 0.0D)) ? 0.0D : vel.z;
        if (vx != vel.x || vz != vel.z) {
            entity.setDeltaMovement(vx, vel.y, vz);
            entity.hasImpulse = true;
        }
    }

    private static ServerPlayer findServerPlayer(Entity entity) {
        if (entity instanceof ServerPlayer sp) return sp;
        for (Entity passenger : entity.getIndirectPassengers()) {
            if (passenger instanceof ServerPlayer sp) return sp;
        }
        return null;
    }

    private static void notifyBorder(ServerPlayer sp) {
        if (sp.server == null) return;
        int now = sp.server.getTickCount();
        Integer last = LAST_WARN_TICK.get(sp.getUUID());
        if (last != null && now - last >= 0 && now - last < WARN_COOLDOWN_TICKS) {
            return;
        }
        LAST_WARN_TICK.put(sp.getUUID(), now);
        sp.displayClientMessage(Component.translatable("hn.map.world_border_reached"), true);
    }
}
