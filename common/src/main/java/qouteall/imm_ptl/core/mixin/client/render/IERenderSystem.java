package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// S12-A (Slice C) — VERBATIM IP duck (IP:imm_ptl/core/mixin/client/render/IERenderSystem.java). Held/
// UNREGISTERED (not listed in seamlessportals-ip-client.mixins.json "client":[]); it is registered into
// the S12 client-mixin set and activated flag-ON at S13.
//
// 26.2 TARGET STILL PRESENT: RenderSystem.modelViewStack is a private static Matrix4fStack
// (26.2:com/mojang/blaze3d/systems/RenderSystem.java:60), with a public getModelViewStack() (:208) — so
// this verbatim accessor compiles against a live field and its @Mixin/return types resolve unchanged.
//
// LANDED PER THE S11 FORWARD-REF LEDGER (S12 accessor), NOT because MyGameRenderer needs it: MyGameRenderer
// RE-EXPRESSED IP's ip_get/setModelViewStack(new Matrix4fStack) stack-object SWAP (G28, meaningless on
// 26.2) onto push identity on RenderSystem.getModelViewStack() / pop on restore (S11B-render-drivers.md
// §1.1), so no IERenderSystem call remains there. The duck is landed for the S12 concrete-renderer family
// (PortalRenderer / RendererUsingStencil) that consumes the model-view-stack accessor.
@Mixin(RenderSystem.class)
public interface IERenderSystem {
    @Accessor(value = "modelViewStack", remap = false)
    public static Matrix4fStack ip_getModelViewStack() {
        throw new RuntimeException();
    }

    @Mutable
    @Accessor(value = "modelViewStack", remap = false)
    public static void ip_setModelViewStack(Matrix4fStack arg) {
        throw new RuntimeException();
    }
}
