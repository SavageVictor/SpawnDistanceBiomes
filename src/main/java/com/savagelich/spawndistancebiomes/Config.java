package com.savagelich.spawndistancebiomes;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Configuration for spawn-distance biome bands.
 *
 * Each band defines a maximum distance from spawn (in blocks) and a list
 * of biomes allowed to generate within that range. Biomes not in the list
 * are replaced with the fallback.
 *
 * The first band matching the query distance is used. Bands are ordered
 * from inner to outer.
 */
public class Config {

    public static final ModConfigSpec SPEC;

    // --- Band definitions ---
    // Format: "maxDistance;biome1,biome2,biome3;fallback"
    // maxDistance == 0 means "use vanilla for this band"
    // fallback is the biome to substitute when vanilla picks a non-allowed biome

    static final List<String> DEFAULT_BANDS = List.of(
        // Inner band (0-512 blocks): safe starter biomes
        "512;minecraft:plains,minecraft:forest,minecraft:meadow,minecraft:river,minecraft:sunflower_plains,minecraft:flower_forest;minecraft:plains",
        // Mid band (512-1536): moderate biomes
        "1536;minecraft:plains,minecraft:forest,minecraft:meadow,minecraft:river,minecraft:taiga,minecraft:savanna,minecraft:swamp,minecraft:birch_forest,minecraft:dark_forest,minecraft:sunflower_plains,minecraft:flower_forest;minecraft:forest",
        // Outer band (1536-3072): challenging biomes added
        "3072;minecraft:plains,minecraft:forest,minecraft:meadow,minecraft:river,minecraft:taiga,minecraft:savanna,minecraft:swamp,minecraft:birch_forest,minecraft:dark_forest,minecraft:jungle,minecraft:bamboo_jungle,minecraft:sparse_jungle,minecraft:badlands,minecraft:wooded_badlands,minecraft:eroded_badlands,minecraft:ice_spikes,minecraft:snowy_plains,minecraft:frozen_peaks,minecraft:jagged_peaks,minecraft:stony_peaks,minecraft:sunflower_plains,minecraft:flower_forest;minecraft:plains",
        // Infinite band: all overworld biomes unlocked (delegate to vanilla)
        "-1;*;minecraft:plains"
    );

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BANDS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Spawn Distance Biomes Configuration",
            "Each band entry format: \"maxDistance;biome1,biome2,...;fallbackBiome\"",
            "maxDistance = block distance from spawn. -1 = infinite.",
            "Use '*' to allow all biomes (delegates to vanilla noise).",
            "fallbackBiome = biome used when vanilla noise picks a banned biome.",
            "Bands are checked in order from innermost to outermost.",
            "First matching band (where distance <= maxDistance) wins.");

        BANDS = builder
            .comment("Ordered list of biome bands from inner to outer")
            .defineListAllowEmpty("bands", DEFAULT_BANDS, () -> "1024;minecraft:plains;minecraft:plains",
                Config::validateBand);

        SPEC = builder.build();
    }

    private static boolean validateBand(Object obj) {
        if (!(obj instanceof String band)) return false;
        String[] parts = band.split(";");
        if (parts.length < 2 || parts.length > 3) return false;
        try {
            int maxDist = Integer.parseInt(parts[0]);
            if (maxDist < -1) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        // biome list (parts[1]) can be "*" or a comma-separated list
        // fallback (parts[2]) is optional, defaults to "minecraft:plains"
        return true;
    }
}
