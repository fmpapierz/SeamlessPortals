package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// S12-A (Slice C) — VERBATIM IP duck (IP:imm_ptl/core/mixin/client/render/IESectionRenderDispatcher.java).
// Held/UNREGISTERED (not listed in seamlessportals-ip-client.mixins.json "client":[]); registered into the
// S12 client-mixin set and activated flag-ON at S13.
//
// 26.2 TARGET STILL PRESENT: SectionRenderDispatcher.fixedBuffers survives on 26.2 as a
// `private final SectionBufferBuilderPack fixedBuffers` (26.2:.../chunk/SectionRenderDispatcher.java:46,
// initialised `renderBuffers.fixedBufferPack()` :64) — both the target class and the SectionBufferBuilderPack
// type resolve, so the verbatim accessor compiles.
//
// LANDED PER THE S11 FORWARD-REF LEDGER (S12 accessor); the SWAP is NOT wired yet. IP swapped the secondary
// dispatcher's fixed buffers for per-dim buffer isolation; MyGameRenderer DROPPED that call
// (S11B-render-drivers.md §1.1: "the mod's live path isolates via the RenderBuffers pool swap alone") and
// CUTOVER_SPEC §1.4 named the necessity a NAMED S13/S14 runtime check (stranded-upload watch). This accessor
// is landed so the driver core can wire the fixed-buffer swap ONLY if that runtime check requires it — never
// pre-committed. Compile surface present now; behavior deferred.
@Mixin(SectionRenderDispatcher.class)
public interface IESectionRenderDispatcher {
    @Accessor("fixedBuffers")
    SectionBufferBuilderPack ip_getFixedBuffers();

    @Mutable
    @Accessor("fixedBuffers")
    void ip_setFixedBuffers(SectionBufferBuilderPack arg);
}
