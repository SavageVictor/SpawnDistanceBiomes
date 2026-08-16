package com.savagelich.spawndistancebiomes.noise;

/**
 * Central configuration for the distance-gated climate/terrain shaping.
 *
 * For each Tectonic knob we override, this holds the "near spawn" target
 * value and the shared radius/fade math. The far value is always whatever
 * Tectonic's own config holds, so beyond {@link #RADIUS} the world reverts
 * to the player's normal Tectonic setup.
 *
 * Values are tunable constants for now; they will be wired to the YACL
 * band config once the mechanism is verified.
 */
public final class SpawnZone {

    private SpawnZone() {}

    /** Master switch for distance gating. The scanner toggles this to sample the ungated baseline. */
    public static volatile boolean GATING_ENABLED = true;

    /** Distance (blocks) from spawn over which the "temperate flat" zone fades out. */
    public static final double RADIUS = 2048.0;

    // ---- ConfigConstant knobs (Tectonic ConfigState.getValue) ----
    /** Positive values push terrain well above sea level (land).
     *  Tectonic: -0.8 = lots of ocean, -0.2 = no ocean biomes but still coast,
     *  ~+0.1 = actual land (plains/forest). */
    public static final double OCEAN_OFFSET_NEAR = 0.0;
    /** Positive values favor flat terrain (default is 0.1). */
    public static final double FLAT_TERRAIN_SKEW_NEAR = 0.8;
    /** Tectonic default is 1.125 (grand terrain). Lower flattens the land. */
    public static final double VERTICAL_SCALE_NEAR = 0.35;

    // ---- ConfigNoise knobs (Tectonic ConfigState.getNoiseState) ----
    // NoiseState = (scale, multiplier, offset). scale is left at Tectonic's
    // value; we compress (multiplier) and re-center (offset) the climate.
    public static final double TEMPERATURE_MULTIPLIER_NEAR = 0.4;
    public static final double TEMPERATURE_OFFSET_NEAR = 0.3;
    public static final double VEGETATION_MULTIPLIER_NEAR = 0.4;
    public static final double VEGETATION_OFFSET_NEAR = 0.0;

    // ---- Spawn (PoC: world origin; TODO: ServerLifecycleHooks + getSharedSpawnPos) ----
    public static volatile double spawnX = 0.0;
    public static volatile double spawnZ = 0.0;

    public static double distance(int blockX, int blockZ) {
        double dx = blockX - spawnX;
        double dz = blockZ - spawnZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Plateau fade: 1.0 until {@code plateauFraction} of radius, then smooth falloff to 0. */
    public static double edgeFade(double distance) {
        return edgeFade(distance, RADIUS);
    }

    public static double edgeFade(double distance, double radius) {
        if (radius <= 0.0 || distance >= radius) return 0.0;
        double plateau = radius * 0.7;
        if (distance <= plateau) return 1.0;
        double t = (distance - plateau) / (radius - plateau); // 0..1 across the falloff band
        double s = 1.0 - t;
        return s * s;
    }

    /** Linear interpolation of two values by fade (0 = original, 1 = near). */
    public static double blend(double original, double near, double fade) {
        return original * (1.0 - fade) + near * fade;
    }
}
