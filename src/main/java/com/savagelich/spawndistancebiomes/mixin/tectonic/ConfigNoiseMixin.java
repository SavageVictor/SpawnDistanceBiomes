package com.savagelich.spawndistancebiomes.mixin.tectonic;

import com.google.common.collect.MapMaker;
import com.mojang.logging.LogUtils;
import com.savagelich.spawndistancebiomes.noise.DistanceAwareConstant;
import com.savagelich.spawndistancebiomes.noise.SpawnZone;
import dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentMap;

/**
 * Makes Tectonic's config-backed climate noise (temperature, vegetation)
 * distance-aware, so biome selection shifts toward a hot/dry (or temperate)
 * band near spawn instead of being post-hoc swapped.
 *
 * IMPORTANT: we replicate Tectonic's own non-smoother mapAll structure
 * (shifted_noise2d wrapped in mul/add) instead of keeping a recursive
 * DistanceAwareNoise node — the latter keeps shiftX/shiftZ as children,
 * which form a holder cycle and infinite-recurse mapAll into OOM.
 */
@Mixin(ConfigNoise.class)
public class ConfigNoiseMixin {

    private static final Logger LOGGER = LogUtils.getLogger();
    // Identity semantics + weak keys, so ConfigNoise instances are GC-able
    // after their world's NoiseRouter is released (no permanent leak).
    private static final ConcurrentMap<Object, String> KEY_BY_IDENTITY =
        new MapMaker().weakKeys().makeMap();
    private static volatile boolean logged = false;

    @Inject(method = "create", at = @At("RETURN"))
    private static void sdb$captureKey(String key,
                                       DensityFunction.NoiseHolder noise,
                                       DensityFunction shiftX,
                                       DensityFunction shiftZ,
                                       CallbackInfoReturnable<ConfigNoise> cir) {
        KEY_BY_IDENTITY.put(cir.getReturnValue(), key);
    }

    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void sdb$mapAll(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        if (!SpawnZone.GATING_ENABLED) return; // ungated baseline sampling

        String key = KEY_BY_IDENTITY.get((Object) this);
        ConfigNoise self = (ConfigNoise) (Object) this;

        double nearMultiplier;
        double nearOffset;
        if ("temperature".equals(key)) {
            nearMultiplier = SpawnZone.TEMPERATURE_MULTIPLIER_NEAR;
            nearOffset = SpawnZone.TEMPERATURE_OFFSET_NEAR;
        } else if ("vegetation".equals(key)) {
            nearMultiplier = SpawnZone.VEGETATION_MULTIPLIER_NEAR;
            nearOffset = SpawnZone.VEGETATION_OFFSET_NEAR;
        } else {
            return; // not a gated climate knob
        }

        if (self.smootherScaling()) {
            return; // only temperature/vegetation (non-smoother) are gated
        }

        if (!logged) {
            logged = true;
            LOGGER.info("[SpawnDistanceBiomes] Tectonic config_noise '{}' intercepted "
                + "(mult {}=>{}, offset {}=>{}, radius={})",
                key, self.multiplier(), nearMultiplier, self.offset(), nearOffset, SpawnZone.RADIUS);
        }

        // Replicate Tectonic's non-smoother mapAll, but with distance-aware
        // multiplier/offset constants. shiftedNoise2d replaces the ConfigNoise
        // node (breaking any holder cycle) exactly like the original does.
        DensityFunction shifted = DensityFunctions.shiftedNoise2d(
            self.shiftX(), self.shiftZ(), self.scale(), self.noise().noiseData());
        DensityFunction result = DensityFunctions.add(
            DensityFunctions.mul(shifted, new DistanceAwareConstant(self.multiplier(), nearMultiplier)),
            new DistanceAwareConstant(self.offset(), nearOffset));
        cir.setReturnValue(result.mapAll(visitor));
    }
}
