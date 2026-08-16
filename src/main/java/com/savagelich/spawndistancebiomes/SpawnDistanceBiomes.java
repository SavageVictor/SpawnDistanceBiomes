package com.savagelich.spawndistancebiomes;

import com.mojang.logging.LogUtils;
import com.savagelich.spawndistancebiomes.config.YaclConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.slf4j.Logger;

@Mod(SpawnDistanceBiomes.MODID)
public class SpawnDistanceBiomes {
    public static final String MODID = "spawndistancebiomes";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SpawnDistanceBiomes(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("SpawnDistanceBiomes loading — biome distance gating active");
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, MODID + ".toml");
    }

    /** Client-only: registers custom config screen. */
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    static class ClientSetup {
        @SubscribeEvent
        static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> ModLoadingContext.get().registerExtensionPoint(
                IConfigScreenFactory.class, () -> (client, parent) -> YaclConfigScreen.create(parent)));
        }
    }
}
