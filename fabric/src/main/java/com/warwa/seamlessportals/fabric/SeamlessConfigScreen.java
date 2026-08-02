package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * In-game configuration screen for Seamless Portals, surfaced through Mod Menu
 * (see {@link ModMenuIntegration}). Pure-vanilla widgets — no external UI lib.
 *
 * <p>Built against the 26.2 GUI-extract model: the base {@link Screen} renders
 * the child widgets in {@code extractRenderState}; we override it only to draw
 * the centered title. Every changed value is applied live to
 * {@link SeamlessPortalsConfig} as the user drags, and persisted to
 * {@code config/seamlessportals.properties} on close.
 */
public class SeamlessConfigScreen extends Screen {

    private final Screen parent;

    public SeamlessConfigScreen(Screen parent) {
        super(Component.literal("Seamless Portals"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        SeamlessPortalsConfig cfg = SeamlessPortalsConfig.get();

        final int w = 220;
        final int cx = this.width / 2;
        final int x = cx - w / 2;
        final int rowH = 24;
        int y = this.height / 4 + 8;

        // Headline knob: how deep the destination is kept loaded + meshed
        // (IP's indirectLoadingRadiusCap). Higher = less far-chunk reload on
        // crossing, but more memory + longer prep.
        this.addRenderableWidget(new IntSlider(x, y, w, 20,
                "Destination depth (chunks)", 1, 32,
                cfg.getPortalRenderDistance(), cfg::setPortalRenderDistance));
        y += rowH;

        // Recursive portal-through-portal render depth.
        this.addRenderableWidget(new IntSlider(x, y, w, 20,
                "Max recursive portal depth", 0, 3,
                cfg.getMaxPortalRenderDepth(), cfg::setMaxPortalRenderDepth));
        y += rowH;

        // Master "render through portals" toggle.
        this.addRenderableWidget(new Button.Builder(renderingLabel(cfg), b -> {
            cfg.setEnablePortalRendering(!cfg.isEnablePortalRendering());
            b.setMessage(renderingLabel(cfg));
        }).bounds(x, y, w, 20).build());
        y += rowH;

        // Speculative pre-warm toggle: pre-load an unlit frame's expected destination so
        // lighting the portal reveals an already-prepared view.
        this.addRenderableWidget(new Button.Builder(prewarmLabel(cfg), b -> {
            cfg.setSpeculativePrewarm(!cfg.isSpeculativePrewarm());
            b.setMessage(prewarmLabel(cfg));
        }).bounds(x, y, w, 20).build());
        y += rowH;

        // IS5-REC: recursion depth WITH A SHADERPACK ON. Separate from the slider above because a
        // shaders-ON layer is a full pack-shaded world render (gbuffer + shadow pass + composite
        // chain), i.e. a much heavier unit than a shaders-off layer. 1 = the pre-feature behaviour.
        this.addRenderableWidget(new IntSlider(x, y, w, 20,
                "Shader portal recursion depth", 1, 5,
                cfg.getIrisRecursionDepth(), cfg::setIrisRecursionDepth));
        y += rowH;

        // IS5-REC: the OPT-IN deep-recursion lag guard. Off by default (user-decided). It exists
        // because the engine's mirror-room protection only checks the frame rate after >10 dest
        // renders in a frame, and a deep single chain makes about one per layer — so nothing
        // automatic covers deep recursion without this.
        this.addRenderableWidget(new Button.Builder(lagGuardLabel(cfg), b -> {
            cfg.setIrisRecursionLagGuard(!cfg.isIrisRecursionLagGuard());
            b.setMessage(lagGuardLabel(cfg));
        }).bounds(x, y, w, 20).build());
        y += rowH + 10;

        this.addRenderableWidget(new Button.Builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx - 100, y, 200, 20).build());
    }

    private static Component renderingLabel(SeamlessPortalsConfig cfg) {
        return Component.literal("Portal rendering: " + (cfg.isEnablePortalRendering() ? "ON" : "OFF"));
    }

    private static Component prewarmLabel(SeamlessPortalsConfig cfg) {
        return Component.literal("Pre-warm unlit frames: " + (cfg.isSpeculativePrewarm() ? "ON" : "OFF"));
    }

    private static Component lagGuardLabel(SeamlessPortalsConfig cfg) {
        return Component.literal(
            "Reduce shader recursion when laggy: " + (cfg.isIrisRecursionLagGuard() ? "ON" : "OFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.getTitle(), this.width / 2, this.height / 4 - 16, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        SeamlessPortalsConfig.saveTo(FabricLoader.getInstance().getConfigDir());
        this.minecraft.setScreenAndShow(parent);
    }

    /**
     * Integer slider over [min, max]. {@link AbstractSliderButton#value} is the
     * normalized 0..1 position; we map it to the integer range and push the
     * result to a consumer as the user drags.
     */
    private static final class IntSlider extends AbstractSliderButton {
        private final int min;
        private final int max;
        private final String label;
        private final IntConsumer apply;

        IntSlider(int x, int y, int w, int h, String label, int min, int max, int current, IntConsumer apply) {
            super(x, y, w, h, Component.literal(label), (double) (current - min) / (double) (max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.apply = apply;
            this.updateMessage();
        }

        private int currentValue() {
            return this.min + (int) Math.round(this.value * (this.max - this.min));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(this.label + ": " + currentValue()));
        }

        @Override
        protected void applyValue() {
            this.apply.accept(currentValue());
        }
    }
}
