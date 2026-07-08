package com.savagelich.spawndistancebiomes.mixin;

import com.savagelich.spawndistancebiomes.Config;
import com.savagelich.spawndistancebiomes.SpawnDistanceBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

/**
 * Mixin into MultiNoiseBiomeSource to apply distance-from-spawn
 * biome gating on the overworld.
 *
 * Intercepts AFTER vanilla decides the biome, checks if it's allowed
 * at this distance from spawn, and substitutes the fallback if banned.
 */
@Mixin(MultiNoiseBiomeSource.class)
public class MultiNoiseBiomeSourceMixin {

    @Unique
    private List<BiomeBandData> spawndistancebiomes$bands = null;
    @Unique
    private long spawndistancebiomes$lastReloadTick = -1;

    @Inject(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("RETURN"), cancellable = true)
    private void afterGetNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler,
                                     CallbackInfoReturnable<Holder<Biome>> cir) {
        Holder<Biome> chosen = cir.getReturnValue();
        if (chosen == null) return;

        // Only gate the overworld's biome source
        if ((Object) this != SpawnDistanceBiomes.overworldBiomeSource) {
            return;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        Level overworld = server.overworld();
        if (overworld == null) return;

        int blockX = quartX << 2;
        int blockZ = quartZ << 2;
        BlockPos spawnPos = overworld.getSharedSpawnPos();

        double distance = Math.sqrt(
            Math.pow(blockX - spawnPos.getX(), 2) +
            Math.pow(blockZ - spawnPos.getZ(), 2)
        );

        BiomeBandData matchingBand = spawndistancebiomes$findBand(distance);

        if (matchingBand == null || matchingBand.allowsAll) {
            return; // No restriction — keep vanilla choice
        }

        if (matchingBand.allows(chosen)) {
            return; // Vanilla choice is allowed in this band
        }

        // Vanilla picked a biome not allowed here — substitute fallback
        Holder<Biome> fallback = spawndistancebiomes$resolveBiome(server, matchingBand.fallbackBiomeId);
        if (fallback != null) {
            cir.setReturnValue(fallback);
        }
    }

    @Unique
    private BiomeBandData spawndistancebiomes$findBand(double distance) {
        for (BiomeBandData band : spawndistancebiomes$getBands()) {
            if (band.maxDistance < 0 || distance <= band.maxDistance) {
                return band;
            }
        }
        return null;
    }

    @Unique
    private List<BiomeBandData> spawndistancebiomes$getBands() {
        long tick = spawndistancebiomes$currentTick();
        if (spawndistancebiomes$bands == null || tick - spawndistancebiomes$lastReloadTick > 100) {
            spawndistancebiomes$bands = spawndistancebiomes$parseBands(Config.BANDS.get());
            spawndistancebiomes$lastReloadTick = tick;
        }
        return spawndistancebiomes$bands;
    }

    @Unique
    private long spawndistancebiomes$currentTick() {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        return srv != null ? srv.getTickCount() : 0;
    }

    @Unique
    private List<BiomeBandData> spawndistancebiomes$parseBands(List<? extends String> configs) {
        List<BiomeBandData> result = new ArrayList<>();
        for (String cfg : configs) {
            BiomeBandData band = BiomeBandData.parse(cfg);
            if (band != null) result.add(band);
        }
        if (result.isEmpty()) {
            result.add(new BiomeBandData(-1, Set.of(), "minecraft:plains", true));
        }
        return result;
    }

    @Unique
    private Holder<Biome> spawndistancebiomes$resolveBiome(MinecraftServer server, String biomeId) {
        Registry<Biome> registry = server.overworld()
            .registryAccess()
            .registryOrThrow(Registries.BIOME);
        ResourceLocation rl = ResourceLocation.tryParse(biomeId);
        if (rl == null) return null;
        return registry.getHolder(ResourceKey.create(Registries.BIOME, rl)).orElse(null);
    }

    // --- Inner data class ---

    @Unique
    static class BiomeBandData {
        final int maxDistance;
        final Set<ResourceLocation> allowedBiomes;
        final String fallbackBiomeId;
        final boolean allowsAll;

        BiomeBandData(int maxDistance, Set<ResourceLocation> allowedBiomes, String fallback, boolean allowsAll) {
            this.maxDistance = maxDistance;
            this.allowedBiomes = allowedBiomes;
            this.fallbackBiomeId = fallback;
            this.allowsAll = allowsAll;
        }

        boolean allows(Holder<Biome> biome) {
            if (allowsAll) return true;
            return biome.unwrapKey()
                .map(k -> allowedBiomes.contains(k.location()))
                .orElse(false);
        }

        static BiomeBandData parse(String config) {
            String[] parts = config.split(";");
            if (parts.length < 2) return null;
            try {
                int maxDist = Integer.parseInt(parts[0].trim());
                String biomeList = parts[1].trim();
                String fallback = parts.length > 2 ? parts[2].trim() : "minecraft:plains";

                if ("*".equals(biomeList)) {
                    return new BiomeBandData(maxDist, Set.of(), fallback, true);
                }
                Set<ResourceLocation> biomes = new HashSet<>();
                for (String s : biomeList.split(",")) {
                    ResourceLocation rl = ResourceLocation.tryParse(s.trim());
                    if (rl != null) biomes.add(rl);
                }
                return new BiomeBandData(maxDist, biomes, fallback, false);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
