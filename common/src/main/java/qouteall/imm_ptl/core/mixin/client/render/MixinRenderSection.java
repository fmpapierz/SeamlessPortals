package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IERenderSection;

/**
 * S12-B (render client-mixin half) — IP {@code MixinRenderSection}
 * ({@code IP:mixin/client/render/MixinRenderSection.java}), 26.2-RETARGETED (mixin-client.md §7,
 * NEEDS-RETARGET).
 *
 * <p><b>Retargets.</b> {@code portal_mark} stays a custom {@code @Unique}-style added field (unchanged).
 * {@code reset()} is now PUBLIC on 26.2 ({@code 26.2:SectionRenderDispatcher.java:257}; vanilla itself
 * calls it from {@code ViewArea.releaseAllBuffers}), so IP's private-invoker rationale is gone — the
 * {@code @Shadow} of {@code reset()} still resolves and {@code portal_fullyReset()} delegates to it.
 * {@code index} is {@code public final int} ({@code :205}); writing it via {@code portal_setIndex} needs
 * {@code @Mutable}. Held/UNREGISTERED until S13.
 *
 * <p>{@code ImmPtlViewArea}'s raw grid adapts to 26.2's {@code RotatingSectionStorage} at the R4 layer
 * (ImmPtlViewArea + the S12-A install redirect); this duck only exposes the per-section mark/index/reset.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class MixinRenderSection implements IERenderSection {

    private long portal_mark;

    @Shadow
    public abstract void reset();

    @Shadow
    @Final
    @Mutable
    public int index;

    @Override
    public void portal_fullyReset() {
        reset();
    }

    @Override
    public long portal_getMark() {
        return portal_mark;
    }

    @Override
    public void portal_setMark(long arg) {
        portal_mark = arg;
    }

    @Override
    public void portal_setIndex(int arg) {
        index = arg;
    }

}
