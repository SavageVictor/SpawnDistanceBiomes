package com.savagelich.spawndistancebiomes.mixin.tectonic;

import com.mojang.logging.LogUtils;
import com.savagelich.spawndistancebiomes.noise.DistanceAwareNoise;
import com.savagelich.spawndistancebiomes.noise.SpawnZone;
import dev.worldgen.tectonic.worldgen.densityfunction.ConfigNoise;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.IdentityHashMap;

/**
 * Makes Tectonic's config-backed climate noise (temperature, vegetation)
 * distance-aware, so biome selection shifts toward a temperate band near
 * spawn instead of being post-hoc swapped.
 */
@Mixin(ConfigNoise.class)
public class ConfigNoiseMixin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final IdentityHashMap<Object, String> KEY_BY_IDENTITY = new IdentityHashMap<>();
    private static volatile boolean logged = false;

    @Inject(method = "create", at = @At("RETURN"))
    private static void sdb$captureKey(String key,
                                       DensityFunction.NoiseHolder noise,
                                       DensityFunction shiftX,
                                       DensityFunction shiftZ,
                                       CallbackInfoReturnable<ConfigNoise> cir) {
        synchronized (KEY_BY_IDENTITY) {
            KEY_BY_IDENTITY.put(cir.getReturnValue(), key);
        }
    }

    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void sdb$mapAll(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        if (!SpawnZone.GATING_ENABLED) return; // ungated baseline sampling
        String key;
        synchronized (KEY_BY_IDENTITY) {
            key = KEY_BY_IDENTITY.get((Object) this);
        }

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

        if (!logged) {
            logged = true;
            LOGGER.info("[SpawnDistanceBiomes] Tectonic config_noise '{}' intercepted "
                + "(mult {}=>{}, offset {}=>{}, radius={})",
                key, self.multiplier(), nearMultiplier, self.offset(), nearOffset, SpawnZone.RADIUS);
        }

        cir.setReturnValue(new DistanceAwareNoise(
            self.noise(), self.shiftX(), self.shiftZ(),
            self.scale(), self.multiplier(), self.offset(),
            self.scale(), nearMultiplier, nearOffset, // scale unchanged
            SpawnZone.RADIUS, self.smootherScaling()));
    }
}
