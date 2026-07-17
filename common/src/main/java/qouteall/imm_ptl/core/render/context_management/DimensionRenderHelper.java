package qouteall.imm_ptl.core.render.context_management;

import com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import qouteall.q_misc_util.Helper;

// S11-A port disposition: PORT-FORWARD / RECONCILE (current-mod-render §1.8). This RE-HOMES the mod's
// already-26.2 Lightmap mechanics (MOD:render/DimensionRenderHelper.java, the proven Lightmap +
// LightmapRenderState extraction) into IP's per-instance contract — it is NOT a fresh re-port of IP's
// 1.21.3 LightTexture shape. The public contract is the one ClientWorldLoader (S10) already fixed:
//   - ctor(Level world); public final Level world;
//   - public final Lightmap lightmapTexture   (retyped from IP's LightTexture; render-core G4,
//     ClientWorldLoader.java:166-173 conflict guard, S10B port-note line 488);
//   - void tick(); void cleanUp().
// The mod's static registry (helpers/getOrCreate/static cleanup) is RETIRED — per-dim ownership now
// lives in ClientWorldLoader.RENDER_HELPER_MAP (IP's ownership model).
//
// 26.2 retypes vs IP (render-core G4): LightTexture -> Lightmap (Lightmap.java:44-61); the main-dim
// helper reuses the GameRenderer's own Lightmap via the mod accessor seamlessportals$getLightmap()
// (the same identity ClientWorldLoader's conflict guard compares); secondaries get new Lightmap().
// The old LightTexture.tick() self-recompute -> Lightmap.render(LightmapRenderState) fed by an extract
// from the DESTINATION virtual camera's attributeProbe — driven by updateAndRender() at the render-
// context switch (S13 wiring), not by the no-arg tick(). §1.8 "verify tick-side weather/skyDarken
// parity": PARITY HELD — IP's 1.21.3 sky-darken/ambient recompute is now Camera.attributeProbe()
// SKY_LIGHT_*/AMBIENT_LIGHT_COLOR + GameRenderer boss-darkening, which the mod-proven extraction reads
// (render-sub G2/G4: attribute state moved onto the per-Camera EnvironmentAttributeProbe).
// Held/inert until S13.
public class DimensionRenderHelper {
    private static final Minecraft client = Minecraft.getInstance();

    private static final RandomSource random = RandomSource.create();
    private static float blockLightFlicker = 0;

    public final Level world;

    public final Lightmap lightmapTexture;

    // S14-A FIX-7 (audit link fog, MAJOR M7): EVERY helper owns a renderState — the birth-time
    // role ("main-born reuses the GameRenderer's Lightmap, vanilla drives it") stops being true at
    // the first cross-dim crossing: the crossing re-points the GameRenderer's lightmap field to
    // the NEW main dim's helper, and vanilla renders ONLY that field (mc262 GameRenderer.render
    // this.lightmap.render). A main-born helper whose dim was crossed away from is then driven by
    // NOBODY, freezing the look-back portal view's lightmap at the crossing moment (time-of-day
    // tint, gamma, night vision, flicker — worsening until relog). IP's dest lightmap recompute
    // was UNCONDITIONAL (IP MyGameRenderer:223-226 helper.lightmapTexture.updateLightTexture(0),
    // a full self-recompute); a universal renderState re-expresses that. Safety: the call-site
    // gate (!isDimensionRendered(dim), with isDimensionRendered(originalPlayerDimension) always
    // true) means updateAndRender never runs for the CURRENT main dim, so the Lightmap vanilla is
    // currently rendering is never re-driven mid-frame; each Lightmap has its own UBO ring (the
    // block-era-proven mechanic).
    private final LightmapRenderState renderState;

    public DimensionRenderHelper(Level world) {
        this.world = world;

        if (client.level == world) {
            this.lightmapTexture =
                ((GameRendererAccessorMixin) client.gameRenderer).seamlessportals$getLightmap();
        }
        else {
            this.lightmapTexture = new Lightmap();
            Helper.log("Created lightmap texture for " + world.dimension().identifier());
        }
        this.renderState = new LightmapRenderState();
    }

    public void tick() {
        // See header: the per-dim recompute is driven by updateAndRender() at the render-context
        // switch (S13), not here. tick() guards the identity — never re-drive the shared main lightmap.
        // Inert for the held phase.
    }

    /**
     * The mod-proven 26.2 per-dimension lightmap driver (re-homed verbatim from
     * MOD:render/DimensionRenderHelper.java). Extracts a LightmapRenderState from the destination
     * {@code virtualCamera}'s attribute probe (dimension-specific biome/environment values) and
     * renders it into this helper's Lightmap. Invoked at the render-context switch (S13 wiring).
     */
    public void updateAndRender(Camera virtualCamera, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // S14-A FIX-7: no renderState==null early-return anymore — every helper can self-render
        // (see the renderState field note; the call-site isDimensionRendered gate keeps the
        // current main dim's vanilla-driven Lightmap out of here).

        renderState.needsUpdate = true;

        // Block light flicker (matches LightmapRenderStateExtractor.tick())
        blockLightFlicker += (random.nextFloat() - random.nextFloat())
            * random.nextFloat() * random.nextFloat() * 0.1F;
        blockLightFlicker *= 0.9F;

        // Extract from virtual camera's attribute probe — gives destination dimension values.
        // This is the EXACT same logic as LightmapRenderStateExtractor.extract().
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
            renderState.nightVisionEffectIntensity = GameRenderer.nightVisionScale(mc.player, partialTicks);
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
        lightmapTexture.render(renderState);
    }

    private static float calculateDarknessScale(LivingEntity entity, float darknessGamma, float partialTicks) {
        float darkness = 0.45F * darknessGamma;
        return Math.max(0.0F, Mth.cos((entity.tickCount - partialTicks)
            * (float) Math.PI * 0.025F) * darkness);
    }

    public void cleanUp() {
        // IP guarded on "not the main gameRenderer's lightmap"; the 26.2 identity is the mod accessor.
        if (lightmapTexture
            != ((GameRendererAccessorMixin) client.gameRenderer).seamlessportals$getLightmap()) {
            lightmapTexture.close();
        }
    }

}
