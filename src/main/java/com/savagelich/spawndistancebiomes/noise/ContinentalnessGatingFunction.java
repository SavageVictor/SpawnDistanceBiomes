package com.savagelich.spawndistancebiomes.noise;

import com.savagelich.spawndistancebiomes.Config;
import com.savagelich.spawndistancebiomes.band.BiomeBandData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Wraps a density function to blend continentalness toward a target
 * value for positions within distance-gated bands.
 *
 * Only activates for bands where useDensityGating=true.
 * Compatible with Tectonic, Natural Temperature — wraps whatever
 * continentalness function they set up.
 */
public class ContinentalnessGatingFunction implements DensityFunction {

    private final DensityFunction delegate;
    private static volatile long lastTick = -1;
    private static volatile int maxDist = -1;
    private static volatile double targetCont, blendStr, spawnX, spawnZ;

    public ContinentalnessGatingFunction(DensityFunction delegate) {
        this.delegate = delegate;
    }

    @Override
    public double compute(FunctionContext ctx) {
        double value = delegate.compute(ctx);
        reloadCache();
        if (maxDist <= 0 || blendStr <= 0) return value;

        int bx = ctx.blockX(), bz = ctx.blockZ();
        double dist = Math.sqrt(Math.pow(bx - spawnX, 2) + Math.pow(bz - spawnZ, 2));
        if (dist > maxDist) return value;

        double edgeFade = 1.0 - Math.min(1.0, dist / (maxDist * 0.9));
        edgeFade = edgeFade * edgeFade;
        return value * (1.0 - edgeFade * blendStr) + targetCont * edgeFade * blendStr;
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        delegate.fillArray(array, provider);
        reloadCache();
        if (maxDist <= 0 || blendStr <= 0) return;
        for (int i = 0; i < array.length; i++) {
            FunctionContext ctx = provider.forIndex(i);
            int bx = ctx.blockX(), bz = ctx.blockZ();
            double dist = Math.sqrt(Math.pow(bx - spawnX, 2) + Math.pow(bz - spawnZ, 2));
            if (dist <= maxDist) {
                double edgeFade = 1.0 - Math.min(1.0, dist / (maxDist * 0.9));
                edgeFade = edgeFade * edgeFade;
                array[i] = array[i] * (1.0 - edgeFade * blendStr) + targetCont * edgeFade * blendStr;
            }
        }
    }

    @Override public double minValue() { return Math.min(delegate.minValue(), targetCont); }
    @Override public double maxValue() { return Math.max(delegate.maxValue(), targetCont); }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return null; }
    @Override public DensityFunction mapAll(Visitor v) {
        DensityFunction m = delegate.mapAll(v);
        return m == delegate ? this : new ContinentalnessGatingFunction(m);
    }

    private static void reloadCache() {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        long tick = srv != null ? srv.getTickCount() : 0;
        if (maxDist >= 0 && tick - lastTick <= 100) return;
        synchronized (ContinentalnessGatingFunction.class) {
            if (maxDist >= 0 && tick - lastTick <= 100) return;
            lastTick = tick; maxDist = -1; targetCont = 0; blendStr = 0;
            if (srv == null) return;
            Level ow = srv.overworld(); if (ow == null) return;
            BlockPos sp = ow.getSharedSpawnPos();
            spawnX = sp.getX(); spawnZ = sp.getZ();
            for (String s : Config.SURFACE_BANDS.get()) {
                BiomeBandData b = BiomeBandData.parse(s);
                if (b != null && b.useDensityGating && b.maxDistance > 0 && b.blendStrength > 0 && b.targetContinentalness < 9.0) {
                    maxDist = b.maxDistance; targetCont = b.targetContinentalness; blendStr = b.blendStrength;
                    break;
                }
            }
        }
    }
}
