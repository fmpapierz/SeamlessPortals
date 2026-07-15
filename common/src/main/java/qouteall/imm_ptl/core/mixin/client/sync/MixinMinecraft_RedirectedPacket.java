package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.network.PacketRedirectionClient;

/**
 * R7 §A step 3 — the client half of the order-faithful packet redirection (entity-portal migration
 * S12-B; port-notes S07-network.md §A step 3 / handoff, SPIKE-R7-requeue §5.3, S12B-behavior.md §2).
 *
 * <p>Companion to the common mixin {@code mixin/common/networking/MixinClientboundCustomPayloadPacket} —
 * its netty-pass {@code scheduleIfPossible(listener, outerPacket)} re-queue is R7 §A step 2. (That common
 * mixin was committed VERBATIM-STOCK at S10.2, MISSING the S07-network.md §2 correction; the re-queue was
 * applied to it HERE at S12-B — see S12B-behavior.md §2.) Together they
 * make the redirected-dimension packet handling ordering-preserving — the SPIKE-R7-confirmed correction over
 * the naive {@code minecraft.execute} EXECUTE shape that SPIKE proved REORDERS. This mixin narrows the
 * redirection so it applies ONLY to {@code Minecraft.execute} tasks submitted DURING redirected handling:
 * {@code wrapRunnable} tags such a task to run inside the redirected world, and {@code scheduleExecutables}
 * is overridden so the redirected handling is never delayed.
 *
 * <p><b>PORTS-CLEAN on 26.2 (api-map/mixin-client.md §9).</b> {@code Minecraft extends
 * ReentrantBlockableEventLoop<Runnable>} (`Minecraft.java:261`); {@code public Runnable wrapRunnable(Runnable)}
 * survives at `Minecraft.java:2668` (the {@code @Inject} descriptor is unchanged). The {@code @IPVanillaCopy}
 * {@code scheduleExecutables} body was RE-DIFFED against the 26.2 base this stage and is UNCHANGED: 26.2
 * {@code ReentrantBlockableEventLoop.scheduleExecutables()} = {@code this.runningTask() || super.scheduleExecutables()}
 * and {@code BlockableEventLoop.scheduleExecutables()} = {@code !this.isSameThread()}
 * (`ReentrantBlockableEventLoop.java:11-13`, `BlockableEventLoop.java:49-50`), so the vanilla-copied tail
 * {@code return this.runningTask() || !onThread;} (with {@code onThread = isSameThread()}) reproduces
 * {@code this.runningTask() || !this.isSameThread()} exactly. {@code runningTask()} is {@code protected} on
 * the shadow-extended base; {@code isSameThread()} is {@code public} on {@code BlockableEventLoop}; the
 * {@code public} override widening a {@code protected} base method is legal.
 *
 * <p>Held-UNREGISTERED (no {@code mixins.json} edit); registered flag-ON at S13 with the client-mixin set.
 * javadoc-{@code @link}-imported by {@code PacketRedirectionClient} (S07 handoff).
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft_RedirectedPacket extends ReentrantBlockableEventLoop<Runnable> {

    // 26.2 API-map translation: ReentrantBlockableEventLoop's ctor gained a `boolean propagatesCrashes`
    // param — 1.21.3 (String) -> 26.2 (String, boolean) (ReentrantBlockableEventLoop.java:6). This fake
    // shadow-extend ctor is never invoked (the mixin bytecode is merged into Minecraft, not instantiated);
    // it exists only so the mixin compiles against the base — the argument values are irrelevant.
    public MixinMinecraft_RedirectedPacket(String string, boolean bl) {
        super(string, bl);
    }

    // ensure that the task is processed with the redirected dimension
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;wrapRunnable(Ljava/lang/Runnable;)Ljava/lang/Runnable;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onCreateTask(Runnable runnable, CallbackInfoReturnable<Runnable> cir) {
        Minecraft this_ = (Minecraft) (Object) this;

        ResourceKey<Level> redirectedDimension = PacketRedirectionClient.clientTaskRedirection.get();
        if (redirectedDimension != null) {
            Runnable newRunnable = () -> {
                ClientWorldLoader.withSwitchedWorldFailSoft(redirectedDimension, runnable);
            };
            cir.setReturnValue(newRunnable);
        }
    }

    /**
     * Make sure that the redirected packet handling won't be delayed.
     * If not on thread, it will delay handling.
     * If running task, it will delay handling.
     * If on thread and running task, normally it will delay handling, but this override
     *  makes it to not delay when processing redirected packet.
     */
    @IPVanillaCopy
    @Override
    public boolean scheduleExecutables() {
        boolean onThread = isSameThread();

        if (onThread) {
            if (PacketRedirectionClient.getIsProcessingRedirectedMessage()) {
                return false;
            }
        }

        return this.runningTask() || !onThread;
    }
}
