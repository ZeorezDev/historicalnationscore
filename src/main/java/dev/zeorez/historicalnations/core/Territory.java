package dev.zeorez.historicalnations.core;

import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** A link to an OPAC sub-claim. OPAC remains authoritative for the claim itself. */
public record Territory(UUID town, UUID owner, int subIndex) {
    public record Location(ResourceLocation dimension, int x, int z) {}
}
