package qouteall.q_misc_util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.profiling.Profiler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.TreeMap;

/**
 * Make this because {@link Gui#setOverlayMessage(Component, boolean)} does not support multi-line
 */
@Environment(EnvType.CLIENT)
public class CustomTextOverlay {

    public static record Entry(
        Component component,
        long clearingTime
    ) {}

    private static final TreeMap<String, Entry> ENTRIES = new TreeMap<>();

    private static final boolean renderAtBottomCenter = true;

    // 26.2 render-model re-derivation (migration/fragments/S07-qmisc.md §CustomTextOverlay):
    // 26.2 rewrote the client GUI render surface (GuiGraphics -> GuiGraphicsExtractor extract
    // model), and MultiLineLabel dropped its immediate GuiGraphics render methods
    // (renderCentered/renderLeftAligned) in favour of the ActiveTextCollector visit model.
    // The MultiLineLabel cache is therefore replaced by a pre-split FormattedCharSequence line
    // list; Font.split reproduces the exact explicit-newline + word-wrap behaviour
    // MultiLineLabel.create(font, component, width) used. This is HELD + unregistered until S13;
    // RENDER CORRECTNESS (exact line height, shadow, anchor) is a render-family residual to be
    // verified at S11/S12 against the render api-map.
    @Nullable
    private static List<FormattedCharSequence> lineCache;

    public static void putText(Component component, double durationSeconds, String key) {
        ENTRIES.put(
            key,
            new Entry(
                component,
                System.nanoTime() + Helper.secondToNano(durationSeconds)
            )
        );
        lineCache = null;
    }

    public static void putText(Component component, double durationSeconds) {
        putText(component, durationSeconds, "5_defaultKey");
    }

    public static void putText(Component component, String key) {
        putText(component, 0.2, key);
    }

    public static void putText(Component component) {
        putText(component, 0.2, "5_defaultKey");
    }

    public static boolean remove(String key) {
        return ENTRIES.remove(key) != null;
    }

    /**
     * {@link Gui#extractRenderState(DeltaTracker, boolean, boolean)}
     * {@link net.minecraft.client.gui.screens.AlertScreen}
     */
    public static void render(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker) {
        long currTime = System.nanoTime();

        boolean removes = ENTRIES.entrySet().removeIf(e -> e.getValue().clearingTime < currTime);
        if (removes) {
            lineCache = null;
        }

        if (ENTRIES.isEmpty()) {
            return;
        }

        if (lineCache == null) {
            // don't make the first component the base component
            // to avoid style override
            MutableComponent component = Component.empty();
            boolean isBeginning = true;
            for (Entry entry : ENTRIES.values()) {
                if (isBeginning) {
                    isBeginning = false;
                }
                else {
                    component.append("\n");
                }
                component.append(entry.component());
            }

            lineCache = Minecraft.getInstance().font.split(
                component,
                (Minecraft.getInstance().getWindow().getGuiScaledWidth() - 20)
            );
            assert lineCache != null;
        }

        Minecraft minecraft = Minecraft.getInstance();

        guiGraphics.pose().pushMatrix();

        int guiScaledWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiScaledHeight = minecraft.getWindow().getGuiScaledHeight();

        Font font = minecraft.font;

        Profiler.get().push("imm_ptl_custom_overlay");
        if (renderAtBottomCenter) {
            // Note: the parchment names are incorrect
            int y = (int) (guiScaledHeight * 0.75); // y
            for (FormattedCharSequence line : lineCache) {
                guiGraphics.centeredText(
                    font,
                    line,
                    guiScaledWidth / 2, // x
                    y,
                    0xffffffff
                );
                y += 9;
            }
        }
        else {
            int y = 10; // y
            for (FormattedCharSequence line : lineCache) {
                guiGraphics.text(
                    font,
                    line,
                    10, // x
                    y,
                    0xffffffff // color
                );
                y += 9;
            }
        }

        guiGraphics.pose().popMatrix();

        Profiler.get().pop();
    }
}
