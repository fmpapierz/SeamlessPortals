package qouteall.imm_ptl.peripheral.mixin.client.portal_wand;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

/**
 * S19-A2 — the 26.2 RE-SITE of IP's MixinDebugRenderer ("let's put portal wand marking render
 * into debug renderer", DebugRenderer.render @RETURN). 26.2-forced (F6 class): that hook is
 * GONE — {@code DebugRenderer.render(PoseStack, BufferSource, ...)} became
 * {@code emitGizmos(...)} with no PoseStack/BufferSource, and the {@code Gizmos} API cannot
 * express the wand's matrix-driven primitives (rotated cube frames, flowing rect loops,
 * matrix-baked grids). The equivalent 26.2 slot is {@code LevelRenderer.submitFeatures}
 * @RETURN — the frame point where entities/BEs/particles/outline/gizmos have all been
 * submitted (26.2 LevelRenderer.java:279-294), i.e. the same "after everything" position IP's
 * DebugRenderer hook occupied. Delivery = ONE {@code submitCustomGeometry} on
 * {@code RenderTypes.lines()} (TRANSLUCENT blend → translucent bucket → after-terrain draw,
 * matching IP's late immediate flush; the proven PortalEntityRenderer:88-96 template).
 *
 * <p>PASS EXPOSURE: submitFeatures also runs for the mod's CROSS-DIM dest portal passes
 * (SecondaryWorldRenderCore / PortalContextSwitch invoke it with destLRS + the dest storage) —
 * IP's DebugRenderer hook likewise ran inside nested portal-view renderLevel passes.
 * Per-pass camera comes from {@code levelRenderState.cameraRenderState.pos} (the argument —
 * dest camera on dest passes, exactly like IP's nested camX/Y/Z args; NULL is the ledgered
 * no-information state on decomposed passes → refuse, S18 rule). CREATE mode draws in
 * cross-dim portal views as in IP; Drag/Copy self-guard via
 * {@code PortalRendering.isRendering()} inside their render bodies (IP-verbatim).
 * NAMED DEVIATION (verify wf_88355dbb-8f3; port-note S19 §2): the SAME-DIM portal pass
 * ({@code renderPortalEntitiesSameDim}) never calls submitFeatures, so the wand overlay is
 * ABSENT in same-dim/loop-back portal views where IP's nested renderLevel showed it —
 * cross-dim views have it; polish candidate, LOW (the spectral-glow residual class).
 * The identity-base PoseStack + camera-relative vertex math draws under each pass's own
 * modelview like every other submitted feature.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_PortalWand {
    @Inject(method = "submitFeatures", at = @At("RETURN"))
    private void onSubmitFeatures(
        LevelRenderState levelRenderState,
        SubmitNodeCollector submitNodeCollector,
        boolean renderOutline,
        CallbackInfo ci
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        ItemStack itemStack = player.getMainHandItem();

        if (itemStack.getItem() == PortalWandItem.instance) {
            Vec3 camPos = levelRenderState.cameraRenderState.pos;
            // S18 null-is-no-information: on decomposed dest passes a null camera pos means
            // the extract didn't run for this pass — refuse rather than NPE inside
            // prepareFrame (the sibling S18 consumers do the same).
            if (camPos == null) {
                return;
            }
            submitNodeCollector.submitCustomGeometry(
                new PoseStack(),
                RenderTypes.lines(),
                (pose, buffer) -> {
                    PoseStack local = new PoseStack();
                    local.last().set(pose);
                    PortalWandItem.clientRender(
                        player, itemStack, local, buffer, camPos.x, camPos.y, camPos.z
                    );
                }
            );
        }
    }
}
