package com.savagelich.spawndistancebiomes.noise;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Replaces Tectonic's {@code ConfigNoise} for distance-gated climate knobs
 * ({@code temperature}, {@code vegetation}).
 *
 * Mirrors ConfigNoise's sampling math but blends the {@code scale},
 * {@code multiplier} and {@code offset} toward spawn-zone targets using a
 * smooth quadratic fade, so climate (and therefore biome selection) shifts
 * naturally without a hard cutoff.
 *
 * Immutable and thread-safe (all fields final).
 */
public class DistanceAwareNoise implements DensityFunction {

    private final NoiseHolder noise;
    private final DensityFunction shiftX;
    private final DensityFunction shiftZ;

    private final double origScale;
    private final double origMultiplier;
    private final double origOffset;

    private final double nearScale;
    private final double nearMultiplier;
    private final double nearOffset;

    private final double radius;
    private final boolean smootherScaling;

    public DistanceAwareNoise(
            NoiseHolder noise,
            DensityFunction shiftX,
            DensityFunction shiftZ,
            double origScale, double origMultiplier, double origOffset,
            double nearScale, double nearMultiplier, double nearOffset,
            double radius,
            boolean smootherScaling) {
        this.noise = noise;
        this.shiftX = shiftX;
        this.shiftZ = shiftZ;
        this.origScale = origScale;
        this.origMultiplier = origMultiplier;
        this.origOffset = origOffset;
        this.nearScale = nearScale;
        this.nearMultiplier = nearMultiplier;
        this.nearOffset = nearOffset;
        this.radius = radius;
        this.smootherScaling = smootherScaling;
    }

    @Override
    public double compute(FunctionContext context) {
        double fade = SpawnZone.edgeFade(SpawnZone.distance(context.blockX(), context.blockZ()), radius);
        double scale = SpawnZone.blend(origScale, nearScale, fade);
        double multiplier = SpawnZone.blend(origMultiplier, nearMultiplier, fade);
        double offset = SpawnZone.blend(origOffset, nearOffset, fade);

        double x;
        double z;
        if (smootherScaling) {
            x = (context.blockX() + shiftX.compute(context)) * scale;
            z = (context.blockZ() + shiftZ.compute(context)) * scale;
        } else {
            x = context.blockX() * scale + shiftX.compute(context);
            z = context.blockZ() * scale + shiftZ.compute(context);
        }
        return noise.getValue(x, 0, z) * multiplier + offset;
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        provider.fillAllDirectly(array, this);
    }

    @Override
    public double minValue() {
        return -maxValue();
    }

    @Override
    public double maxValue() {
        double maxMult = Math.max(Math.abs(origMultiplier), Math.abs(nearMultiplier));
        double maxOff = Math.max(Math.abs(origOffset), Math.abs(nearOffset));
        return noise.maxValue() * maxMult + maxOff;
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new DistanceAwareNoise(
            visitor.visitNoise(noise),
            shiftX.mapAll(visitor),
            shiftZ.mapAll(visitor),
            origScale, origMultiplier, origOffset,
            nearScale, nearMultiplier, nearOffset,
            radius, smootherScaling);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return null; // runtime-only, never serialized
    }
}
