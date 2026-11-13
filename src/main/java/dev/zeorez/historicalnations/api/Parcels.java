package dev.zeorez.historicalnations.api;

import dev.zeorez.historicalnations.property.PropertyBook;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import java.util.Optional;

/** Logical server thread only. Each query verifies the physical OPAC claim before producing a snapshot. */
public final class Parcels {
    private Parcels() {}
    public static Optional<ParcelView> plot(MinecraftServer server, ResourceLocation dimension, BlockPos pos) {
        return PropertyBook.lookup(server, dimension, pos, false);
    }
    public static Optional<ParcelView> quarter(MinecraftServer server, ResourceLocation dimension, BlockPos pos) {
        return PropertyBook.lookup(server, dimension, pos, true);
    }
}
