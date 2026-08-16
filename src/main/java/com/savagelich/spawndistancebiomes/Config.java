package com.savagelich.spawndistancebiomes;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for spawn-distance biome bands with elevation control.
 *
 * Band format (5 fields): "maxDistance;targetContinentalness;blendStrength;biomes;fallbackBiome"
 *   maxDistance:          block distance from spawn (-1 = infinite)
 *   targetContinentalness: terrain elevation target.
 *     -1.0 = deep ocean floor, 0.0 = plains, 0.5 = hills, 1.0 = peaks
 *     Use 9.0 to disable elevation gating for this band
 *   blendStrength:        0.0 = no elevation change, 1.0 = full enforcement
 *   biomes:               comma-separated IDs or "*" for all
 *   fallbackBiome:        substitution when vanilla picks a banned biome
 *
 * Old 3-field format is still supported: "maxDistance;biomes;fallbackBiome"
 */
public class Config {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SURFACE_BANDS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CAVE_BANDS;
    public static final ModConfigSpec.IntValue SURFACE_THRESHOLD_Y;

    // spawn_zone climate/terrain knobs (see SpawnZone)
    public static final ModConfigSpec.BooleanValue GATING_ENABLED;
    public static final ModConfigSpec.BooleanValue BIOME_SWAP;
    public static final ModConfigSpec.IntValue INNER_RADIUS;
    public static final ModConfigSpec.IntValue OUTER_RADIUS;
    public static final ModConfigSpec.IntValue TRANSITION;
    public static final ModConfigSpec.IntValue BAND_TRANSITION;
    public static final ModConfigSpec.DoubleValue NOISE_SCALE;
    public static final ModConfigSpec.DoubleValue NOISE_STRENGTH;
    public static final ModConfigSpec.DoubleValue INNER_OCEAN;
    public static final ModConfigSpec.DoubleValue INNER_TEMP_MULT;
    public static final ModConfigSpec.DoubleValue INNER_TEMP_OFF;
    public static final ModConfigSpec.DoubleValue INNER_VEG_MULT;
    public static final ModConfigSpec.DoubleValue INNER_VEG_OFF;
    public static final ModConfigSpec.DoubleValue INNER_VERTICAL;
    public static final ModConfigSpec.DoubleValue INNER_FLAT;
    public static final ModConfigSpec.DoubleValue COLD_TEMP;
    public static final ModConfigSpec.DoubleValue HOT_TEMP;
    public static final ModConfigSpec.DoubleValue FLAT_VERTICAL;
    public static final ModConfigSpec.DoubleValue MOUNTAIN_VERTICAL;
    public static final ModConfigSpec.DoubleValue FLAT_SKEW;
    public static final ModConfigSpec.DoubleValue MOUNTAIN_SKEW;

    // Default: 3 bands with elevation gating, 1 pass-through
    static final List<String> DEFAULT_SURFACE = new ArrayList<>(List.of(
        // Inner: safe land biomes + natural water-edge transitions
        "512;-0.1;1.0;0;minecraft:plains,minecraft:forest,minecraft:meadow,minecraft:river,minecraft:beach,minecraft:swamp,minecraft:sunflower_plains,minecraft:flower_forest,minecraft:birch_forest,minecraft:dark_forest,minecraft:stony_shore;minecraft:plains",
        // Mid: moderate biomes, slightly hilly
        "1536;0.0;0.6;0;minecraft:plains,minecraft:forest,minecraft:meadow,minecraft:river,minecraft:beach,minecraft:swamp,minecraft:taiga,minecraft:savanna,minecraft:birch_forest,minecraft:dark_forest,minecraft:sunflower_plains,minecraft:flower_forest,minecraft:stony_shore;minecraft:forest",
        // Outer: challenging biomes, full terrain
        "3072;9.0;0.0;0;minecraft:plains,minecraft:forest,minecraft:meadow,minecraft:river,minecraft:beach,minecraft:swamp,minecraft:taiga,minecraft:savanna,minecraft:birch_forest,minecraft:dark_forest,minecraft:jungle,minecraft:bamboo_jungle,minecraft:sparse_jungle,minecraft:badlands,minecraft:wooded_badlands,minecraft:eroded_badlands,minecraft:ice_spikes,minecraft:snowy_plains,minecraft:frozen_peaks,minecraft:jagged_peaks,minecraft:stony_peaks,minecraft:sunflower_plains,minecraft:flower_forest,minecraft:stony_shore,minecraft:cherry_grove,minecraft:snowy_taiga;minecraft:plains",
        // Infinite: all biomes
        "-1;9.0;0.0;0;*;minecraft:plains"
    ));

    static final List<String> DEFAULT_CAVE = new ArrayList<>(List.of(
        "-1;9.0;0.0;0;*;minecraft:plains"
    ));

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("general");
        SURFACE_THRESHOLD_Y = builder
            .comment("Y-level dividing surface from underground.",
                "Y >= threshold uses surface_bands, Y < threshold uses cave_bands.")
            .defineInRange("surface_threshold_y", 0, -64, 320);
        builder.pop();

        builder.push("surface_bands").comment(
            "Gating for biomes at or above the surface threshold.",
            "Format: maxDist;targetContinentalness;blendStrength;useDensityGating;biomes;fallback",
            "  useDensityGating: 0=off, 1=on (wraps continentalness density function)",
            "  blendStrength: 0.0=none, 1.0=full enforcement",
            "Old 3-field format (\"maxDist;biomes;fallback\") still works.");

        SURFACE_BANDS = builder
            .comment("Ordered list of surface biome bands (inner to outer)")
            .defineListAllowEmpty("bands", DEFAULT_SURFACE, () -> "1024;0.0;1.0;minecraft:plains;minecraft:plains",
                Config::validateBand);
        builder.pop();

        builder.push("cave_bands").comment(
            "Gating for biomes below the surface threshold.",
            "Format same as surface_bands. Leave at \"-1;9.0;0.0;*;plains\" to disable.");

        CAVE_BANDS = builder
            .comment("Ordered list of cave biome bands (inner to outer)")
            .defineListAllowEmpty("bands", DEFAULT_CAVE, () -> "-1;9.0;0.0;*;minecraft:plains",
                Config::validateBand);
        builder.pop();

        builder.push("spawn_zone").comment(
            "Distance/direction/noise-gated climate/terrain shaping near spawn.",
            "Inner band (0..inner_radius): temperate flat. Outer band (inner_radius..outer_radius):",
            "four directional quadrants (NW cold+flat, NE cold+mountains, SW hot+flat, SE hot+mountains).",
            "Beyond outer_radius the world fades back to regular Tectonic generation.");
        GATING_ENABLED = builder.comment("Master switch for distance gating.").define("gating_enabled", true);
        BIOME_SWAP = builder.comment("Whether the biome allowlist post-filter runs.").define("biome_swap", true);
        INNER_RADIUS = builder.comment("Inner band radius (blocks).").defineInRange("inner_radius", 1000, 0, 100000);
        OUTER_RADIUS = builder.comment("Outer band radius (blocks).").defineInRange("outer_radius", 2500, 0, 100000);
        TRANSITION = builder.comment("Fade-out distance beyond outer_radius (blocks).").defineInRange("transition", 200, 1, 10000);
        BAND_TRANSITION = builder.comment("Smooth transition distance between inner and outer band (blocks).").defineInRange("band_transition", 150, 1, 10000);
        NOISE_SCALE = builder.comment("Noise cell size for organic boundaries (blocks).").defineInRange("noise_scale", 128.0, 1.0, 10000.0);
        NOISE_STRENGTH = builder.comment("How much the boundary wobbles (0 = perfect circle).").defineInRange("noise_strength", 0.2, 0.0, 1.0);
        INNER_OCEAN = builder.comment("Ocean offset everywhere in the gated zone (no oceans).").defineInRange("inner_ocean_offset", 0.0, -2.0, 2.0);
        INNER_TEMP_MULT = builder.comment("Temperature multiplier (compress).").defineInRange("inner_temperature_multiplier", 0.4, 0.0, 4.0);
        INNER_TEMP_OFF = builder.comment("Temperature offset (higher = hotter).").defineInRange("inner_temperature_offset", 0.3, -2.0, 2.0);
        INNER_VEG_MULT = builder.comment("Vegetation multiplier (compress).").defineInRange("inner_vegetation_multiplier", 0.4, 0.0, 4.0);
        INNER_VEG_OFF = builder.comment("Vegetation offset (lower = drier).").defineInRange("inner_vegetation_offset", 0.0, -2.0, 2.0);
        INNER_VERTICAL = builder.comment("Vertical scale (lower = flatter).").defineInRange("inner_vertical_scale", 0.35, 0.0, 4.0);
        INNER_FLAT = builder.comment("Flat terrain skew (higher = flatter).").defineInRange("inner_flat_terrain_skew", 0.8, -2.0, 2.0);
        COLD_TEMP = builder.comment("Temperature offset in the north (cold).").defineInRange("cold_temperature_offset", -0.3, -2.0, 2.0);
        HOT_TEMP = builder.comment("Temperature offset in the south (hot).").defineInRange("hot_temperature_offset", 0.8, -2.0, 2.0);
        FLAT_VERTICAL = builder.comment("Vertical scale in the west (flat).").defineInRange("flat_vertical_scale", 0.35, 0.0, 4.0);
        MOUNTAIN_VERTICAL = builder.comment("Vertical scale in the east (mountains).").defineInRange("mountain_vertical_scale", 1.0, 0.0, 4.0);
        FLAT_SKEW = builder.comment("Flat skew in the west (flat).").defineInRange("flat_skew", 0.8, -2.0, 2.0);
        MOUNTAIN_SKEW = builder.comment("Flat skew in the east (mountains).").defineInRange("mountain_skew", -0.5, -2.0, 2.0);
        builder.pop();

        SPEC = builder.build();
    }

    static boolean validateBand(Object obj) {
        // Accept any string — format is handled by BiomeBandData.parse()
        return obj instanceof String;
    }
}
