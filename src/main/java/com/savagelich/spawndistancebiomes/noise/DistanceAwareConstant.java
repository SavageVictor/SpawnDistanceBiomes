package com.savagelich.spawndistancebiomes.noise;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A leaf density function replacing Tectonic's {@code ConfigConstant} for the
 * gated knobs. The spatial logic (bands, direction, noise) lives in
 * {@link SpawnZone#compute}; this class just carries the knob key and the
 * original Tectonic value.
 *
 * Immutable and thread-safe.
 */
public class DistanceAwareConstant implements DensityFunction {

    private final String knob;
    private final double originalValue;

    public DistanceAwareConstant(String knob, double originalValue) {
        this.knob = knob;
        this.originalValue = originalValue;
    }

    @Override
    public double compute(FunctionContext context) {
        return SpawnZone.compute(knob, originalValue, context.blockX(), context.blockZ());
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        provider.fillAllDirectly(array, this);
    }

    @Override
    public double minValue() {
        return Math.min(originalValue, -2.0);
    }

    @Override
    public double maxValue() {
        return Math.max(originalValue, 2.0);
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
