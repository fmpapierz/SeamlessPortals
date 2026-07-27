package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamRailContinuity;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import com.warwa.seamlessportals.passthrough.SeamShadow;
import com.warwa.seamlessportals.passthrough.SeamShadowBridge;
import com.warwa.seamlessportals.passthrough.SeamShadowHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.RailState;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RS PASSTHROUGH (b) — RAILS CONNECT ACROSS THE SEAM. The one consumer of the step-1 primitive:
 * every world read {@code RailState} makes is answered across the seam when the probed position is a
 * window onto the far dimension, and the two writes are routed to the far rail they belong to.
 *
 * <h2>The model</h2>
 * Two kinds of instance, told apart per-instance (never by position — on an obsidian frame the
 * approach cells on BOTH sides are real, buildable local cells):
 * <ul>
 *   <li><b>OWNER</b> — a {@code RailState} constructed at a bound seam cell. Reads are LOCAL-FIRST
 *       (R1&#x2032;): the cross cell is consulted only when the local cell holds no rail. This makes
 *       (b) purely additive — it can add a connection vanilla would miss, never override one vanilla
 *       would make — and makes vanilla non-regression a theorem for all-local geometry.</li>
 *   <li><b>PROXY</b> — a child {@code RailState} minted by {@code getRail} from a CROSS read: its
 *       {@code pos} is a local shadow coordinate and its {@code state} is the far rail's state
 *       rotated into the local frame. For a proxy the shadow IS the frame: every read and both
 *       writes route through it, which is how the far rail's own shape gets rewritten to meet
 *       ours.</li>
 * </ul>
 *
 * <p><b>The stamp re-checks locality before attaching a shadow.</b> The design-panel spec stamped
 * every child whose position lies past the plane — but on a mid-block seam the co-located approach
 * cell is ALSO past the plane in the other binding's frame, so a child built from a genuine LOCAL
 * rail standing there would have been stamped as a far proxy, and its {@code connectTo} write would
 * have landed in the far dimension while the local rail it was actually built from went untouched.
 * A child is only a proxy if the local world does NOT hold a rail at its position (local-first
 * already guarantees the child's state came from the cross read in exactly that case).
 *
 * <p>Every handler opens with the runtime entity-portals check because
 * {@code SeamlessMixinConfigPlugin} gates only {@code qouteall.*} mixins.
 */
@Mixin(RailState.class)
public abstract class MixinRailStateSeam implements SeamShadowHolder {

    @Shadow @Final private Level level;
    @Shadow @Final private BlockPos pos;

    @Unique @Nullable private SeamShadow seamlessportals$shadow;
    @Unique @Nullable private SeamRegistry.SeamCell seamlessportals$owner;
    @Unique private boolean seamlessportals$armed;

    @Override
    public BlockPos seamlessportals$pos() {
        return this.pos;
    }

    @Override
    @Nullable
    public SeamShadow seamlessportals$shadow() {
        return this.seamlessportals$shadow;
    }

    @Override
    public void seamlessportals$setShadow(SeamShadow shadow) {
        this.seamlessportals$shadow = shadow;
    }

    // THE FOLD: one LongOpenHashSet.contains per RailState construction, then a reference-null check
    // per probe. In a world with no portals the section set is empty and this is one isEmpty().
    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void seamlessportals$cacheOwner(Level lvl, BlockPos p, BlockState st, CallbackInfo ci) {
        this.seamlessportals$armed = !AperturePassthroughLever.DISABLED
            && !AperturePassthroughLever.DISABLE_SEAM_SHADOW
            && SeamlessPortalsConfig.isEntityPortals()
            && lvl instanceof ServerLevel
            && SeamRegistry.sectionHasSeam(lvl, p);
        this.seamlessportals$owner =
            this.seamlessportals$armed ? SeamRegistry.lookup(lvl, p) : null;
    }

    @Unique
    @Nullable
    private SeamShadow seamlessportals$find(BlockPos query) {
        if (this.seamlessportals$shadow != null) {
            return this.seamlessportals$shadow;   // inherit, never nest
        }
        if (this.seamlessportals$owner == null) {
            return null;
        }
        return SeamShadowBridge.shadowFor(this.level, this.seamlessportals$owner, this.pos, query, 1);
    }

    // ── H1a: hasRail. 3 isRail(Level,BlockPos) sites, all on RailState.java:91. ──
    @WrapOperation(
        method = "hasRail",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BaseRailBlock;"
            + "isRail(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"),
        require = 3, allow = 3
    )
    private boolean seamlessportals$isRailHasRail(Level l, BlockPos q, Operation<Boolean> op) {
        return seamlessportals$isRail(l, q, op);
    }

    // ── H1b: connectTo. 4 sites: :181, :185, :191, :195 (the ascending probes). ──
    @WrapOperation(
        method = "connectTo",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BaseRailBlock;"
            + "isRail(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"),
        require = 4, allow = 4
    )
    private boolean seamlessportals$isRailConnectTo(Level l, BlockPos q, Operation<Boolean> op) {
        return seamlessportals$isRail(l, q, op);
    }

    // ── H1c: place. 4 sites: :307, :311, :317, :321. ──
    @WrapOperation(
        method = "place",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BaseRailBlock;"
            + "isRail(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z"),
        require = 4, allow = 4
    )
    private boolean seamlessportals$isRailPlace(Level l, BlockPos q, Operation<Boolean> op) {
        return seamlessportals$isRail(l, q, op);
    }

    @Unique
    private boolean seamlessportals$isRail(Level l, BlockPos q, Operation<Boolean> op) {
        SeamShadow s = this.seamlessportals$shadow;
        if (s != null) {
            return BaseRailBlock.isRail(s.readLocal(q));   // PROXY: the shadow IS the frame
        }
        if (this.seamlessportals$owner == null) {
            return op.call(l, q);
        }
        if (op.call(l, q)) {
            return true;                                   // ── R1' LOCAL FIRST ──
        }
        SeamShadow far = seamlessportals$find(q);
        if (far == null) {
            return false;
        }
        boolean hit = BaseRailBlock.isRail(far.readLocal(q));
        if (hit) {
            SeamRailContinuity.crossHit();
        }
        return hit;
    }

    // ── H2: getRail's three state reads, :96, :102, :108. LOCAL-FIRST. ──
    @WrapOperation(
        method = "getRail",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "getBlockState(Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 3, allow = 3
    )
    private BlockState seamlessportals$readGetRail(Level l, BlockPos q, Operation<BlockState> op) {
        SeamShadow s = this.seamlessportals$shadow;
        if (s != null) {
            return s.readLocal(q);
        }
        if (this.seamlessportals$owner == null) {
            return op.call(l, q);
        }
        BlockState local = op.call(l, q);
        if (BaseRailBlock.isRail(local)) {
            return local;                                  // ── R1' LOCAL FIRST ──
        }
        SeamShadow far = seamlessportals$find(q);
        if (far == null) {
            return local;
        }
        BlockState cross = far.readLocal(q);
        if (BaseRailBlock.isRail(cross)) {
            SeamRailContinuity.crossHit();
            return cross;
        }
        return local;
    }

    // ── H2b: place:332, the idempotence guard. NOT local-first: a proxy's this.pos is a shadow
    //         coordinate and must map through, or the guard compares two different frames. ──
    @WrapOperation(
        method = "place",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "getBlockState(Lnet/minecraft/core/BlockPos;)"
            + "Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 1, allow = 1
    )
    private BlockState seamlessportals$readPlaceGuard(Level l, BlockPos q, Operation<BlockState> op) {
        SeamShadow s = this.seamlessportals$shadow;
        return s != null ? s.readLocal(q) : op.call(l, q);
    }

    // ── H3a: connectTo:205. H3b: place:333. A proxy's this.pos is a local coordinate past the
    //         plane; writing it locally would spawn a rail in the SOURCE dimension behind the portal. ──
    @WrapOperation(
        method = "connectTo",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "setBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
        require = 1, allow = 1
    )
    private boolean seamlessportals$writeConnectTo(
        Level l, BlockPos p, BlockState st, int flags, Operation<Boolean> op
    ) {
        return seamlessportals$write(l, p, st, flags, op);
    }

    @WrapOperation(
        method = "place",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;"
            + "setBlock(Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
        require = 1, allow = 1
    )
    private boolean seamlessportals$writePlace(
        Level l, BlockPos p, BlockState st, int flags, Operation<Boolean> op
    ) {
        return seamlessportals$write(l, p, st, flags, op);
    }

    @Unique
    private boolean seamlessportals$write(
        Level l, BlockPos p, BlockState st, int flags, Operation<Boolean> op
    ) {
        SeamShadow s = this.seamlessportals$shadow;
        if (s == null) {
            return op.call(l, p, st, flags);
        }
        // A PROXY's position is a shadow coordinate. With the write lever pulled the far write is
        // DROPPED, never executed locally: op.call here would spawn a phantom rail in the SOURCE
        // dimension behind the portal. (Panel finding, 2026-07-27 — the spec's own §3.2 carried
        // this exact bug.)
        if (AperturePassthroughLever.DISABLE_SEAM_RAIL_WRITE) {
            return false;
        }
        return s.writeLocal(p, st, flags);
    }

    // ── THE SHADOW HANDOFF. @At("RETURN") catches every return site — FOUR in bytecode, not the
    //    three the source shows: the :109 ternary compiles to two areturns (rail / null). The
    //    child's constructor has already run updateConnections, which is pure BlockPos arithmetic
    //    and needs no shadow. Stamped against the CHILD's pos, not the queried one — getRail may
    //    have found the rail at queried.above() or .below(). ──
    @Inject(method = "getRail", at = @At("RETURN"), require = 4, allow = 4)
    private void seamlessportals$stamp(BlockPos queried, CallbackInfoReturnable<RailState> cir) {
        RailState child = cir.getReturnValue();
        if (child == null) {
            return;
        }
        SeamShadowHolder h = (SeamShadowHolder) (Object) child;
        if (h.seamlessportals$shadow() != null) {
            return;   // already stamped (defensive; children are freshly constructed)
        }
        BlockPos childPos = h.seamlessportals$pos();
        if (this.seamlessportals$shadow == null) {
            // OWNER path: only a child whose state came from a CROSS read becomes a proxy. If the
            // local world holds a rail there, local-first means the child IS that local rail — see
            // the class note for why stamping it would misroute its write.
            if (this.seamlessportals$owner == null) {
                return;
            }
            if (BaseRailBlock.isRail(this.level.getBlockState(childPos))) {
                return;
            }
        }
        SeamShadow s = seamlessportals$find(childPos);
        if (s != null) {
            h.seamlessportals$setShadow(s);
        }
    }
}
