package com.savagelich.spawndistancebiomes.mixin;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.savagelich.spawndistancebiomes.Config;
import com.savagelich.spawndistancebiomes.band.BiomeBandData;
import com.savagelich.spawndistancebiomes.noise.SpawnZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {

    @Unique private static final Logger LOGGER = LogUtils.getLogger();
    @Unique private volatile List<BiomeBandData> spawndistancebiomes$surfaceBands;
    @Unique private volatile List<BiomeBandData> spawndistancebiomes$caveBands;
    @Unique private volatile long spawndistancebiomes$lastReloadTick = -1;
    @Unique private static volatile MultiNoiseBiomeSource spawndistancebiomes$knownOverworldSource;
    @Unique private static volatile long spawndistancebiomes$logThrottle = 0;
    @Unique private static volatile boolean spawndistancebiomes$discovered = false;

    @Accessor
    abstract Either<Climate.ParameterList<Holder<Biome>>, Holder<net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList>> getParameters();

    @Inject(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("HEAD"), cancellable = true)
    private void beforeGetNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler,
                                      CallbackInfoReturnable<Holder<Biome>> cir) {
        if (!SpawnZone.GATING_ENABLED) return; // ungated baseline sampling
        if (!SpawnZone.BIOME_SWAP) return;     // allowlist swap disabled — climate shaping only
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        // Identity check
        if ((Object) this != spawndistancebiomes$knownOverworldSource) {
            if (!spawndistancebiomes$discoverOverworldSource()) {
                // Log throttled every ~5 seconds if we keep failing
                if (server.getTickCount() - spawndistancebiomes$logThrottle > 100) {
                    LOGGER.warn("Mixin active but NOT the overworld source — gating disabled. knownOverworldSource={}", spawndistancebiomes$knownOverworldSource);
                    spawndistancebiomes$logThrottle = server.getTickCount();
                }
                return;
            }
        }

        Level overworld = server.overworld();
        if (overworld == null) return;

        int blockY = quartY << 2;
        boolean isSurface = blockY >= Config.SURFACE_THRESHOLD_Y.getAsInt();
        List<BiomeBandData> bands = isSurface
            ? spawndistancebiomes$getSurfaceBands()
            : spawndistancebiomes$getCaveBands();
        if (bands == null) return;

        int blockX = quartX << 2;
        int blockZ = quartZ << 2;
        BlockPos spawnPos = overworld.getSharedSpawnPos();
        double distance = Math.sqrt(Math.pow(blockX - spawnPos.getX(), 2) + Math.pow(blockZ - spawnPos.getZ(), 2));

        BiomeBandData band = spawndistancebiomes$findBand(bands, distance);
        if (band == null || band.allowsAll) return;

        // Climate/terrain are shaped by the Tectonic density-function mixins
        // (ocean_offset, flat_terrain_skew, temperature, vegetation), so biome
        // selection here is mostly correct already. Keep only a thin allowlist
        // post-filter — no continentalness steering (that caused rivers to
        // become land).
        Climate.TargetPoint original = sampler.sample(quartX, quartY, quartZ);
        Holder<Biome> result = ((MultiNoiseBiomeSource) (Object) this).getNoiseBiome(original);

        if (!band.allows(result)) {
            Holder<Biome> fallback = spawndistancebiomes$findClosestAllowed(original, band.allowedBiomes);
            if (fallback != null) {
                result = fallback;
            }
        }

        cir.setReturnValue(result);
    }

    @Unique private float[] spawndistancebiomes$unpack(Climate.TargetPoint tp) {
        return new float[]{Climate.unquantizeCoord(tp.temperature()), Climate.unquantizeCoord(tp.humidity()),
            Climate.unquantizeCoord(tp.continentalness()), Climate.unquantizeCoord(tp.erosion()),
            Climate.unquantizeCoord(tp.depth()), Climate.unquantizeCoord(tp.weirdness())};
    }

    @Unique private Holder<Biome> spawndistancebiomes$findClosestAllowed(Climate.TargetPoint tp, Set<ResourceLocation> allowed) {
        if (allowed.isEmpty()) return null;
        Either<Climate.ParameterList<Holder<Biome>>, Holder<net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList>> params = getParameters();
        Climate.ParameterList<Holder<Biome>> pl = params.left().orElseGet(() -> params.right().orElseThrow().value().parameters());
        float[] target = spawndistancebiomes$unpack(tp);
        Holder<Biome> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Pair<Climate.ParameterPoint, Holder<Biome>> e : pl.values()) {
            if (e.getSecond().unwrapKey().map(k -> allowed.contains(k.location())).orElse(false)) {
                Climate.ParameterPoint pp = e.getFirst();
                float dx = target[0] - Climate.unquantizeCoord(pp.temperature().min());
                float dy = target[1] - Climate.unquantizeCoord(pp.humidity().min());
                float dz = target[2] - Climate.unquantizeCoord(pp.continentalness().min());
                float dw = target[3] - Climate.unquantizeCoord(pp.erosion().min());
                float dv = target[4] - Climate.unquantizeCoord(pp.depth().min());
                float du = target[5] - Climate.unquantizeCoord(pp.weirdness().min());
                double dist = dx*dx + dy*dy + dz*dz + dw*dw + dv*dv + du*du;
                if (dist < bestDist) { bestDist = dist; best = e.getSecond(); }
            }
        }
        return best;
    }

    @Unique private boolean spawndistancebiomes$discoverOverworldSource() {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        if (srv == null) return false;
        ServerLevel ow = srv.overworld(); if (ow == null) return false;
        Object src = ow.getChunkSource().getGenerator().getBiomeSource();
        if (src instanceof MultiNoiseBiomeSource mn && (Object) mn == this) {
            spawndistancebiomes$knownOverworldSource = mn;
            if (!spawndistancebiomes$discovered) {
                LOGGER.info("DISCOVERED overworld biome source at {}", Integer.toHexString(System.identityHashCode(mn)));
                spawndistancebiomes$discovered = true;
            }
            return true;
        }
        return false;
    }

    @Unique private volatile long spawndistancebiomes$lastReloadLog = 0;

    @Unique private List<BiomeBandData> spawndistancebiomes$getSurfaceBands() {
        long tick = spawndistancebiomes$currentTick();
        if (spawndistancebiomes$surfaceBands == null || tick - spawndistancebiomes$lastReloadTick > 100) {
            synchronized (this) {
                if (spawndistancebiomes$surfaceBands == null || tick - spawndistancebiomes$lastReloadTick > 100) {
                    var surface = Config.SURFACE_BANDS.get();
                    var cave = Config.CAVE_BANDS.get();
                    if (surface.size() != (spawndistancebiomes$surfaceBands == null ? -1 : spawndistancebiomes$surfaceBands.size())
                        && tick - spawndistancebiomes$lastReloadLog > 200) {
                        LOGGER.info("Reloading bands: surface={} cave={}", surface.size(), cave.size());
                        spawndistancebiomes$lastReloadLog = tick;
                    }
                    spawndistancebiomes$surfaceBands = parse(surface);
                    spawndistancebiomes$caveBands = parse(cave);
                    spawndistancebiomes$lastReloadTick = tick;
                }
            }
        }
        return spawndistancebiomes$surfaceBands;
    }

    @Unique private List<BiomeBandData> spawndistancebiomes$getCaveBands() { spawndistancebiomes$getSurfaceBands(); return spawndistancebiomes$caveBands; }
    @Unique private long spawndistancebiomes$currentTick() { var s = ServerLifecycleHooks.getCurrentServer(); return s != null ? s.getTickCount() : 0; }
    @Unique private BiomeBandData spawndistancebiomes$findBand(List<BiomeBandData> bands, double d) { if (bands==null) return null; for (BiomeBandData b : bands) if (b.maxDistance < 0 || d <= b.maxDistance) return b; return null; }
    @Unique private static List<BiomeBandData> parse(List<? extends String> c) { List<BiomeBandData> out = new ArrayList<>(); for (String s : c) { var b = BiomeBandData.parse(s); if (b != null) out.add(b); } if (out.isEmpty()) out.add(new BiomeBandData(-1, 9.0, 0.0, false, Set.of(), "minecraft:plains", true)); return out; }
}
