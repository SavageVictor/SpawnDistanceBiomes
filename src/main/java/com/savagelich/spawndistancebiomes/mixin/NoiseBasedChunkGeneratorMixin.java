package com.savagelich.spawndistancebiomes.mixin;

import com.savagelich.spawndistancebiomes.Config;
import com.savagelich.spawndistancebiomes.band.BiomeBandData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Post-generation terrain raiser. Runs after the entire worldgen pipeline
 * (noise → density → biome → surface). For columns within elevation-gated
 * bands that are below the target continentalness elevation, physically
 * moves terrain up and fills gaps with stone.
 *
 * Zero conflicts with any worldgen mod — runs after everything else.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorMixin {

    @Inject(method = "fillFromNoise", at = @At("RETURN"))
    private void afterFillFromNoise(CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (future == null) return;
        future.thenAccept(c -> spawndistancebiomes$process((LevelChunk) c));
    }

    @Unique
    private static void spawndistancebiomes$process(LevelChunk chunk) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        Level overworld = server.overworld();
        if (overworld == null || chunk.getLevel() != overworld) return;

        BlockPos spawn = overworld.getSharedSpawnPos();
        int seaLevel = overworld.getSeaLevel();
        int cx = chunk.getPos().getMinBlockX();
        int cz = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = chunk.getMaxBuildHeight() - 1;

        List<BiomeBandData> bands = spawndistancebiomes$getBands();
        if (bands.isEmpty()) return;

        for (int x = 0; x < 16; x++) {
            int wx = cx + x;
            for (int z = 0; z < 16; z++) {
                int wz = cz + z;
                double dist = Math.sqrt(Math.pow(wx - spawn.getX(), 2) + Math.pow(wz - spawn.getZ(), 2));

                // Find matching band for this column
                BiomeBandData band = null;
                for (BiomeBandData b : bands) {
                    if (b.maxDistance < 0 || dist <= b.maxDistance) {
                        band = b;
                        break;
                    }
                }
                if (band == null || !band.hasElevationRule) continue;

                // Compute target surface Y from continentalness
                // continentalness: -1.0 → ocean floor (Y~30), 0.0 → plains (Y~63), 0.5 → hills (Y~80), 1.0 → peaks (Y~120+)
                int targetY = spawndistancebiomes$contToY(band.targetContinentalness, seaLevel);

                // Find current surface
                int surfaceY = -1;
                for (int y = maxY; y >= minY; y--) {
                    BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir() && !state.is(Blocks.WATER)) {
                        surfaceY = y;
                        break;
                    }
                }
                if (surfaceY < 0 || surfaceY >= targetY) continue;

                // Raise terrain: move blocks up, fill gap with stone
                int raiseBy = targetY - surfaceY;
                int readFrom = surfaceY;
                int writeTo = targetY;

                // Move blocks upward from bottom to top (to avoid overwriting)
                for (int y = surfaceY; y >= minY; y--) {
                    BlockPos src = new BlockPos(x, y, z);
                    BlockPos dst = new BlockPos(x, y + raiseBy, z);
                    BlockState state = chunk.getBlockState(src);
                    if (dst.getY() <= maxY) {
                        chunk.setBlockState(dst, state, false);
                    }
                }

                // Fill vacated space with stone (or deepslate below Y=0)
                BlockState fillBlock = targetY - raiseBy < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                for (int y = surfaceY; y > surfaceY - raiseBy && y >= minY; y--) {
                    chunk.setBlockState(new BlockPos(x, y, z), fillBlock, false);
                }

                // Cap with dirt+grass at the new surface
                BlockPos newSurface = new BlockPos(x, targetY, z);
                chunk.setBlockState(newSurface, Blocks.GRASS_BLOCK.defaultBlockState(), false);
                BlockPos below = new BlockPos(x, targetY - 1, z);
                if (below.getY() >= minY) {
                    chunk.setBlockState(below, Blocks.DIRT.defaultBlockState(), false);
                }

                // Remove water above the raised surface
                for (int y = targetY + 1; y <= seaLevel && y <= maxY; y++) {
                    BlockPos wp = new BlockPos(x, y, z);
                    BlockState ws = chunk.getBlockState(wp);
                    if (ws.is(Blocks.WATER) || ws.is(Blocks.ICE) || ws.is(Blocks.FROSTED_ICE)) {
                        chunk.setBlockState(wp, Blocks.AIR.defaultBlockState(), false);
                    }
                }
            }
        }
    }

    /** Maps continentalness to approximate surface Y. */
    @Unique
    private static int spawndistancebiomes$contToY(double cont, int seaLevel) {
        // Rough mapping:
        // -1.0 → seaLevel - 32 (deep ocean floor)
        //  0.0 → seaLevel      (plains)
        //  0.5 → seaLevel + 20 (hills)
        //  1.0 → seaLevel + 80 (mountain peaks)
        return (int) (seaLevel + cont * 80.0);
    }

    @Unique
    private static volatile List<BiomeBandData> spawndistancebiomes$cachedBands = List.of();
    @Unique
    private static volatile long spawndistancebiomes$lastTick = -1;

    @Unique
    private static List<BiomeBandData> spawndistancebiomes$getBands() {
        MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
        long tick = srv != null ? srv.getTickCount() : 0;
        if (!spawndistancebiomes$cachedBands.isEmpty() && tick - spawndistancebiomes$lastTick <= 100)
            return spawndistancebiomes$cachedBands;

        synchronized (NoiseBasedChunkGeneratorMixin.class) {
            if (!spawndistancebiomes$cachedBands.isEmpty() && tick - spawndistancebiomes$lastTick <= 100)
                return spawndistancebiomes$cachedBands;
            List<BiomeBandData> bands = new ArrayList<>();
            for (String s : Config.SURFACE_BANDS.get()) {
                BiomeBandData b = BiomeBandData.parse(s);
                if (b != null) bands.add(b);
            }
            spawndistancebiomes$cachedBands = bands;
            spawndistancebiomes$lastTick = tick;
        }
        return spawndistancebiomes$cachedBands;
    }
}
