package com.warwa.seamlessportals.fabric;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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

        // Recursive portal-through-portal depth, NO shaderpack. This replaces the former
        // "Max recursive portal depth" slider, which was capped at 3 and — more to the point — had
        // NO consumer anywhere: nothing outside its own getter/setter ever read the value, so moving
        // it did nothing at all. This one writes IPGlobal.maxPortalLayer, which the engine reads.
        addDepthRow(x, y, w, "Portal recursion depth", cfg.getVanillaRecursionDepth(),
                cfg::setVanillaRecursionDepth);
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

        // IS5-REC: recursion depth WITH A SHADERPACK ON. Separate from the row above because a
        // shaders-ON layer is a full pack-shaded world render (gbuffer + shadow pass + composite
        // chain), i.e. a much heavier unit than a shaders-off layer. 1 = the pre-feature behaviour.
        addDepthRow(x, y, w, "Shader portal recursion depth", cfg.getIrisRecursionDepth(),
                cfg::setIrisRecursionDepth);
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

    /**
     * A depth row: a SLIDER for the common range plus a TYPED BOX that can exceed it.
     *
     * <p>The slider alone cannot express the values this setting now supports — a slider capped at
     * the useful range is exactly what blocked entering 20 or 100 — and a text box alone loses the
     * one-drag adjustment that covers almost every real use. So both, kept in sync: dragging the
     * slider rewrites the box, and typing a valid number moves the slider (clamping its POSITION to
     * its own range while the real value goes through unclamped).
     *
     * <p>Typing is validated on every keystroke and simply ignored while the field is empty or
     * mid-word ("2" on the way to "20" is a legal value, so it applies — the config is re-read on
     * screen close anyway, and nothing here is destructive).
     */
    private void addDepthRow(int x, int y, int w, String label, int current, IntConsumer apply) {
        final int boxW = 46;
        final int gap = 4;
        final int sliderW = w - boxW - gap;

        EditBox box = new EditBox(this.font, x + sliderW + gap, y, boxW, 20,
                Component.literal(label + " (exact)"));
        box.setMaxLength(3); // 999 max typeable; the config setter clamps to MAX_RECURSION_DEPTH
        box.setValue(Integer.toString(current));

        IntSlider slider = new IntSlider(x, y, sliderW, 20, label,
                SLIDER_MIN, SLIDER_MAX, clampToSlider(current), v -> {
            apply.accept(v);
            // Keep the box showing the live value, but never fight the user mid-type: only rewrite
            // it when the slider is what changed the value.
            box.setValue(Integer.toString(v));
        });

        box.setResponder(text -> {
            String t = text.trim();
            if (t.isEmpty()) {
                return; // mid-edit, not a value yet
            }
            try {
                int typed = Integer.parseInt(t);
                if (typed < 0) {
                    return;
                }
                apply.accept(typed);
                slider.setFromValue(clampToSlider(typed));
            }
            catch (NumberFormatException ignored) {
                // not a number yet — leave the last good value in place
            }
        });

        this.addRenderableWidget(slider);
        this.addRenderableWidget(box);
    }

    /** The slider's own range. The TYPED box is what reaches past it. */
    private static final int SLIDER_MIN = 0;
    private static final int SLIDER_MAX = 10;

    private static int clampToSlider(int v) {
        return Math.max(SLIDER_MIN, Math.min(SLIDER_MAX, v));
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

        /**
         * Move the knob to represent {@code v} WITHOUT re-applying it. Used when the typed box is
         * the source of the change: re-applying here would bounce the value back through the
         * consumer and overwrite what was just typed with the slider's clamped version.
         */
        void setFromValue(int v) {
            this.value = (double) (v - this.min) / (double) (this.max - this.min);
            this.updateMessage();
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
