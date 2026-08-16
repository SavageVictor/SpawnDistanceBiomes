package com.savagelich.spawndistancebiomes.config;

import com.savagelich.spawndistancebiomes.Config;
import com.savagelich.spawndistancebiomes.SpawnDistanceBiomes;
import com.savagelich.spawndistancebiomes.band.BiomeBandData;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.minecraft.client.Minecraft;
import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Follows Structurify's pattern: tabs for Surface/Cave, YACL builder
 * with generateScreen(), screen state management.
 */
public class YaclConfigScreen {

    private static final Map<String, ScreenState> screenStates = new HashMap<>();

    public static Screen create(Screen parent) {
        List<BiomeBandData> surfaceBands = parse(Config.SURFACE_BANDS.get());
        List<BiomeBandData> caveBands = parse(Config.CAVE_BANDS.get());

        var yacl = YetAnotherConfigLib.createBuilder()
            .title(Component.literal("Spawn Distance Biomes"))
            .save(() -> {
                doSave(Config.SURFACE_BANDS, surfaceBands);
                doSave(Config.CAVE_BANDS, caveBands);
                SpawnDistanceBiomes.LOGGER.info("Config saved");
            });

        // Surface tab
        var surfaceCat = ConfigCategory.createBuilder()
            .name(Component.literal("Surface Bands"))
            .tooltip(Component.literal("Controls biomes and terrain for Y ≥ "
                + Config.SURFACE_THRESHOLD_Y.getAsInt()));
        addBandsSection(surfaceCat, surfaceBands, true);
        yacl.category(surfaceCat.build());

        // Cave tab
        var caveCat = ConfigCategory.createBuilder()
            .name(Component.literal("Cave Bands"))
            .tooltip(Component.literal("Controls biomes below Y "
                + Config.SURFACE_THRESHOLD_Y.getAsInt()));
        addBandsSection(caveCat, caveBands, false);
        yacl.category(caveCat.build());

        // General tab
        var generalCat = ConfigCategory.createBuilder()
            .name(Component.literal("General"));
        var genGroup = OptionGroup.createBuilder()
            .name(Component.literal("Settings"));
        genGroup.option(Option.<Integer>createBuilder()
            .name(Component.literal("Surface Threshold Y"))
            .description(OptionDescription.of(Component.literal("Y-level dividing surface from underground.")))
            .binding(Config.SURFACE_THRESHOLD_Y.getAsInt(),
                Config.SURFACE_THRESHOLD_Y::getAsInt,
                Config.SURFACE_THRESHOLD_Y::set)
            .controller(opt -> IntegerFieldControllerBuilder.create(opt).min(-64).max(320))
            .build());
        generalCat.group(genGroup.build());
        yacl.category(generalCat.build());

        // Load previous state
        var screen = yacl.build().generateScreen(parent);
        screenStates.computeIfAbsent("main", k -> new ScreenState(0, new HashMap<>()));
        return screen;
    }

    private static void addBandsSection(ConfigCategory.Builder cat,
                                         List<BiomeBandData> bands, boolean isSurface) {
        // "Add Band" button at top
        var actionsGroup = OptionGroup.createBuilder()
            .name(Component.literal("Actions"));
        actionsGroup.option(ButtonOption.createBuilder()
            .name(Component.literal("+ Add " + (isSurface ? "Surface" : "Cave") + " Band"))
            .text(Component.literal("Click to add"))
            .action((screen, opt) -> {
                bands.add(new BiomeBandData(1024, -0.1, 1.0, false,
                    new LinkedHashSet<>(), "minecraft:plains", false));
                doSave(isSurface ? Config.SURFACE_BANDS : Config.CAVE_BANDS, bands);
                // Rebuild screen
                Minecraft.getInstance().setScreen(create(screen));
            })
            .build());
        cat.group(actionsGroup.build());

        // One group per band
        for (int i = 0; i < bands.size(); i++) {
            final int idx = i;
            BiomeBandData b = bands.get(i);

            String label = b.maxDistance < 0 ? "∞" : "≤" + b.maxDistance;
            String biomes = b.allowsAll ? "*" : b.allowedBiomes.size() + " biomes";
            String elev = b.targetContinentalness >= 9.0 ? "none"
                : String.format("%.1f", b.targetContinentalness);

            var group = OptionGroup.createBuilder()
                .name(Component.literal("Band " + i + ": " + label
                    + " — " + biomes + "  (elev: " + elev + ")"));

            // Distance
            group.option(Option.<Integer>createBuilder()
                .name(Component.literal("Max Distance"))
                .description(OptionDescription.of(Component.literal("-1 = infinite")))
                .binding(b.maxDistance, () -> b.maxDistance, v -> b.maxDistance = v)
                .controller(opt -> IntegerFieldControllerBuilder.create(opt).min(-1))
                .build());

            // Elevation
            group.option(Option.<Double>createBuilder()
                .name(Component.literal("Target Elevation"))
                .description(OptionDescription.of(Component.literal(
                    "-1=ocean, -0.1=shore, 0=plains, 0.5=hills, 9=disabled")))
                .binding(b.targetContinentalness, () -> b.targetContinentalness,
                    v -> b.targetContinentalness = v)
                .controller(opt -> DoubleFieldControllerBuilder.create(opt).min(-1.0).max(9.0))
                .build());

            // Blend
            group.option(Option.<Double>createBuilder()
                .name(Component.literal("Blend Strength"))
                .description(OptionDescription.of(Component.literal("0=none, 1=full enforcement")))
                .binding(b.blendStrength, () -> b.blendStrength,
                    v -> b.blendStrength = Math.clamp(v, 0, 1))
                .controller(opt -> DoubleSliderControllerBuilder.create(opt).range(0.0, 1.0).step(0.05))
                .build());

            // Density gating
            group.option(Option.<Boolean>createBuilder()
                .name(Component.literal("Density Gating (continentalness)"))
                .description(OptionDescription.of(Component.literal(
                    "Wrap continentalness to prevent ocean terrain near spawn.")))
                .binding(b.useDensityGating, () -> b.useDensityGating,
                    v -> b.useDensityGating = v)
                .controller(BooleanControllerBuilder::create)
                .build());

            // Edit biomes
            group.option(ButtonOption.createBuilder()
                .name(Component.literal("Edit Allowed Biomes (" + biomes + ")"))
                .text(Component.literal("Click to open biome picker"))
                .action((screen, opt) -> {
                    screen.finishOrSave();
                    Minecraft.getInstance().setScreen(
                        new BiomePickerScreen(screen, bands, idx, isSurface));
                })
                .build());

            // Delete
            group.option(ButtonOption.createBuilder()
                .name(Component.literal("✕ Delete This Band"))
                .text(Component.literal("Removes this band permanently"))
                .action((screen, opt) -> {
                    bands.remove(idx);
                    doSave(isSurface ? Config.SURFACE_BANDS : Config.CAVE_BANDS, bands);
                    screen.finishOrSave();
                    Minecraft.getInstance().setScreen(create(
                        Minecraft.getInstance().screen instanceof YACLScreen ? null :
                            Minecraft.getInstance().screen instanceof BiomePickerScreen ? null : screen));
                })
                .build());

            cat.group(group.build());
        }

        // Spacer at bottom
        var spacerGroup = OptionGroup.createBuilder()
            .name(Component.literal(""));
        spacerGroup.option(LabelOption.create(Component.literal("\n")));
        cat.group(spacerGroup.build());
    }

    // === Helpers ===

    static List<BiomeBandData> parse(List<? extends String> raw) {
        List<BiomeBandData> out = new ArrayList<>();
        for (String s : raw) { BiomeBandData b = BiomeBandData.parse(s); if (b != null) out.add(b); }
        return out;
    }

    public static void doSave(
        net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<List<? extends String>> target,
        List<BiomeBandData> bands) {
        List<String> strings = new ArrayList<>();
        for (BiomeBandData b : bands) {
            if (!b.allowsAll && !b.allowedBiomes.isEmpty()) {
                b.fallbackBiomeId = b.allowedBiomes.iterator().next().toString();
            } else if (b.fallbackBiomeId == null || b.fallbackBiomeId.isEmpty()) {
                b.fallbackBiomeId = "minecraft:plains";
            }
            strings.add(b.maxDistance + ";" + b.targetContinentalness + ";" + b.blendStrength + ";"
                + (b.useDensityGating ? "1" : "0") + ";"
                + (b.allowsAll || b.allowedBiomes.isEmpty() ? "*"
                    : String.join(",", b.allowedBiomes.stream().map(Object::toString).toList()))
                + ";" + b.fallbackBiomeId);
        }
        target.set(strings);
        // Force save via the spec (now safe since validator accepts any format)
        Config.SPEC.save();
    }

    record ScreenState(double scrollAmount, Map<String, Boolean> collapsedGroups) {}
}
