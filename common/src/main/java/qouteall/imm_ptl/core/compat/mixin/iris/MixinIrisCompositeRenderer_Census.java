package qouteall.imm_ptl.core.compat.mixin.iris;

import com.warwa.seamlessportals.render.IrisCompositeCensus;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * IS5-CEN (roster harvest) — names the program ids the bind witness reports. Log-only, default OFF
 * ({@code -Dseamlessportals.compositeCensus}).
 *
 * <p>{@link MixinIrisProgram_Census} witnesses binds by <b>program id</b>, which is the integer the
 * driver actually binds but tells a reader nothing. This hook walks {@code CompositeRenderer.passes}
 * once per renderer instance and records id → {@code name} / {@code drawBuffers} /
 * {@code stageReadsFromAlt} / viewport, so every census row arrives already named and already carrying
 * its ping-pong parity. The {@code drawBuffers} column is not decoration: the Motion Blur toggle is what
 * turns {@code composite4}'s {@code DRAWBUFFERS:3} into {@code :30}, and that write is the leading
 * alternative explanation if the velocity columns all come back zero.
 *
 * <p>HEAD, before the pass loop, so the roster is always populated before the first
 * {@code Program.use()} of that chain. Harvest is once per renderer identity: a cross-dim frame runs a
 * SECOND {@code CompositeRenderer} (iris keeps one pipeline per dimension) and both get harvested, which
 * is how a census row can distinguish "the same pass bound twice" from "two different passes that share
 * a name across pipelines".
 *
 * <p>Behaviour-neutral: {@code @Inject} at HEAD, never {@code cancellable}, no {@code @Shadow}, no
 * {@code @Local}. {@code this} crosses as {@code Object}; the census reads the fields reflectively and
 * disarms itself with one WARN if an iris update renames them. {@code require = 0} and an "Iris" simple
 * name for the same reasons as the sibling mixins.
 */
@Pseudo
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class MixinIrisCompositeRenderer_Census {

    @Inject(method = "renderAll", at = @At("HEAD"), remap = false, require = 0)
    private void seamlessportals$censusRoster(CallbackInfo ci) {
        IrisCompositeCensus.onRenderAllHead(this);
    }
}
