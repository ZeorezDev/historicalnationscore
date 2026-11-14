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
    public enum Liquidation { REQUIRE_EMPTY, PAY_LEADER }
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY = BUILDER.define("currencyName", "Lira");
    public static final ForgeConfigSpec.ConfigValue<String> SYMBOL = BUILDER.define("currencySymbol", "₺");
    public static final ForgeConfigSpec.LongValue STARTING = BUILDER.defineInRange("startingBalance", 0L, 0L, Long.MAX_VALUE);
    public static final ForgeConfigSpec.EnumValue<Liquidation> LIQUIDATION =
            BUILDER.defineEnum("bankLiquidation", Liquidation.REQUIRE_EMPTY);
    public static final ForgeConfigSpec.LongValue ISSUE_LIMIT = BUILDER.defineInRange("dailyIssueLimit", 10000L, 0L, Long.MAX_VALUE);
    public static final ForgeConfigSpec.IntValue FX_LIMIT = BUILDER.defineInRange("dailyFxLimitBps", 300, 0, 2000);
    public static final ForgeConfigSpec.IntValue ACTIVITY = BUILDER.defineInRange("economicActivityBaseline", 1000, 1, Integer.MAX_VALUE);
    public static final ForgeConfigSpec.BooleanValue INDEX_FEES = BUILDER.define("indexTaxesAndUpkeep", false);
    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private TerritoryConfig() {}
}
