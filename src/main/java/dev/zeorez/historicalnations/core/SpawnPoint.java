package dev.zeorez.historicalnations.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Persistent world spawn point (dimension, exact block/entity position, and view rotation) for a town or nation. */
public record SpawnPoint(ResourceLocation dimension, double x, double y, double z, float yaw, float pitch) {

    public static SpawnPoint fromPlayer(ServerPlayer player) {
        return new SpawnPoint(
                player.serverLevel().dimension().location(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()
        );
    }

    public BlockPos blockPos() {
        return BlockPos.containing(x, y, z);
    }

    public Territory.Location chunkLocation() {
        BlockPos pos = blockPos();
        return new Territory.Location(dimension, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", dimension.toString());
        tag.putDouble("X", x);
        tag.putDouble("Y", y);
        tag.putDouble("Z", z);
        tag.putFloat("Yaw", yaw);
        tag.putFloat("Pitch", pitch);
        return tag;
    }

    public static SpawnPoint load(CompoundTag tag) {
        if (tag == null || !tag.contains("Dimension", Tag.TAG_STRING)
                || !tag.contains("X", Tag.TAG_DOUBLE)
                || !tag.contains("Y", Tag.TAG_DOUBLE)
                || !tag.contains("Z", Tag.TAG_DOUBLE)) {
            return null;
        }
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dim == null || dim.getPath().isEmpty()) return null;
        double x = tag.getDouble("X");
        double y = tag.getDouble("Y");
        double z = tag.getDouble("Z");
        float yaw = tag.getFloat("Yaw");
        float pitch = tag.getFloat("Pitch");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            return null;
        }
        return new SpawnPoint(dim, x, y, z, yaw, pitch);
    }
}
