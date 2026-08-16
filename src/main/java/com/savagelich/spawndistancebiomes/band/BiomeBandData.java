package com.savagelich.spawndistancebiomes.band;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.HashSet;
import java.util.Set;

/**
 * Parsed representation of one biome distance band from config.
 *
 * Format: "maxDistance;targetContinentalness;blendStrength;biome1,biome2,...;fallbackBiome"
 *
 * - maxDistance: block distance from spawn (-1 = infinite, 0 = disable)
 * - targetContinentalness: terrain elevation target (-1.0=ocean, 0.0=plains, 0.5=hills, 1.0=peaks). Use 9.0 to skip elevation gating.
 * - blendStrength: how strongly to enforce (0.0=none, 1.0=full). Use 0.0 to skip.
 * - biomes: comma-separated IDs or "*" for all
 * - fallback: substitution biome
 *
 * Backward compatible with old 3-field format: "maxDist;biomes;fallback"
 */
public class BiomeBandData {
    public int maxDistance;
    public double targetContinentalness = 9.0;
    public double blendStrength = 0.0;
    public boolean useDensityGating = false;
    public Set<ResourceLocation> allowedBiomes;
    public String fallbackBiomeId;
    public boolean allowsAll;
    public boolean hasElevationRule;

    public BiomeBandData(int maxDistance, double targetContinentalness, double blendStrength,
                         boolean useDensityGating, Set<ResourceLocation> allowedBiomes, String fallback, boolean allowsAll) {
        this.maxDistance = maxDistance;
        this.targetContinentalness = targetContinentalness;
        this.blendStrength = blendStrength;
        this.useDensityGating = useDensityGating;
        this.allowedBiomes = allowedBiomes;
        this.fallbackBiomeId = fallback;
        this.allowsAll = allowsAll;
        this.hasElevationRule = targetContinentalness != 9.0 && blendStrength > 0.0;
    }

    public boolean allows(Holder<Biome> biome) {
        if (allowsAll) return true;
        return biome.unwrapKey().map(k -> allowedBiomes.contains(k.location())).orElse(false);
    }

    /**
     * Parses config string. Supports both 5-field and 3-field (backward compat) formats.
     */
    public static BiomeBandData parse(String config) {
        String[] parts = config.split(";");
        if (parts.length < 2) return null;

        try {
            // Parse fields with backward compat
            int maxDist = Integer.parseInt(parts[0].trim());
            double targetCont = 9.0;
            double blend = 0.0;
            boolean useDensityGate = false;
            int biomeIdx, fallbackIdx;

            if (parts.length >= 6) {
                // 6-field: maxDist;targetCont;blend;densityGate;biomes;fallback
                targetCont = Double.parseDouble(parts[1].trim());
                blend = Double.parseDouble(parts[2].trim());
                useDensityGate = "1".equals(parts[3].trim()) || "true".equalsIgnoreCase(parts[3].trim());
                biomeIdx = 4;
                fallbackIdx = 5;
            } else if (parts.length >= 5) {
                // 5-field: maxDist;targetCont;blend;biomes;fallback
                targetCont = Double.parseDouble(parts[1].trim());
                blend = Double.parseDouble(parts[2].trim());
                biomeIdx = 3;
                fallbackIdx = 4;
            } else if (parts.length >= 3) {
                // Old 3-field: maxDist;biomes;fallback
                biomeIdx = 1;
                fallbackIdx = 2;
            } else {
                biomeIdx = 1;
                fallbackIdx = -1;
            }

            String biomeList = parts[biomeIdx].trim();
            String fallback = fallbackIdx >= 0 && fallbackIdx < parts.length
                ? parts[fallbackIdx].trim() : "minecraft:plains";

            if ("*".equals(biomeList)) {
                return new BiomeBandData(maxDist, targetCont, blend, useDensityGate, Set.of(), fallback, true);
            }

            Set<ResourceLocation> biomes = new HashSet<>();
            for (String s : biomeList.split(",")) {
                ResourceLocation rl = ResourceLocation.tryParse(s.trim());
                if (rl != null) biomes.add(rl);
            }
            return new BiomeBandData(maxDist, targetCont, blend, useDensityGate, biomes, fallback, false);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
