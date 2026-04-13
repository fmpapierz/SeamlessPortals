package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;

/**
 * IP's FogRendererContext.swappingManager pattern.
 * Saves/restores fog GPU buffer state during context switch.
 *
 * FogData has: environmentalStart/End, renderDistanceStart/End, skyEnd, cloudEnd, color.
 * We copy all fields to save, and restore them to a FogData for re-upload.
 */
public class FogContextManager {

    private static FogData savedFog = null;

    /**
     * Save current fog state and push destination fog to GPU.
     * IP: FogRendererContext.swappingManager.pushSwapping(newDimension)
     */
    public static void pushFog(FogRenderer fogRenderer, FogData destFogData) {
        // Save current fog by copying fields (FogData is mutable, can't save reference)
        savedFog = copyFogData(destFogData); // Save BEFORE overwriting

        try {
            fogRenderer.updateBuffer(destFogData);

            if (SeamlessPortalsConstants.LOGGER.isDebugEnabled()) {
                SeamlessPortalsConstants.LOGGER.debug(
                    "[FOG PUSH] dest fog: start={}, end={}, color=({},{},{},{})",
                    destFogData.renderDistanceStart, destFogData.renderDistanceEnd,
                    destFogData.color.x, destFogData.color.y, destFogData.color.z, destFogData.color.w);
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.warn("[FOG PUSH] Failed", e);
        }
    }

    /**
     * Restore previous fog state to GPU.
     * IP: FogRendererContext.swappingManager.popSwapping()
     *
     * @param mainFogData The fog data that was active BEFORE the context switch.
     *                    We re-upload this to the GPU.
     */
    public static void popFog(FogRenderer fogRenderer, FogData mainFogData) {
        if (mainFogData != null) {
            try {
                fogRenderer.updateBuffer(mainFogData);

                if (SeamlessPortalsConstants.LOGGER.isDebugEnabled()) {
                    SeamlessPortalsConstants.LOGGER.debug(
                        "[FOG POP] restored fog: start={}, end={}, color=({},{},{},{})",
                        mainFogData.renderDistanceStart, mainFogData.renderDistanceEnd,
                        mainFogData.color.x, mainFogData.color.y, mainFogData.color.z, mainFogData.color.w);
                }
            } catch (Exception e) {
                SeamlessPortalsConstants.LOGGER.warn("[FOG POP] Failed", e);
            }
        }
        savedFog = null;
    }

    /** Deep copy FogData fields (the object is mutable). */
    private static FogData copyFogData(FogData src) {
        FogData copy = new FogData();
        copy.environmentalStart = src.environmentalStart;
        copy.renderDistanceStart = src.renderDistanceStart;
        copy.environmentalEnd = src.environmentalEnd;
        copy.renderDistanceEnd = src.renderDistanceEnd;
        copy.skyEnd = src.skyEnd;
        copy.cloudEnd = src.cloudEnd;
        copy.color = new Vector4f(src.color);
        return copy;
    }
}
