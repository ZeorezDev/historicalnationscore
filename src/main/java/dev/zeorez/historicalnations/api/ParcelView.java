package dev.zeorez.historicalnations.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Immutable snapshot. A null owner denotes municipal land, a null price a closed offer. */
public record ParcelView(UUID id, UUID town, ResourceLocation dimension, int chunkX, int chunkZ,
                        String name, String type, UUID owner, Long price, String group, int color,
                        String description, Set<UUID> trusted, Map<String, Boolean> permissions, List<Box> boxes, String currency) {
    public ParcelView(UUID id, UUID town, ResourceLocation dimension, int chunkX, int chunkZ, String name,
                      String type, UUID owner, Long price, String group, int color, String description,
                      Set<UUID> trusted, Map<String, Boolean> permissions, List<Box> boxes) {
        this(id,town,dimension,chunkX,chunkZ,name,type,owner,price,group,color,description,trusted,permissions,boxes,EconomyApi.REFERENCE);
    }
    public ParcelView {
        trusted = Set.copyOf(trusted);
        permissions = Map.copyOf(permissions);
        boxes = List.copyOf(boxes);
    }

    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public static Box between(BlockPos a, BlockPos b) {
            return new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }
        public boolean contains(BlockPos p) {
            return p.getX() >= x1 && p.getX() <= x2 && p.getY() >= y1 && p.getY() <= y2 && p.getZ() >= z1 && p.getZ() <= z2;
        }
        public boolean intersects(Box b) {
            return x1 <= b.x2 && b.x1 <= x2 && y1 <= b.y2 && b.y1 <= y2 && z1 <= b.z2 && b.z1 <= z2;
        }
        public boolean inChunk(int x, int z) {
            return x1 <= x2 && y1 <= y2 && z1 <= z2 && x1 >> 4 == x && x2 >> 4 == x && z1 >> 4 == z && z2 >> 4 == z;
        }
        public long volume() { return (x2 - (long)x1 + 1) * (y2 - (long)y1 + 1) * (z2 - (long)z1 + 1); }
    }
}
