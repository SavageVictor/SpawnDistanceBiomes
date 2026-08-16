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
    public static final ModConfigSpec.IntValue RADIUS;
    public static final ModConfigSpec.DoubleValue OCEAN_OFFSET;
    public static final ModConfigSpec.DoubleValue FLAT_TERRAIN_SKEW;
    public static final ModConfigSpec.DoubleValue VERTICAL_SCALE;
    public static final ModConfigSpec.DoubleValue TEMPERATURE_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue TEMPERATURE_OFFSET;
    public static final ModConfigSpec.DoubleValue VEGETATION_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue VEGETATION_OFFSET;

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
            "Distance-gated climate/terrain shaping near world spawn.",
            "These override Tectonic's density functions within 'radius' blocks of spawn.");
        GATING_ENABLED = builder.comment("Master switch for distance gating.").define("gating_enabled", true);
        BIOME_SWAP = builder.comment("Whether the biome allowlist post-filter runs.").define("biome_swap", true);
        RADIUS = builder.comment("Fade radius in blocks.").defineInRange("radius", 2048, 0, 100000);
        OCEAN_OFFSET = builder.comment("-0.8=ocean, -0.2=coast, ~0.0=land").defineInRange("ocean_offset", 0.0, -2.0, 2.0);
        FLAT_TERRAIN_SKEW = builder.comment("Higher = flatter terrain.").defineInRange("flat_terrain_skew", 0.8, -2.0, 2.0);
        VERTICAL_SCALE = builder.comment("Tectonic default 1.125 (grand); lower flattens land.").defineInRange("vertical_scale", 0.35, 0.0, 4.0);
        TEMPERATURE_MULTIPLIER = builder.comment("Compress temperature variation near spawn.").defineInRange("temperature_multiplier", 0.4, 0.0, 4.0);
        TEMPERATURE_OFFSET = builder.comment("Shift temperature (higher = hotter).").defineInRange("temperature_offset", 0.3, -2.0, 2.0);
        VEGETATION_MULTIPLIER = builder.comment("Compress humidity variation near spawn.").defineInRange("vegetation_multiplier", 0.4, 0.0, 4.0);
        VEGETATION_OFFSET = builder.comment("Shift humidity (lower = drier).").defineInRange("vegetation_offset", 0.0, -2.0, 2.0);
        builder.pop();

        SPEC = builder.build();
    }

    static boolean validateBand(Object obj) {
        // Accept any string — format is handled by BiomeBandData.parse()
        return obj instanceof String;
    }
}
