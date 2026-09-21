package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

/**
 * S12-B (render client-mixin half) — IP {@code MixinScreenEffectRenderer}
 * ({@code IP:mixin/client/render/MixinScreenEffectRenderer.java}), 26.2-RETARGETED (mixin-client.md §7
 * NEEDS-RETARGET).
 *
 * <p><b>The in-wall overlay draw is submit-based now.</b> {@code renderTex(TextureAtlasSprite, PoseStack)}
 * is GONE; the block-in-wall sprite path is the static
 * {@code submitBlockSprite(TextureAtlasSprite, PoseStack, SubmitNodeCollector, int)}
 * ({@code 26.2:ScreenEffectRenderer.java:146}), driven from {@code submit(...)} (the 4th param is a
 * {@code SubmitNodeCollector} — NOT {@code SubmitNodeStorage}; both classes exist and the wrong one yields a
 * wrong injector descriptor). The suffocation-overlay cancel re-anchors onto {@code submitBlockSprite} HEAD
 * under the same IP conditions. Held/UNREGISTERED until S13.
 */
@Mixin(ScreenEffectRenderer.class)
public class MixinScreenEffectRenderer {
    // avoid rendering suffocating when colliding with portal
    @Inject(
        method = "submitBlockSprite",
        at = @At("HEAD"),
        cancellable = true
    )
    // 26.3: static submitBlockSprite(TextureAtlasSprite, PoseStack, SubmitNodeCollector, int) ->
    // submitBlockSprite(Identifier atlasLocation, float u0, float v0, float u1, float v1, PoseStack, SubmitNodeCollector, int)
    // (mc262-ref ScreenEffectRenderer.java:66,146 -> mc263-ref :40-49; javap 26.3: private static, descriptor
    // (Lnet/minecraft/resources/Identifier;FFFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V).
    // The sprite is resolved at EXTRACT time now (mc263-ref LevelExtractor.java:438-445 -> PlayerRenderState.blockOverlay) and
    // only its atlas + UVs reach this method. Still the one in-wall-overlay draw, still driven from submit(..); the handler
    // never read the sprite, so only its parameter list changes.
    private static void onRenderInWallOverlay(
        net.minecraft.resources.Identifier atlasLocation,
        float u0,
        float v0,
        float u1,
        float v1,
        PoseStack matrices,
        SubmitNodeCollector submitNodeCollector,
        int packedLight,
        CallbackInfo ci
    ) {
        if (PortalRendering.isRendering()) {
            ci.cancel();
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            if (((IEEntity) player).ip_getCollidingPortal() != null) {
                ci.cancel();
            }
        }
        if (ClientTeleportationManager.isTeleportingFrequently()) {
            ci.cancel();
        }
    }
}
