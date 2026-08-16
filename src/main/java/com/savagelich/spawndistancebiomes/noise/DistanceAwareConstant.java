package com.savagelich.spawndistancebiomes.noise;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A leaf density function replacing Tectonic's {@code ConfigConstant} for
 * distance-gated knobs (e.g. {@code ocean_offset}, {@code flat_terrain_skew}).
 *
 * Blends between the player's Tectonic value and the spawn-zone target with a
 * smooth quadratic fade — no hard circular cutoff.
 *
 * Immutable and thread-safe (all fields final).
 */
public class DistanceAwareConstant implements DensityFunction {

    private final double originalValue;
    private final double nearValue;
    private final double radius;

    public DistanceAwareConstant(double originalValue, double nearValue) {
        this(originalValue, nearValue, SpawnZone.RADIUS);
    }

    public DistanceAwareConstant(double originalValue, double nearValue, double radius) {
        this.originalValue = originalValue;
        this.nearValue = nearValue;
        this.radius = radius;
    }

    @Override
    public double compute(FunctionContext context) {
        double dist = SpawnZone.distance(context.blockX(), context.blockZ());
        double fade = SpawnZone.edgeFade(dist, radius);
        return SpawnZone.blend(originalValue, nearValue, fade);
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        // Value varies per position — cannot Arrays.fill.
        provider.fillAllDirectly(array, this);
    }

    @Override
    public double minValue() {
        return Math.min(originalValue, nearValue);
    }

    @Override
    public double maxValue() {
        return Math.max(originalValue, nearValue);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null; // runtime-only, never serialized
    }
}
