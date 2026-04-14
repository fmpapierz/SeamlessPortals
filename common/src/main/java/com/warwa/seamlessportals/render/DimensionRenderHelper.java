package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-dimension lightmap management matching IP's DimensionRenderHelper.
 *
 * Each dimension gets its own Lightmap texture that is updated each frame
 * with the correct sky angle, ambient light, and dimension-specific values.
 * This replaces the hardcoded LightmapRenderState values.
 *
 * IP's DimensionRenderHelper creates a LightTexture per dimension and ticks it.
 * MC 26.1.2 uses LightmapRenderStateExtractor to populate LightmapRenderState,
 * then Lightmap.render() to generate the texture. We replicate this per dimension.
 */
public class DimensionRenderHelper {

    private static final Map<ResourceKey<Level>, DimensionRenderHelper> helpers = new ConcurrentHashMap<>();
    private static final RandomSource random = RandomSource.create();
    private static float blockLightFlicker = 0;

    private final Lightmap lightmap;
    private final LightmapRenderState renderState;

    private DimensionRenderHelper() {
        this.lightmap = new Lightmap();
        this.renderState = new LightmapRenderState();
    }

    public static DimensionRenderHelper getOrCreate(ResourceKey<Level> dimension) {
        return helpers.computeIfAbsent(dimension, k -> {
            SeamlessPortalsConstants.LOGGER.info("[SEAMLESS] Created DimensionRenderHelper for {}", k.identifier());
            return new DimensionRenderHelper();
        });
    }

    /**
     * Update the lightmap for this dimension using the virtual camera.
     * Extracts LightmapRenderState from the camera's attribute probe
     * (which reads dimension-specific biome/environment values).
     *
     * Matches MC's LightmapRenderStateExtractor.extract() logic exactly,
     * but uses the virtual camera instead of the main camera.
     */
    public void updateAndRender(Camera virtualCamera, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        renderState.needsUpdate = true;

        // Block light flicker (matches LightmapRenderStateExtractor.tick())
        blockLightFlicker += (random.nextFloat() - random.nextFloat())
            * random.nextFloat() * random.nextFloat() * 0.1F;
        blockLightFlicker *= 0.9F;

        // Extract from virtual camera's attribute probe — gives destination dimension values
        // This is the EXACT same logic as LightmapRenderStateExtractor.extract()
        renderState.blockFactor = blockLightFlicker + 1.4F;
        renderState.blockLightTint = ARGB.vector3fFromRGB24(
            virtualCamera.attributeProbe().getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, partialTicks));
        renderState.skyFactor = virtualCamera.attributeProbe().getValue(
            EnvironmentAttributes.SKY_LIGHT_FACTOR, partialTicks);
        renderState.skyLightColor = ARGB.vector3fFromRGB24(
            virtualCamera.attributeProbe().getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, partialTicks));
        renderState.ambientColor = ARGB.vector3fFromRGB24(
            virtualCamera.attributeProbe().getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, partialTicks));

        // Brightness from game options
        float brightnessOption = mc.options.gamma().get().floatValue();
        float darknessScale = 0.0F;
        if (mc.player instanceof LivingEntity living) {
            float darknessOption = mc.options.darknessEffectScale().get().floatValue();
            float darknessEffect = living.getEffectBlendFactor(MobEffects.DARKNESS, partialTicks) * darknessOption;
            renderState.brightness = Math.max(0.0F, brightnessOption - darknessEffect);
            darknessScale = calculateDarknessScale(living, darknessEffect, partialTicks) * darknessOption;
        } else {
            renderState.brightness = brightnessOption;
        }
        renderState.darknessEffectScale = darknessScale;

        // Night vision
        if (mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            renderState.nightVisionEffectIntensity = GameRenderer.getNightVisionScale(mc.player, partialTicks);
        } else {
            float waterVision = mc.player.getWaterVision();
            if (waterVision > 0.0F && mc.player.hasEffect(MobEffects.CONDUIT_POWER)) {
                renderState.nightVisionEffectIntensity = waterVision;
            } else {
                renderState.nightVisionEffectIntensity = 0.0F;
            }
        }

        renderState.nightVisionColor = ARGB.vector3fFromRGB24(
            virtualCamera.attributeProbe().getValue(EnvironmentAttributes.NIGHT_VISION_COLOR, partialTicks));
        renderState.bossOverlayWorldDarkening = 0.0F; // No boss overlay in portal view

        // Render the lightmap texture
        lightmap.render(renderState);
    }

    private static float calculateDarknessScale(LivingEntity entity, float darknessGamma, float partialTicks) {
        float darkness = 0.45F * darknessGamma;
        return Math.max(0.0F, Mth.cos((entity.tickCount - partialTicks)
            * (float) Math.PI * 0.025F) * darkness);
    }

    public Lightmap getLightmap() {
        return lightmap;
    }

    public static void cleanup() {
        for (DimensionRenderHelper helper : helpers.values()) {
            helper.lightmap.close();
        }
        helpers.clear();
    }
}
