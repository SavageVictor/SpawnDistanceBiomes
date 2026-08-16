package com.savagelich.spawndistancebiomes.config;

import com.savagelich.spawndistancebiomes.Config;
import com.savagelich.spawndistancebiomes.SpawnDistanceBiomes;
import com.savagelich.spawndistancebiomes.band.BiomeBandData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Two-panel biome picker: left=allowed (click to remove), right=available (click to add).
 */
public class BiomePickerScreen extends Screen {

    private static final int ITEM_H = 18;
    private final Screen parent;
    private final List<BiomeBandData> bands;
    private final int bandIndex;
    private final boolean isSurface;

    private BiomeBandData band;
    private final LinkedHashSet<ResourceLocation> selected;
    private EditBox searchField;
    private int leftScroll, rightScroll;
    private List<ResourceLocation> allBiomes = List.of();
    private List<ResourceLocation> filteredAvailable = List.of();

    public BiomePickerScreen(Screen parent, List<BiomeBandData> bands, int bandIndex, boolean isSurface) {
        super(Component.literal("Select Allowed Biomes"));
        this.parent = parent;
        this.bands = bands;
        this.bandIndex = bandIndex;
        this.isSurface = isSurface;
        this.band = bands.get(bandIndex);
        this.selected = new LinkedHashSet<>(band.allowedBiomes);
    }

    private int panelW() { return (width - 32) / 2; }
    private int leftX() { return 8; }
    private int rightX() { return leftX() + panelW() + 8; }
    private int listTop() { return 52; }
    private int visible() { return Math.max(4, (height - listTop() - 50) / ITEM_H); }
    private int listH() { return visible() * ITEM_H; }

    @Override protected void init() {
        super.init();
        loadBiomes();

        // Allow All button (left side, same row as search)
        addRenderableWidget(Button.builder(Component.literal("* Allow All"), b -> {
            band.allowedBiomes.clear();
            band.allowsAll = true;
            save();
        }).pos(leftX(), 24).size(80, 18).build());

        // Search (right side)
        searchField = new EditBox(font, rightX(), 24, panelW() - 2, 18, Component.empty());
        searchField.setHint(Component.literal("Search biomes..."));
        searchField.setResponder(this::onSearch);
        addRenderableWidget(searchField);
        onSearch("");

        // Save/Cancel
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> { save(); onClose(); })
            .pos(width / 2 - 105, height - 28).size(100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
            .pos(width / 2 + 5, height - 28).size(100, 20).build());

        rebuildButtons();
    }

    @Override public void render(@NotNull GuiGraphics g, int mx, int my, float p) {
        renderBackground(g, mx, my, p);
        super.render(g, mx, my, p);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);

        int lx = leftX(), rx = rightX(), pw = panelW(), lt = listTop(), lh = listH();

        g.fill(lx - 1, lt - 11, lx + pw + 1, lt + lh + 1, 0xFF444444);
        g.fill(lx, lt - 10, lx + pw, lt + lh, 0xFF000000);
        g.drawString(font, "Allowed (" + selected.size() + ")", lx + 2, lt - 10, 0xAAAAAA);

        g.fill(rx - 1, lt - 11, rx + pw + 1, lt + lh + 1, 0xFF444444);
        g.fill(rx, lt - 10, rx + pw, lt + lh, 0xFF000000);
        g.drawString(font, "Available (" + allBiomes.size() + ")", rx + 2, lt - 10, 0xAAAAAA);

        if (allBiomes.isEmpty())
            g.drawCenteredString(font, Component.literal("Open from in-game for biome list"),
                width / 2, lt + 20, 0xAAAAAA);
    }

    private void rebuildButtons() {
        // Remove biome buttons from previous render
        var toRemove = new ArrayList<net.minecraft.client.gui.components.events.GuiEventListener>();
        for (var w : children()) {
            if (w instanceof Button b) {
                String s = b.getMessage().getString();
                // Keep: Save, Cancel, Allow All, and any EditBox
                if (!s.equals("Save") && !s.equals("Cancel") && !s.startsWith("* Allow"))
                    toRemove.add(w);
            }
        }
        toRemove.forEach(w -> removeWidget(w));

        int lx = leftX(), rx = rightX(), pw = panelW() - 2, lt = listTop();
        int ih = ITEM_H, lh = listH();

        leftScroll  = Math.clamp(leftScroll,  0, Math.max(0, selected.size() * ih - lh));
        rightScroll = Math.clamp(rightScroll, 0, Math.max(0, filteredAvailable.size() * ih - lh));

        int i = 0;
        for (ResourceLocation id : selected) {
            int y = lt + i * ih - leftScroll;
            if (y >= lt - ih && y <= lt + lh) {
                final ResourceLocation rid = id;
                addRenderableWidget(Button.builder(
                    Component.literal("✕ " + shortName(id, pw)),
                    b -> { selected.remove(rid); band.allowedBiomes.remove(rid); band.allowsAll = false; onSearch(searchField.getValue()); })
                    .pos(lx + 1, y).size(pw, ih - 1).build());
            }
            i++;
        }
        for (int j = 0; j < filteredAvailable.size(); j++) {
            ResourceLocation id = filteredAvailable.get(j);
            int y = lt + j * ih - rightScroll;
            if (y >= lt - ih && y <= lt + lh) {
                final ResourceLocation rid = id;
                addRenderableWidget(Button.builder(
                    Component.literal("+ " + shortName(id, pw)),
                    b -> { selected.add(rid); band.allowedBiomes.add(rid); band.allowsAll = false; onSearch(searchField.getValue()); })
                    .pos(rx + 1, y).size(pw, ih - 1).build());
            }
        }
    }

    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int ih = ITEM_H, lh = listH();
        if (mx >= leftX() && mx <= leftX() + panelW()) {
            leftScroll = Math.clamp(leftScroll - (int) sy * ih, 0, Math.max(0, selected.size() * ih - lh));
            rebuildButtons(); return true;
        }
        if (mx >= rightX() && mx <= rightX() + panelW()) {
            rightScroll = Math.clamp(rightScroll - (int) sy * ih, 0, Math.max(0, filteredAvailable.size() * ih - lh));
            rebuildButtons(); return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    private void onSearch(String q) {
        String l = q.toLowerCase().trim();
        filteredAvailable = allBiomes.stream()
            .filter(id -> !selected.contains(id))
            .filter(id -> l.isEmpty() || id.toString().contains(l) || id.getPath().contains(l))
            .collect(Collectors.toList());
        rightScroll = 0;
        rebuildButtons();
    }

    private void save() {
        bands.set(bandIndex, band);
        YaclConfigScreen.doSave(isSurface ? Config.SURFACE_BANDS : Config.CAVE_BANDS, bands);
        Config.SPEC.save();
        SpawnDistanceBiomes.LOGGER.info("Saved biome selection for band {}", bandIndex);
    }

    @Override public void onClose() {
        minecraft.setScreen(YaclConfigScreen.create(parent));
    }

    private void loadBiomes() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            // Main menu — registries aren't accessible. Tell the user.
            allBiomes = List.of();
            return;
        }
        Registry<Biome> reg = level.registryAccess().registryOrThrow(Registries.BIOME);
        allBiomes = reg.keySet().stream()
            .sorted(Comparator.comparing(ResourceLocation::getPath))
            .collect(Collectors.toList());
    }

    private String shortName(ResourceLocation id, int panelW) {
        int max = (panelW - 20) / (font.width("x") + 1);
        String s = id.toString();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    static class BiomeBtn extends Button {
        BiomeBtn(int x, int y, int w, int h, Component msg, OnPress action) {
            super(x, y, w, h, msg, action, DEFAULT_NARRATION);
        }
    }
}
