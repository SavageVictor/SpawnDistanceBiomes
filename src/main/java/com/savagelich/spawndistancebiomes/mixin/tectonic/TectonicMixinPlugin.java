package com.savagelich.spawndistancebiomes.mixin.tectonic;

import com.mojang.logging.LogUtils;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Conditionally applies Tectonic mixins only when Tectonic is present.
 *
 * Uses several fallbacks because the mixin-preparation phase runs at a
 * different point in NeoForge's loading sequence than mod construction:
 *   - LoadingModList is populated right after mod discovery (earliest);
 *   - ModList becomes available once mod loading completes;
 *   - Class.forName is the last-resort classpath check.
 */
public class TectonicMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TECTONIC_CLASS = "dev.worldgen.tectonic.worldgen.densityfunction.ConfigConstant";
    private static boolean logged = false;

    @Override
    public void onLoad(String mixinPackage) {
        LOGGER.info("[SpawnDistanceBiomes] Tectonic mixin plugin loaded (Tectonic present = {})",
            isTectonicPresent());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean apply = isTectonicPresent();
        if (!logged) {
            logged = true;
            LOGGER.info("[SpawnDistanceBiomes] shouldApplyMixin {} -> {}", mixinClassName, apply);
        }
        return apply;
    }

    private static boolean isTectonicPresent() {
        // 1. LoadingModList — available right after mod discovery.
        try {
            if (net.neoforged.fml.loading.LoadingModList.get().getModFileById("tectonic") != null) {
                return true;
            }
        } catch (Throwable ignored) {}

        // 2. ModList — available once mod loading completes.
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("tectonic")) {
                return true;
            }
        } catch (Throwable ignored) {}

        // 3. Raw classpath presence — last resort.
        try {
            Class.forName(TECTONIC_CLASS, false, TectonicMixinPlugin.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {}

        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
