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
    public enum MapMode { POLITICAL, TERRAIN }
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY = BUILDER.define("currencyName", "Lira");
    public static final ForgeConfigSpec.ConfigValue<String> SYMBOL = BUILDER.define("currencySymbol", "₺");
    public static final ForgeConfigSpec.LongValue STARTING = BUILDER.defineInRange("startingBalance", 0L, 0L, Long.MAX_VALUE);
    public static final ForgeConfigSpec.EnumValue<Liquidation> LIQUIDATION =
            BUILDER.defineEnum("bankLiquidation", Liquidation.REQUIRE_EMPTY);
    public static final ForgeConfigSpec.LongValue ISSUE_LIMIT = BUILDER.defineInRange("dailyIssueLimit", 10000L, 0L, Long.MAX_VALUE);
    public static final ForgeConfigSpec.IntValue FX_LIMIT = BUILDER.defineInRange("dailyFxLimitBps", 300, 0, 2000);
    public static final ForgeConfigSpec.IntValue ACTIVITY = BUILDER.defineInRange("economicActivityBaseline", 1000, 1, Integer.MAX_VALUE);
    public static final ForgeConfigSpec.BooleanValue INDEX_FEES = BUILDER.define("indexTaxesAndUpkeep", false);
    public static final ForgeConfigSpec.BooleanValue ENABLE_GOLD_COSTS;
    public static final ForgeConfigSpec.IntValue TOWN_CREATION_GOLD_COST;
    public static final ForgeConfigSpec.IntValue NATION_CREATION_GOLD_COST;
    public static final ForgeConfigSpec.IntValue CLAIM_GOLD_COST;
    public static final ForgeConfigSpec.BooleanValue REFUND_ON_UNCLAIM;

    public static final ForgeConfigSpec.BooleanValue MAP_ENABLED;
    public static final ForgeConfigSpec.EnumValue<MapMode> DEFAULT_MAP_MODE;
    public static final ForgeConfigSpec.BooleanValue SHOW_NATION_BORDERS;
    public static final ForgeConfigSpec.BooleanValue SHOW_TOWN_BORDERS;
    public static final ForgeConfigSpec.BooleanValue SHOW_TOWN_NAMES;
    public static final ForgeConfigSpec.BooleanValue SHOW_NATION_NAMES;
    public static final ForgeConfigSpec.BooleanValue ENABLE_TOWN_INFO_POPUP;
    public static final ForgeConfigSpec.BooleanValue ENABLE_MAP_SEARCH;

    public static final ForgeConfigSpec.IntValue EARTH_SCALE;
    public static final ForgeConfigSpec.ConfigValue<String> EARTH_DIMENSION;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_MAP_CALIBRATION;
    public static final ForgeConfigSpec.IntValue EARTH_MIN_BLOCK_X;
    public static final ForgeConfigSpec.IntValue EARTH_MAX_BLOCK_X;
    public static final ForgeConfigSpec.IntValue EARTH_MIN_BLOCK_Z;
    public static final ForgeConfigSpec.IntValue EARTH_MAX_BLOCK_Z;
    public static final ForgeConfigSpec.IntValue EARTH_ORIGIN_BLOCK_X;
    public static final ForgeConfigSpec.IntValue EARTH_ORIGIN_BLOCK_Z;
    public static final ForgeConfigSpec.DoubleValue EARTH_BLOCKS_PER_DEGREE;
    public static final ForgeConfigSpec.ConfigValue<String> MAP_TILE_DIRECTORY;

    public static final ForgeConfigSpec.BooleanValue ENABLE_TILE_CACHE;
    public static final ForgeConfigSpec.BooleanValue ENABLE_REGION_BASED_SYNC;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CLAIM_DELTA_UPDATES;
    public static final ForgeConfigSpec.IntValue MAX_VISIBLE_LABELS;
    public static final ForgeConfigSpec.IntValue MAX_CACHED_TILES;

    static {
        BUILDER.push("economy");
        ENABLE_GOLD_COSTS = BUILDER
                .comment("Require vanilla Gold Ingots for town creation, nation creation, and town chunk claims.")
                .define("enableGoldCosts", true);
        BUILDER.pop();

        BUILDER.push("town");
        TOWN_CREATION_GOLD_COST = BUILDER
                .comment("Vanilla Gold Ingots required to found a town (includes the free initial town chunk).")
                .defineInRange("creationGoldCost", 2, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("nation");
        NATION_CREATION_GOLD_COST = BUILDER
                .comment("Vanilla Gold Ingots required to found a nation.")
                .defineInRange("creationGoldCost", 128, 0, Integer.MAX_VALUE);
        BUILDER.pop();

        BUILDER.push("claims");
        CLAIM_GOLD_COST = BUILDER
                .comment("Flat vanilla Gold Ingots required for each additional town chunk claim.")
                .defineInRange("claimGoldCost", 1, 0, Integer.MAX_VALUE);
        REFUND_ON_UNCLAIM = BUILDER
                .comment("Whether unclaiming a paid town chunk refunds Gold Ingots.")
                .define("refundOnUnclaim", false);
        BUILDER.pop();

        BUILDER.push("politicalMap");
        MAP_ENABLED = BUILDER.comment("Enable the interactive Political World Map system.")
                .define("enabled", true);
        DEFAULT_MAP_MODE = BUILDER.comment("Initial map layer mode when opened by a player.")
                .defineEnum("defaultMapMode", MapMode.POLITICAL);
        SHOW_NATION_BORDERS = BUILDER.define("showNationBorders", true);
        SHOW_TOWN_BORDERS = BUILDER.define("showTownBorders", true);
        SHOW_TOWN_NAMES = BUILDER.define("showTownNames", true);
        SHOW_NATION_NAMES = BUILDER.define("showNationNames", true);
        ENABLE_TOWN_INFO_POPUP = BUILDER.define("enableTownInfoPopup", true);
        ENABLE_MAP_SEARCH = BUILDER.define("enableSearch", true);
        BUILDER.pop();

        BUILDER.push("earthMap");
        EARTH_SCALE = BUILDER.comment("Nominal cartographic scale of the Earth map (e.g. 500 for 1:500).")
                .defineInRange("scale", 500, 1, 100000);
        EARTH_DIMENSION = BUILDER.comment("Dimension rendered by the Political World Map.")
                .define("dimension", "minecraft:overworld");
        REQUIRE_MAP_CALIBRATION = BUILDER.comment("Require calibration.json verification before showing geographic degree readouts.")
                .define("requireMapCalibration", true);
        EARTH_MIN_BLOCK_X = BUILDER.comment("Western block boundary (MOTFE 1:500 default: -36864).")
                .defineInRange("minBlockX", -36864, -30000000, 30000000);
        EARTH_MAX_BLOCK_X = BUILDER.comment("Eastern block boundary (MOTFE 1:500 default: 36863).")
                .defineInRange("maxBlockX", 36863, -30000000, 30000000);
        EARTH_MIN_BLOCK_Z = BUILDER.comment("Northern block boundary (MOTFE 1:500 default: -18432).")
                .defineInRange("minBlockZ", -18432, -30000000, 30000000);
        EARTH_MAX_BLOCK_Z = BUILDER.comment("Southern block boundary (MOTFE 1:500 default: 18431).")
                .defineInRange("maxBlockZ", 18431, -30000000, 30000000);
        EARTH_ORIGIN_BLOCK_X = BUILDER.comment("Block X corresponding to 0 deg Longitude.")
                .defineInRange("originBlockX", 0, -30000000, 30000000);
        EARTH_ORIGIN_BLOCK_Z = BUILDER.comment("Block Z corresponding to 0 deg Latitude.")
                .defineInRange("originBlockZ", 0, -30000000, 30000000);
        EARTH_BLOCKS_PER_DEGREE = BUILDER.comment("Blocks per geographic degree (MOTFE 1:500 equirectangular: 73728 / 360 = 204.8).")
                .defineInRange("blocksPerDegree", 204.8D, 0.01D, 100000.0D);
        MAP_TILE_DIRECTORY = BUILDER.comment("Directory containing pre-generated map terrain tiles (absolute path or relative to server root).")
                .define("tileDirectory", "D:/HeartsOfSteel/MapTiles");
        BUILDER.pop();

        BUILDER.push("mapPerformance");
        ENABLE_TILE_CACHE = BUILDER.define("enableTileCache", true);
        ENABLE_REGION_BASED_SYNC = BUILDER.define("enableRegionBasedSync", true);
        ENABLE_CLAIM_DELTA_UPDATES = BUILDER.define("enableClaimDeltaUpdates", true);
        MAX_VISIBLE_LABELS = BUILDER.defineInRange("maxVisibleLabels", 150, 10, 2000);
        MAX_CACHED_TILES = BUILDER.defineInRange("maxCachedTiles", 256, 32, 4096);
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private TerritoryConfig() {}
}
