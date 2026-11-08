package dev.zeorez.historicalnations;

import net.minecraftforge.common.ForgeConfigSpec;

public final class TerritoryConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.IntValue LIMIT = BUILDER.comment("Maximum linked OPAC chunks per town.")
            .defineInRange("maxChunksPerTown", 16, 0, Integer.MAX_VALUE);
    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private TerritoryConfig() {}
}
