package com.savagelich.spawndistancebiomes.mixin.tectonic;

import com.google.common.collect.MapMaker;
import com.mojang.logging.LogUtils;
import com.savagelich.spawndistancebiomes.noise.DistanceAwareConstant;
import com.savagelich.spawndistancebiomes.noise.SpawnZone;
import dev.worldgen.tectonic.worldgen.densityfunction.ConfigConstant;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentMap;

/**
 * Makes Tectonic's config-backed density constants distance-aware.
 *
 * Tectonic bakes config values into {@code final double value} at worldgen
 * init and erases the object during {@code mapAll()}. We capture the key at
 * {@code create()} and emit a {@link DistanceAwareConstant} from
 * {@code mapAll()} for the knobs we gate.
 */
@Mixin(ConfigConstant.class)
public class ConfigConstantMixin {

    private static final Logger LOGGER = LogUtils.getLogger();
    // Identity semantics + weak keys, so ConfigConstant instances are GC-able
    // after their world's NoiseRouter is released (no permanent leak).
    private static final ConcurrentMap<Object, String> KEY_BY_IDENTITY =
        new MapMaker().weakKeys().makeMap();
    private static volatile boolean logged = false;

    @Inject(method = "create", at = @At("RETURN"))
    private static void sdb$captureKey(String key, CallbackInfoReturnable<ConfigConstant> cir) {
        KEY_BY_IDENTITY.put(cir.getReturnValue(), key);
    }

    @Inject(method = "mapAll", at = @At("HEAD"), cancellable = true)
    private void sdb$mapAll(DensityFunction.Visitor visitor, CallbackInfoReturnable<DensityFunction> cir) {
        SpawnZone.loadKnobs();
        if (!SpawnZone.GATING_ENABLED) return; // ungated baseline sampling
        String key = KEY_BY_IDENTITY.get((Object) this);
        if (!isGated(key)) {
            return; // not a gated knob — let Tectonic's mapAll run unchanged
        }
        double original = ((ConfigConstant) (Object) this).value();
        if (!logged) {
            logged = true;
            LOGGER.info("[SpawnDistanceBiomes] Tectonic config_constant '{}' intercepted (original={})", key, original);
        }
        cir.setReturnValue(visitor.apply(new DistanceAwareConstant(key, original)));
    }

    private static boolean isGated(String key) {
        if (key == null) return false;
        return switch (key) {
            case "ocean_offset", "flat_terrain_skew", "vertical_scale" -> true;
            default -> false;
        };
    }
}
