package com.savagelich.spawndistancebiomes.noise;

import com.savagelich.spawndistancebiomes.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Central configuration for the distance-gated climate/terrain shaping.
 *
 * Values are read from the NeoForge config (spawndistancebiomes.toml,
 * [spawn_zone] section) once at first use. Edit the config file and restart —
 * no JVM args needed. See {@link Config} for the defaults/descriptions.
 */
public final class SpawnZone {

    private SpawnZone() {}

    // Defaults below are reloaded from Config on first use.
    public static volatile boolean GATING_ENABLED = true;
    public static volatile boolean BIOME_SWAP = true;
    public static volatile double RADIUS = 2048.0;
    public static volatile double OCEAN_OFFSET_NEAR = 0.0;
    public static volatile double FLAT_TERRAIN_SKEW_NEAR = 0.8;
    public static volatile double VERTICAL_SCALE_NEAR = 0.35;
    public static volatile double TEMPERATURE_MULTIPLIER_NEAR = 0.4;
    public static volatile double TEMPERATURE_OFFSET_NEAR = 0.3;
    public static volatile double VEGETATION_MULTIPLIER_NEAR = 0.4;
    public static volatile double VEGETATION_OFFSET_NEAR = 0.0;

    private static volatile boolean knobsLoaded = false;

    /** Loads the [spawn_zone] values from Config (idempotent, thread-safe). */
    public static void loadKnobs() {
        if (knobsLoaded) return;
        synchronized (SpawnZone.class) {
            if (knobsLoaded) return;
            try {
                GATING_ENABLED = Config.GATING_ENABLED.get();
                BIOME_SWAP = Config.BIOME_SWAP.get();
                RADIUS = Config.RADIUS.get();
                OCEAN_OFFSET_NEAR = Config.OCEAN_OFFSET.get();
                FLAT_TERRAIN_SKEW_NEAR = Config.FLAT_TERRAIN_SKEW.get();
                VERTICAL_SCALE_NEAR = Config.VERTICAL_SCALE.get();
                TEMPERATURE_MULTIPLIER_NEAR = Config.TEMPERATURE_MULTIPLIER.get();
                TEMPERATURE_OFFSET_NEAR = Config.TEMPERATURE_OFFSET.get();
                VEGETATION_MULTIPLIER_NEAR = Config.VEGETATION_MULTIPLIER.get();
                VEGETATION_OFFSET_NEAR = Config.VEGETATION_OFFSET.get();
            } catch (Throwable ignored) {
                // config not ready — keep defaults
            }
            knobsLoaded = true;
        }
    }

    // ---- Spawn (real world spawn, cached for the hot path) ----
    public static volatile double spawnX = 0.0;
    public static volatile double spawnZ = 0.0;
    private static volatile ServerLevel spawnSource = null;
    private static volatile boolean spawnLoaded = false;
    private static int distCounter = 0;

    public static double distance(int blockX, int blockZ) {
        // Cache the spawn; re-check the server only every 8192 calls (handles
        // world reloads without a per-call ServerLifecycleHooks lookup).
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
}
