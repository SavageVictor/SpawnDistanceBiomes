package com.savagelich.spawndistancebiomes.noise;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Central configuration for the distance-gated climate/terrain shaping.
 *
 * Every value is read from a system property (with a sensible default) so the
 * spawn-zone profile can be tuned from the command line without recompiling:
 *
 *   ./gradlew runServer -Psdb.temperatureOffset=0.8 -Psdb.vegetationOffset=-0.5 \
 *                        -Psdb.biomeSwap=false -Psdb.oceanOffset=0.0
 *
 * System properties (all optional, defaults shown):
 *   sdb.gating                true        master switch for distance gating
 *   sdb.biomeSwap             true        allowlist post-filter on/off
 *   sdb.radius                2048        fade radius (blocks)
 *   sdb.oceanOffset           0.0         -0.8=ocean, -0.2=coast, ~0.0=land
 *   sdb.flatTerrainSkew       0.8         higher = flatter
 *   sdb.verticalScale         0.35        Tectonic default 1.125 (grand)
 *   sdb.temperatureMultiplier 0.4         compress temperature variation
 *   sdb.temperatureOffset     0.3         shift temperature (higher = hotter)
 *   sdb.vegetationMultiplier  0.4         compress humidity variation
 *   sdb.vegetationOffset      0.0         shift humidity (lower = drier)
 */
public final class SpawnZone {

    private SpawnZone() {}

    /** Master switch for distance gating. */
    public static final boolean GATING_ENABLED = boolProp("sdb.gating", true);

    /** Whether the biome allowlist post-filter runs. */
    public static final boolean BIOME_SWAP = boolProp("sdb.biomeSwap", true);

    /** Distance (blocks) from spawn over which the spawn zone fades out. */
    public static final double RADIUS = prop("sdb.radius", 2048.0);

    // ---- ConfigConstant knobs (Tectonic ConfigState.getValue) ----
    public static final double OCEAN_OFFSET_NEAR = prop("sdb.oceanOffset", 0.0);
    public static final double FLAT_TERRAIN_SKEW_NEAR = prop("sdb.flatTerrainSkew", 0.8);
    public static final double VERTICAL_SCALE_NEAR = prop("sdb.verticalScale", 0.35);

    // ---- ConfigNoise knobs (Tectonic ConfigState.getNoiseState) ----
    public static final double TEMPERATURE_MULTIPLIER_NEAR = prop("sdb.temperatureMultiplier", 0.4);
    public static final double TEMPERATURE_OFFSET_NEAR = prop("sdb.temperatureOffset", 0.3);
    public static final double VEGETATION_MULTIPLIER_NEAR = prop("sdb.vegetationMultiplier", 0.4);
    public static final double VEGETATION_OFFSET_NEAR = prop("sdb.vegetationOffset", 0.0);

    // ---- Spawn (real world spawn, cached for the hot path) ----
    public static volatile double spawnX = 0.0;
    public static volatile double spawnZ = 0.0;
    private static volatile ServerLevel spawnSource = null;
    private static volatile boolean spawnLoaded = false;
    private static int distCounter = 0;

    public static double distance(int blockX, int blockZ) {
        // Cache the spawn; re-check the server only every 8192 calls (handles
        // world reloads without a per-call ServerLifecycleHooks lookup, which
        // otherwise dominates chunk-gen time).
        if (!spawnLoaded || (++distCounter & 0x1FFF) == 0) {
            reloadSpawn();
        }
        double dx = blockX - spawnX;
        double dz = blockZ - spawnZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

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

    /** Plateau fade: 1.0 until 70% of radius, then smooth falloff to 0. */
    public static double edgeFade(double distance) {
        return edgeFade(distance, RADIUS);
    }

    public static double edgeFade(double distance, double radius) {
        if (radius <= 0.0 || distance >= radius) return 0.0;
        double plateau = radius * 0.7;
        if (distance <= plateau) return 1.0;
        double t = (distance - plateau) / (radius - plateau);
        double s = 1.0 - t;
        return s * s;
    }

    /** Linear interpolation of two values by fade (0 = original, 1 = near). */
    public static double blend(double original, double near, double fade) {
        return original * (1.0 - fade) + near * fade;
    }

    private static double prop(String key, double defaultValue) {
        return Double.parseDouble(System.getProperty(key, Double.toString(defaultValue)));
    }

    private static boolean boolProp(String key, boolean defaultValue) {
        return !"false".equalsIgnoreCase(System.getProperty(key, Boolean.toString(defaultValue)));
    }
}
