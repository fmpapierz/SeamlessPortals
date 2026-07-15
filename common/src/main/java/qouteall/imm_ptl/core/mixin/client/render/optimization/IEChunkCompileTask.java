package qouteall.imm_ptl.core.mixin.client.render.optimization;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S12-B (render client-mixin half) — IP accessor duck
 * ({@code IP:mixin/client/render/optimization/IEChunkCompileTask.java}), 26.2-RETARGETED
 * (mixin-client.md §8, NEEDS-RETARGET).
 *
 * <p><b>Cancellation flag moved up the type hierarchy.</b> IP targeted the {@code isCancelled} flag on
 * 1.21.3's {@code SectionRenderDispatcher.RenderSection.CompileTask}. On 26.2 the flag is declared on the
 * new abstract base {@code SectionRenderDispatcher.RenderSection.SectionTask}
 * ({@code protected final AtomicBoolean isCancelled}, {@code 26.2:SectionRenderDispatcher.java:559}); the
 * package-private {@code CompileTask extends SectionTask} ({@code :418}) inherits it, and the dispatcher
 * gates on {@code task.isCancelled.get()} ({@code :88}). The accessor therefore retargets onto the public
 * abstract {@code SectionTask} where the field now lives (the {@code CompileTask} subtype is
 * package-private, so it cannot be named as a {@code @Mixin} class literal). Held/UNREGISTERED until S13.
 */
@Mixin(SectionRenderDispatcher.RenderSection.SectionTask.class)
public interface IEChunkCompileTask {
    @Accessor("isCancelled")
    AtomicBoolean getIsCancelled();
}
