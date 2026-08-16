package com.savagelich.spawndistancebiomes.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Headless worldgen scanner. Samples the real vanilla worldgen pipeline —
 * {@code Climate.Sampler} (6 climate params), biome source, and
 * {@code getBaseHeight} (terrain elevation) — WITHOUT generating chunks.
 *
 * Two resolutions:
 *   - biome + climate at {@code step} (fast; 10 blocks = 10x10 patches)
 *   - terrain height at {@code heightStep} (getBaseHeight is the slow part)
 *
 * Outputs biomes.png, continentalness.png, height.png, scan.csv, heights.csv.
 */
public class WorldgenScanner {

    public void scan(ServerLevel level, int step, int heightStep, int radius, Path outDir) throws IOException {
        Files.createDirectories(outDir);

        var chunkSource = level.getChunkSource();
        RandomState randomState = chunkSource.randomState();
        NoiseRouter router = randomState.router();
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) chunkSource.getGenerator();
        BiomeSource biomeSource = generator.getBiomeSource();
        Climate.Sampler sampler = makeSampler(router, makeNoiseVisitor(randomState));

        BlockPos spawn = level.getSharedSpawnPos();
        int spawnX = spawn.getX();
        int spawnZ = spawn.getZ();

        // Height first, so the biome pass can sample at the real surface Y
        // (avoids underground cave biomes leaking into the map at fixed Y).
        double[][] heights = scanHeight(generator, level, randomState, heightStep, radius, spawnX, spawnZ, outDir);
        scanClimate(biomeSource, sampler, heights, heightStep, step, radius, spawnX, spawnZ, outDir);

        // Regenerate the interactive viewer map from the fresh CSVs (overwrites prior).
        ViewerGenerator.generate(outDir);
        System.out.println("[sdb-scan] viewer.html written");
    }

    private static void scanClimate(BiomeSource biomeSource, Climate.Sampler sampler,
                                    double[][] heights, int heightStep, int step, int radius,
                                    int spawnX, int spawnZ, Path outDir) throws IOException {
        int samples = (radius * 2) / step + 1;

        BufferedImage biomesImg = new BufferedImage(samples, samples, BufferedImage.TYPE_INT_RGB);
        BufferedImage contImg = new BufferedImage(samples, samples, BufferedImage.TYPE_INT_RGB);
        String[] rows = new String[samples];

        long t0 = System.currentTimeMillis();
        AtomicInteger done = new AtomicInteger();

        IntStream.range(0, samples).parallel().forEach(zi -> {
            int bz = spawnZ - radius + zi * step;
            int qz = QuartPos.fromBlock(bz);
            int hj = (int) Math.round((bz - spawnZ + radius) / (double) heightStep);
            hj = Math.max(0, Math.min(heights.length - 1, hj));
            StringBuilder sb = new StringBuilder(1 << 12);
            for (int xi = 0; xi < samples; xi++) {
                int bx = spawnX - radius + xi * step;
                int qx = QuartPos.fromBlock(bx);
                int hi = (int) Math.round((bx - spawnX + radius) / (double) heightStep);
                hi = Math.max(0, Math.min(heights[0].length - 1, hi));
                int qy = QuartPos.fromBlock((int) heights[hj][hi]);

                Climate.TargetPoint tp = sampler.sample(qx, qy, qz);
                Holder<Biome> biome = biomeSource.getNoiseBiome(qx, qy, qz, sampler);
                float cont = Climate.unquantizeCoord(tp.continentalness());

                biomesImg.setRGB(xi, zi, biomeColor(biome));
                contImg.setRGB(xi, zi, continentalnessColor(cont));

                sb.append(bx).append(',').append(bz).append(',')
                  .append(f(tp.temperature())).append(',').append(f(tp.humidity())).append(',')
                  .append(f(tp.continentalness())).append(',').append(f(tp.erosion())).append(',')
                  .append(f(tp.depth())).append(',').append(f(tp.weirdness())).append(',')
                  .append(name(biome)).append('\n');
            }
            rows[zi] = sb.toString();
            int n = done.incrementAndGet();
            if (n % 128 == 0) {
                System.out.printf("[sdb-climate] %.0f%% (%d/%d) %.1fs%n",
                    100.0 * n / samples, n, samples, (System.currentTimeMillis() - t0) / 1000.0);
            }
        });

        ImageIO.write(biomesImg, "png", outDir.resolve("biomes.png").toFile());
        ImageIO.write(contImg, "png", outDir.resolve("continentalness.png").toFile());
        StringBuilder csv = new StringBuilder(samples * 128);
        csv.append("x,z,temp,hum,cont,erosion,depth,weird,biome\n");
        for (String row : rows) csv.append(row);
        Files.writeString(outDir.resolve("scan.csv"), csv.toString());
        System.out.printf("[sdb-climate] done %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static double[][] scanHeight(NoiseBasedChunkGenerator generator, ServerLevel level,
                                         RandomState randomState, int heightStep, int radius,
                                         int spawnX, int spawnZ, Path outDir) throws IOException {
        int samples = (radius * 2) / heightStep + 1;
        double[][] heights = new double[samples][samples];
        BufferedImage heightImg = new BufferedImage(samples, samples, BufferedImage.TYPE_INT_RGB);
        String[] rows = new String[samples];

        long t0 = System.currentTimeMillis();
        AtomicInteger done = new AtomicInteger();

        IntStream.range(0, samples).parallel().forEach(zi -> {
            int bz = spawnZ - radius + zi * heightStep;
            StringBuilder sb = new StringBuilder(samples * 12);
            for (int xi = 0; xi < samples; xi++) {
                int bx = spawnX - radius + xi * heightStep;
                int height = generator.getBaseHeight(bx, bz, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
                heights[zi][xi] = height;
                heightImg.setRGB(xi, zi, heightColor(height));
                sb.append(bx).append(',').append(bz).append(',').append(height).append('\n');
            }
            rows[zi] = sb.toString();
            int n = done.incrementAndGet();
            if (n % 32 == 0) {
                System.out.printf("[sdb-height] %.0f%% (%d/%d) %.1fs%n",
                    100.0 * n / samples, n, samples, (System.currentTimeMillis() - t0) / 1000.0);
            }
        });

        ImageIO.write(heightImg, "png", outDir.resolve("height.png").toFile());
        StringBuilder csv = new StringBuilder(samples * 64);
        csv.append("x,z,height\n");
        for (String row : rows) csv.append(row);
        Files.writeString(outDir.resolve("heights.csv"), csv.toString());
        System.out.printf("[sdb-height] done %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);
        return heights;
    }

    private static DensityFunction.Visitor makeNoiseVisitor(RandomState randomState) {
        return new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction function) {
                return function;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
                NormalNoise noise = randomState.getOrCreateNoise(holder.noiseData().unwrapKey().orElseThrow());
                return new DensityFunction.NoiseHolder(holder.noiseData(), noise);
            }
        };
    }

    private static Climate.Sampler makeSampler(NoiseRouter router, DensityFunction.Visitor visitor) {
        return new Climate.Sampler(
            router.temperature().mapAll(visitor),
            router.vegetation().mapAll(visitor),
            router.continents().mapAll(visitor),
            router.erosion().mapAll(visitor),
            router.depth().mapAll(visitor),
            router.ridges().mapAll(visitor),
            List.of());
    }

    private static String f(long quantized) {
        return Float.toString(Climate.unquantizeCoord(quantized));
    }

    private static String name(Holder<Biome> biome) {
        return biome.unwrapKey().map(k -> k.location().toString()).orElse("unregistered");
    }

    // ---- palette ----

    private static int biomeColor(Holder<Biome> biome) {
        return switch (name(biome)) {
            case "minecraft:ocean" -> 0x000070;
            case "minecraft:deep_ocean" -> 0x000030;
            case "minecraft:warm_ocean" -> 0x0000ac;
            case "minecraft:lukewarm_ocean" -> 0x000090;
            case "minecraft:cold_ocean" -> 0x202070;
            case "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean" -> 0x7070b0;
            case "minecraft:river" -> 0x3030ff;
            case "minecraft:frozen_river" -> 0xa0a0ff;
            case "minecraft:beach" -> 0xede0d4;
            case "minecraft:snowy_beach" -> 0xfaf0ec;
            case "minecraft:plains" -> 0x8ab86a;
            case "minecraft:sunflower_plains" -> 0x9ac86a;
            case "minecraft:meadow" -> 0x83bb6d;
            case "minecraft:forest" -> 0x056621;
            case "minecraft:flower_forest" -> 0x2d8a49;
            case "minecraft:birch_forest", "minecraft:old_growth_birch_forest" -> 0x5f9a5f;
            case "minecraft:dark_forest" -> 0x1c4a1c;
            case "minecraft:swamp" -> 0x4a5d43;
            case "minecraft:mangrove_swamp" -> 0x4a3d43;
            case "minecraft:desert" -> 0xe6d68a;
            case "minecraft:savanna", "minecraft:savanna_plateau" -> 0xbfae3f;
            case "minecraft:taiga", "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga", "minecraft:snowy_taiga" -> 0x2b6652;
            case "minecraft:snowy_plains" -> 0xffffff;
            case "minecraft:ice_spikes" -> 0x9ad8ff;
            case "minecraft:jagged_peaks", "minecraft:frozen_peaks", "minecraft:stony_peaks" -> 0x9aa0a8;
            case "minecraft:snowy_slopes" -> 0xd0d8e0;
            case "minecraft:grove" -> 0x8a9b68;
            case "minecraft:windswept_hills", "minecraft:windswept_forest", "minecraft:windswept_gravelly_hills" -> 0x7a8a7a;
            case "minecraft:stony_shore" -> 0x8a8a90;
            case "minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands" -> 0xd08a5a;
            case "minecraft:jungle", "minecraft:sparse_jungle", "minecraft:bamboo_jungle" -> 0x14791a;
            case "minecraft:mushroom_fields" -> 0xc05a8a;
            default -> hashColor(name(biome));
        };
    }

    private static int hashColor(String id) {
        int h = id.hashCode();
        int r = 60 + Math.abs(h) % 176;
        int g = 60 + Math.abs(h >> 8) % 176;
        int b = 60 + Math.abs(h >> 16) % 176;
        return (r << 16) | (g << 8) | b;
    }

    private static int continentalnessColor(float c) {
        if (c < -0.45f) return 0x000020;
        if (c < -0.2f) return 0x000060;
        if (c < -0.05f) return 0x2050a0;
        if (c < 0.0f) return 0xc0b060;
        if (c < 0.1f) return 0x70a050;
        if (c < 0.3f) return 0x40a040;
        if (c < 0.5f) return 0x308030;
        if (c < 0.7f) return 0x806040;
        return 0xa0a0a0;
    }

    private static int heightColor(int y) {
        if (y < 32) return 0x000020;
        if (y < 56) return 0x000080;
        if (y < 62) return 0x0060c0;
        if (y < 68) return 0xc8c060;
        if (y < 90) return 0x40a040;
        if (y < 130) return 0x308030;
        if (y < 180) return 0x806040;
        return 0xd0d0d0;
    }
}
