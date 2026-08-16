package com.savagelich.spawndistancebiomes.mixin;

import com.savagelich.spawndistancebiomes.Config;
import com.savagelich.spawndistancebiomes.band.BiomeBandData;
import com.savagelich.spawndistancebiomes.noise.ContinentalnessGatingFunction;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Wraps Climate.Sampler.continentalness() with ContinentalnessGatingFunction
 * if any band has useDensityGating=true.
 */
@Mixin(NoiseChunk.class)
public class NoiseChunkMixin {

    @Inject(method = "cachedClimateSampler(Lnet/minecraft/world/level/levelgen/NoiseRouter;Ljava/util/List;)Lnet/minecraft/world/level/biome/Climate$Sampler;",
            at = @At("RETURN"), cancellable = true)
    private void wrapClimateSampler(NoiseRouter router, List<Climate.ParameterPoint> spawnTarget,
                                     CallbackInfoReturnable<Climate.Sampler> cir) {
        // Only wrap if at least one band enables density gating
        if (!spawndistancebiomes$anyDensityGatingEnabled()) return;

        Climate.Sampler original = cir.getReturnValue();
        DensityFunction gated = new ContinentalnessGatingFunction(original.continentalness());

        Climate.Sampler wrapped = new Climate.Sampler(
            original.temperature(), original.humidity(),
            gated,
            original.erosion(), original.depth(), original.weirdness(),
            original.spawnTarget()
        );
        cir.setReturnValue(wrapped);
    }

    private static boolean spawndistancebiomes$anyDensityGatingEnabled() {
        for (String s : Config.SURFACE_BANDS.get()) {
            BiomeBandData b = BiomeBandData.parse(s);
            if (b != null && b.useDensityGating && b.maxDistance > 0) return true;
        }
        return false;
    }
}
