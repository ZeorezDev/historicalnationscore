package dev.zeorez.historicalnations;

import net.minecraftforge.common.ForgeConfigSpec;

public final class TerritoryConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue LIMIT = BUILDER.comment("Maximum linked OPAC chunks per town.")
            .defineInRange("maxChunksPerTown", 16, 0, Integer.MAX_VALUE);
    public static final ForgeConfigSpec.IntValue QUARTERS = BUILDER.defineInRange("maxQuartersPerTown", 128, 0, 10000);
    public static final ForgeConfigSpec.IntValue VOLUME = BUILDER.defineInRange("maxCuboidVolume", 16384, 1, 16000000);
    public static final ForgeConfigSpec.IntValue PARTS = BUILDER.defineInRange("maxCuboidsPerQuarter", 16, 1, 128);
    public static final ForgeConfigSpec.BooleanValue ENTRY = BUILDER.define("quarterEntryMessages", true);
    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private TerritoryConfig() {}
}
