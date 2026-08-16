package com.savagelich.spawndistancebiomes.noise;

import com.savagelich.spawndistancebiomes.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Distance/direction/noise-gated climate shaping near world spawn.
 *
 * Layout (relative to spawn, distances modulated by a smooth value-noise so
 * boundaries are organic patches rather than clean circles):
 *   - inner band (0 .. innerRadius): temperate, flat, no oceans
 *   - outer band (innerRadius .. outerRadius): four directional quadrants
 *       NW = cold + flat, NE = cold + mountains,
 *       SW = hot  + flat, SE = hot  + mountains
 *   - beyond outerRadius: fades back to the world's regular Tectonic gen
 *
 * Values come from the NeoForge config (spawndistancebiomes.toml [spawn_zone]).
 */
public final class SpawnZone {

    private SpawnZone() {}

    public static volatile boolean GATING_ENABLED = true;
    public static volatile boolean BIOME_SWAP = true;

    // ---- band / noise geometry ----
    public static volatile double INNER_RADIUS = 1000.0;
    public static volatile double OUTER_RADIUS = 2500.0;
    public static volatile double TRANSITION = 200.0;
    public static volatile double BAND_TRANSITION = 150.0;
    public static volatile double NOISE_SCALE = 128.0;
    public static volatile double NOISE_STRENGTH = 0.2;

    // ---- inner (temperate flat) profile ----
    public static volatile double INNER_OCEAN = 0.0;
    public static volatile double INNER_TEMP_MULT = 0.4;
    public static volatile double INNER_TEMP_OFF = 0.3;
    public static volatile double INNER_VEG_MULT = 0.4;
    public static volatile double INNER_VEG_OFF = 0.0;
    public static volatile double INNER_VERTICAL = 0.35;
    public static volatile double INNER_FLAT = 0.8;

    // ---- directional extremes (outer band) ----
    public static volatile double COLD_TEMP = -0.3;
    public static volatile double HOT_TEMP = 0.8;
    public static volatile double FLAT_VERTICAL = 0.35;
    public static volatile double MOUNTAIN_VERTICAL = 1.0;
    public static volatile double FLAT_SKEW = 0.8;
    public static volatile double MOUNTAIN_SKEW = -0.5;

    private static volatile boolean knobsLoaded = false;

    public static void loadKnobs() {
        if (knobsLoaded) return;
        synchronized (SpawnZone.class) {
            if (knobsLoaded) return;
            try {
                GATING_ENABLED = Config.GATING_ENABLED.get();
                BIOME_SWAP = Config.BIOME_SWAP.get();
                INNER_RADIUS = Config.INNER_RADIUS.get();
                OUTER_RADIUS = Config.OUTER_RADIUS.get();
                TRANSITION = Config.TRANSITION.get();
                BAND_TRANSITION = Config.BAND_TRANSITION.get();
                NOISE_SCALE = Config.NOISE_SCALE.get();
                NOISE_STRENGTH = Config.NOISE_STRENGTH.get();
                INNER_OCEAN = Config.INNER_OCEAN.get();
                INNER_TEMP_MULT = Config.INNER_TEMP_MULT.get();
                INNER_TEMP_OFF = Config.INNER_TEMP_OFF.get();
                INNER_VEG_MULT = Config.INNER_VEG_MULT.get();
                INNER_VEG_OFF = Config.INNER_VEG_OFF.get();
                INNER_VERTICAL = Config.INNER_VERTICAL.get();
                INNER_FLAT = Config.INNER_FLAT.get();
                COLD_TEMP = Config.COLD_TEMP.get();
                HOT_TEMP = Config.HOT_TEMP.get();
                FLAT_VERTICAL = Config.FLAT_VERTICAL.get();
                MOUNTAIN_VERTICAL = Config.MOUNTAIN_VERTICAL.get();
                FLAT_SKEW = Config.FLAT_SKEW.get();
                MOUNTAIN_SKEW = Config.MOUNTAIN_SKEW.get();
            } catch (Throwable ignored) {
                // config not ready — keep defaults
            }
            knobsLoaded = true;
        }
    }

    // ---- spawn (cached) ----
    public static volatile double spawnX = 0.0;
    public static volatile double spawnZ = 0.0;
    private static volatile ServerLevel spawnSource = null;
    private static volatile boolean spawnLoaded = false;
    private static int distCounter = 0;

    private static void reloadSpawn() {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return;
        ServerLevel ow = srv.overworld();
        if (ow == null) return;
        if (spawnLoaded && ow == spawnSource) return;
        synchronized (SpawnZone.class) {
            if (spawnLoaded && ow == spawnSource) return;
            BlockPos sp = ow.getSharedSpawnPos();
            spawnX = sp.getX();
            spawnZ = sp.getZ();
            spawnSource = ow;
            spawnLoaded = true;
        }
    }

    /**
     * Single entry point: returns the distance-gated value for {@code knob} at
     * the given block position, blending from the original Tectonic value.
     */
    public static double compute(String knob, double original, int blockX, int blockZ) {
        if (!spawnLoaded || (++distCounter & 0x1FFF) == 0) reloadSpawn();
        double dx = blockX - spawnX;
        double dz = blockZ - spawnZ;
        double d = Math.sqrt(dx * dx + dz * dz);

        double nd = noiseDistance(dx, dz, d, blockX, blockZ);
        double fade = fade(nd);
        if (fade <= 0.0) return original;

        double near = target(knob, dx, dz, d, nd);
        return blend(original, near, fade);
    }

    // ---- noise / geometry ----

    private static double noiseDistance(double dx, double dz, double d, int bx, int bz) {
        if (NOISE_STRENGTH <= 0.0 || NOISE_SCALE <= 0.0) return d;
        double n = valueNoise(bx / NOISE_SCALE, bz / NOISE_SCALE) * 2.0 - 1.0; // [-1,1]
        return d / (1.0 + n * NOISE_STRENGTH);
    }

    /** Gating strength: 1 inside outer band, smooth falloff to 0 beyond. */
    private static double fade(double nd) {
        if (nd <= OUTER_RADIUS) return 1.0;
        double t = (nd - OUTER_RADIUS) / Math.max(1.0, TRANSITION);
        if (t >= 1.0) return 0.0;
        double s = 1.0 - t;
        return s * s;
    }

    /** 0 = inner band, 1 = outer (directional) band, smooth between. */
    private static double bandBlend(double nd) {
        if (nd <= INNER_RADIUS) return 0.0;
        double innerEdge = INNER_RADIUS + BAND_TRANSITION;
        if (nd >= innerEdge) return 1.0;
        double t = (nd - INNER_RADIUS) / Math.max(1.0, BAND_TRANSITION);
        return t * t * (3.0 - 2.0 * t); // smoothstep
    }

    private static double target(String knob, double dx, double dz, double d, double nd) {
        double b = bandBlend(nd);
        return switch (knob) {
            case "ocean_offset" -> INNER_OCEAN; // no oceans anywhere in the gated zone
            case "temperature_offset" -> lerp(INNER_TEMP_OFF, directionalTemp(dx, dz, d), b);
            case "temperature_multiplier" -> INNER_TEMP_MULT;
            case "vegetation_offset" -> INNER_VEG_OFF;
            case "vegetation_multiplier" -> INNER_VEG_MULT;
            case "vertical_scale" -> lerp(INNER_VERTICAL, directionalVertical(dx, dz, d), b);
            case "flat_terrain_skew" -> lerp(INNER_FLAT, directionalFlat(dx, dz, d), b);
            default -> 0.0;
        };
    }

    private static double directionalTemp(double dx, double dz, double d) {
        double south = dz / d; // -1 north .. +1 south
        return lerp(COLD_TEMP, HOT_TEMP, (south + 1.0) * 0.5);
    }

    private static double directionalVertical(double dx, double dz, double d) {
        double east = dx / d; // -1 west .. +1 east
        return lerp(FLAT_VERTICAL, MOUNTAIN_VERTICAL, (east + 1.0) * 0.5);
    }

    private static double directionalFlat(double dx, double dz, double d) {
        double east = dx / d;
        return lerp(FLAT_SKEW, MOUNTAIN_SKEW, (east + 1.0) * 0.5);
    }

    // ---- small math ----

    public static double blend(double original, double near, double fade) {
        return original * (1.0 - fade) + near * fade;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    // ---- deterministic value noise ----

    private static double valueNoise(double x, double z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double xf = x - xi, zf = z - zi;
        xf = xf * xf * (3.0 - 2.0 * xf);
        zf = zf * zf * (3.0 - 2.0 * zf);
        double a = hash(xi, zi);
        double b = hash(xi + 1, zi);
        double c = hash(xi, zi + 1);
        double d2 = hash(xi + 1, zi + 1);
        double u = a + (b - a) * xf;
        double v = c + (d2 - c) * xf;
        return u + (v - u) * zf; // [0,1]
    }

    private static double hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0x7fffffff) / 2147483647.0; // [0,1]
    }
}
