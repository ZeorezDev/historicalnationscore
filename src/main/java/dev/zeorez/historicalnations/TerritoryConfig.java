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
    public enum MapMode { POLITICAL, TERRAIN, WAR }
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
    public static final ForgeConfigSpec.BooleanValue LOCK_CAMERA_TO_BOUNDS;

    public static final ForgeConfigSpec.IntValue EARTH_SCALE;
    public static final ForgeConfigSpec.ConfigValue<String> EARTH_DIMENSION;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_MAP_CALIBRATION;
    public static final ForgeConfigSpec.BooleanValue ENFORCE_WORLD_BOUNDS;
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

    // v11.0.0 Flag War & Territorial Conquest configuration
    public static final ForgeConfigSpec.BooleanValue WAR_ENABLED;
    public static final ForgeConfigSpec.IntValue WAR_PREPARATION_HOURS;
    public static final ForgeConfigSpec.IntValue WAR_TRUCE_HOURS;
    public static final ForgeConfigSpec.BooleanValue WAR_ALLOW_INDEPENDENT_TOWNS;

    public static final ForgeConfigSpec.IntValue WAR_CAPTURE_TIME_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_FLAG_ITEM;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> WAR_ALLOWED_FLAG_ITEMS;
    public static final ForgeConfigSpec.BooleanValue WAR_REQUIRE_SKY_ACCESS;
    public static final ForgeConfigSpec.BooleanValue WAR_REQUIRE_ATTACKER_NEARBY;
    public static final ForgeConfigSpec.IntValue WAR_ATTACKER_PRESENCE_RADIUS;
    public static final ForgeConfigSpec.IntValue WAR_ABANDON_CANCEL_SECONDS;
    public static final ForgeConfigSpec.IntValue WAR_FLAG_NO_BUILD_RADIUS;
    public static final ForgeConfigSpec.IntValue WAR_MAX_ACTIVE_FLAGS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue WAR_MAX_ACTIVE_FLAGS_PER_NATION;
    public static final ForgeConfigSpec.BooleanValue WAR_BORDER_ONLY;
    public static final ForgeConfigSpec.BooleanValue WAR_ALLOW_DIAGONAL_ADVANCE;
    public static final ForgeConfigSpec.IntValue WAR_FRONTIER_STAGING_DISTANCE_CHUNKS;

    public static final ForgeConfigSpec.BooleanValue WAR_PROTECT_BUILDINGS;
    public static final ForgeConfigSpec.BooleanValue WAR_ALLOW_BLOCK_DESTRUCTION;
    public static final ForgeConfigSpec.BooleanValue WAR_ALLOW_CHEST_LOOTING;
    public static final ForgeConfigSpec.BooleanValue WAR_PAUSE_WHEN_WINDOW_CLOSED;
    public static final ForgeConfigSpec.BooleanValue WAR_REQUIRE_ONLINE_DEFENDERS_TO_START;
    public static final ForgeConfigSpec.IntValue WAR_DEFENDER_LOGOUT_GRACE_SECONDS;
    public static final ForgeConfigSpec.IntValue WAR_SPAWN_PROTECTION_RADIUS_CHUNKS;

    public static final ForgeConfigSpec.BooleanValue WAR_SCHEDULE_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_TIMEZONE;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_MONDAY;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_TUESDAY;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_WEDNESDAY;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_THURSDAY;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_FRIDAY;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_SATURDAY;
    public static final ForgeConfigSpec.ConfigValue<String> WAR_SUNDAY;

    public static final ForgeConfigSpec.BooleanValue WAR_REQUIRE_PEACE_TREATY_FOR_ANNEXATION;
    public static final ForgeConfigSpec.BooleanValue WAR_ENABLE_OCCUPATION_OVERLAY;

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
        LOCK_CAMERA_TO_BOUNDS = BUILDER.comment("Lock Political World Map camera zoom and panning strictly inside the configured Earth map bounds.")
                .define("lockCameraToBounds", true);
        BUILDER.pop();

        BUILDER.push("earthMap");
        EARTH_SCALE = BUILDER.comment("Nominal cartographic scale of the Earth map (e.g. 500 for 1:500).")
                .defineInRange("scale", 500, 1, 100000);
        EARTH_DIMENSION = BUILDER.comment("Dimension rendered by the Political World Map.")
                .define("dimension", "minecraft:overworld");
        REQUIRE_MAP_CALIBRATION = BUILDER.comment("Require calibration.json verification before showing geographic degree readouts.")
                .define("requireMapCalibration", true);
        ENFORCE_WORLD_BOUNDS = BUILDER.comment("Prevent players, vehicles, teleports, and chunk claims from crossing outside the configured rectangular Earth map bounds in-game.")
                .define("enforceWorldBounds", true);
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

        BUILDER.push("war");
        WAR_ENABLED = BUILDER.comment("Master switch for the Historical Nations Flag War & Territorial Conquest system.")
                .define("enabled", true);
        WAR_PREPARATION_HOURS = BUILDER.comment("Preparation countdown in hours after /war declare before chunk capture is allowed.")
                .defineInRange("preparationHours", 24, 0, 720);
        WAR_TRUCE_HOURS = BUILDER.comment("Default truce duration in hours enforced after a peace treaty concludes a war.")
                .defineInRange("truceHours", 48, 0, 720);
        WAR_ALLOW_INDEPENDENT_TOWNS = BUILDER.comment("Allow declaring war against towns that do not belong to any nation.")
                .define("allowIndependentTownWars", false);

        BUILDER.push("capture");
        WAR_CAPTURE_TIME_SECONDS = BUILDER.comment("Seconds required to hold a War Capture Flag to occupy an enemy chunk.")
                .defineInRange("captureTimeSeconds", 300, 5, 7200);
        WAR_FLAG_ITEM = BUILDER.comment("Primary vanilla fence block item used to place a War Capture Flag.")
                .define("flagItem", "minecraft:oak_fence");
        WAR_ALLOWED_FLAG_ITEMS = BUILDER.comment("Additional fence block registry IDs allowed to initiate a War Capture Flag.")
                .defineListAllowEmpty("allowedFlagItems", java.util.List.of("minecraft:oak_fence"),
                        v -> v instanceof String s && s.contains(":"));
        WAR_REQUIRE_SKY_ACCESS = BUILDER.comment("Require unobstructed vertical sky access above a War Capture Flag (prevents indoor/underground/underwater flags).")
                .define("requireSkyAccess", true);
        WAR_REQUIRE_ATTACKER_NEARBY = BUILDER.comment("Require at least one attacking citizen within attackerPresenceRadius for the capture timer to advance.")
                .define("requireAttackerNearby", true);
        WAR_ATTACKER_PRESENCE_RADIUS = BUILDER.comment("Horizontal/3D radius in blocks around the flag within which an attacker must remain.")
                .defineInRange("attackerPresenceRadius", 32, 4, 128);
        WAR_ABANDON_CANCEL_SECONDS = BUILDER.comment("Seconds an unattended capture flag stays paused before automatically cancelling (0 disables auto-cancel).")
                .defineInRange("abandonCancelSeconds", 180, 0, 3600);
        WAR_FLAG_NO_BUILD_RADIUS = BUILDER.comment("Horizontal block radius around an active flag where building/buckets are blocked to prevent entombing.")
                .defineInRange("flagNoBuildRadius", 3, 1, 8);
        WAR_MAX_ACTIVE_FLAGS_PER_PLAYER = BUILDER.comment("Maximum simultaneous active capture flags placed by a single player.")
                .defineInRange("maxActiveFlagsPerPlayer", 2, 1, 32);
        WAR_MAX_ACTIVE_FLAGS_PER_NATION = BUILDER.comment("Maximum simultaneous active capture flags across an entire attacking nation.")
                .defineInRange("maxActiveFlagsPerNation", 10, 1, 128);
        WAR_BORDER_ONLY = BUILDER.comment("Require chunk attacks to advance strictly along enemy borders / occupied frontline / frontier staging.")
                .define("borderOnly", true);
        WAR_ALLOW_DIAGONAL_ADVANCE = BUILDER.comment("Allow diagonal corner-touching chunks to count as valid frontline adjacency.")
                .define("allowDiagonalAdvance", false);
        WAR_FRONTIER_STAGING_DISTANCE_CHUNKS = BUILDER.comment("Maximum unclaimed wilderness chunks across which a frontline chunk can stage an initial border attack.")
                .defineInRange("frontierStagingDistanceChunks", 3, 0, 16);
        BUILDER.pop();

        BUILDER.push("protection");
        WAR_PROTECT_BUILDINGS = BUILDER.comment("Preserve OPAC and municipal building protections in enemy cities during war.")
                .define("protectBuildings", true);
        WAR_ALLOW_BLOCK_DESTRUCTION = BUILDER.comment("Allow attackers to destroy general city blocks outside War Capture Flags.")
                .define("allowGeneralBlockDestruction", false);
        WAR_ALLOW_CHEST_LOOTING = BUILDER.comment("Allow attackers to open enemy containers/chests in claimed or occupied chunks.")
                .define("allowChestLooting", false);
        WAR_PAUSE_WHEN_WINDOW_CLOSED = BUILDER.comment("Pause active capture timers and block new flags outside scheduled war hours.")
                .define("pauseWhenWarWindowClosed", true);
        WAR_REQUIRE_ONLINE_DEFENDERS_TO_START = BUILDER.comment("Require at least one online defender (or recent logout within grace period) to start a new flag.")
                .define("requireOnlineDefendersToStart", true);
        WAR_DEFENDER_LOGOUT_GRACE_SECONDS = BUILDER.comment("Grace window in seconds after the last defender logs out during which attackers can still press the frontline.")
                .defineInRange("defenderLogoutGraceSeconds", 300, 0, 3600);
        WAR_SPAWN_PROTECTION_RADIUS_CHUNKS = BUILDER.comment("Chunk radius around world spawn where war capture flags are forbidden.")
                .defineInRange("spawnProtectionRadiusChunks", 2, 0, 64);
        BUILDER.pop();

        BUILDER.push("schedule");
        WAR_SCHEDULE_ENABLED = BUILDER.comment("Enforce scheduled weekly war time windows.")
                .define("scheduleEnabled", true);
        WAR_TIMEZONE = BUILDER.comment("IANA timezone ID used to evaluate war windows (default: Europe/Istanbul).")
                .define("timezone", "Europe/Istanbul");
        WAR_MONDAY = BUILDER.define("monday", "");
        WAR_TUESDAY = BUILDER.define("tuesday", "");
        WAR_WEDNESDAY = BUILDER.define("wednesday", "");
        WAR_THURSDAY = BUILDER.define("thursday", "");
        WAR_FRIDAY = BUILDER.define("friday", "");
        WAR_SATURDAY = BUILDER.comment("Time ranges HH:mm-HH:mm (comma-separated if multiple). Default: 18:00-23:00.")
                .define("saturday", "18:00-23:00");
        WAR_SUNDAY = BUILDER.comment("Time ranges HH:mm-HH:mm (comma-separated if multiple). Default: 18:00-23:00.")
                .define("sunday", "18:00-23:00");
        BUILDER.pop();

        BUILDER.push("occupation");
        WAR_REQUIRE_PEACE_TREATY_FOR_ANNEXATION = BUILDER.comment("Require a mutually approved Peace Treaty to permanently annex occupied chunks.")
                .define("requirePeaceTreatyForAnnexation", true);
        WAR_ENABLE_OCCUPATION_OVERLAY = BUILDER.comment("Render occupied chunks and active capture flags on the Political World Map.")
                .define("enableOccupationOverlay", true);
        BUILDER.pop();

        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private TerritoryConfig() {}
}
