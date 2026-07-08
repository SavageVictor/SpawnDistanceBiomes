package com.savagelich.spawndistancebiomes;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * Main mod class. Registers config and captures the overworld's
 * biome source reference so the mixin can identify its target.
 */
@Mod(SpawnDistanceBiomes.MODID)
public class SpawnDistanceBiomes {
    public static final String MODID = "spawndistancebiomes";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The overworld's MultiNoiseBiomeSource instance.
     * Set on server start, used by the mixin to determine
     * whether to apply distance gating.
     */
    public static volatile MultiNoiseBiomeSource overworldBiomeSource = null;

    public SpawnDistanceBiomes(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("SpawnDistanceBiomes loading — biome distance gating active");

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, MODID + ".toml");

        // Capture overworld biome source on server start
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        var source = event.getServer().overworld()
            .getChunkSource().getGenerator().getBiomeSource();
        if (source instanceof MultiNoiseBiomeSource mn) {
            overworldBiomeSource = mn;
            LOGGER.info("Captured overworld biome source for distance gating");
        }
    }
}
