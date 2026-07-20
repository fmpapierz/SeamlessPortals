package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;

/**
 * C2-1 vA2 finding 1 — the reusable {@code mc.levelRenderer} repoint bracket for secondary-dim
 * dirty marking.
 *
 * <p>WHY: sodium 0.9.1's {@code LevelExtractorMixin} resolves the {@code SodiumWorldRenderer}
 * via {@code Minecraft.getInstance().levelRenderer} AT CALL TIME in the whole dirty-marking
 * family — {@code checkRenderer()} (bytecode offsets 0-15: getfield {@code Minecraft
 * .levelRenderer} → {@code LevelRendererExtension.sodium$getWorldRenderer}) is the first call
 * inside its {@code setSectionDirty(IIIZ)}, {@code setBlocksDirty}, {@code setBlockDirty} and
 * {@code setSectionDirtyWithNeighbors} overwrites (javap-proven against
 * {@code sodium-mc26.2-0.9.1-fabric.jar}, sha 14f3388…). So a mod-side call on a SECONDARY
 * dim's per-dim {@link net.minecraft.client.renderer.extract.LevelExtractor} lands the rebuild
 * on whatever renderer {@code mc.levelRenderer} currently holds — the MAIN SWR, unless the call
 * already runs inside a renderer swap ({@code ClientWorldLoader.withSwitchedWorld} swaps it at
 * ClientWorldLoader.java:1175; the teleport cutover at ClientTeleportationManager:501). This
 * bracket makes the remaining outside-any-swap seams (enumerated at the call sites) resolve the
 * MATCHING per-dim renderer, exactly the mechanism the {@code setLevel} brackets in
 * {@code ClientWorldLoader} (BLOCKER-1a) already use.
 *
 * <p>FAST PATH: when the dim's renderer already IS {@code mc.levelRenderer} — the ACTIVE dim,
 * or any call inside {@code withSwitchedWorld}/the teleport cutover — no repoint happens (one
 * cached-boolean check + one map get + a reference compare). Sodium absent: the boolean check
 * alone, then the plain call — this class references NO sodium types and is safe to class-load
 * on a sodium-absent runtime (presence is consulted per-call via
 * {@link com.warwa.seamlessportals.compat.SodiumCompat#isSodiumLoaded()}).
 *
 * <p>THREAD: the repoint is NOT thread-safe ({@code mc.levelRenderer} is the render thread's
 * global). Every current caller is main/render-thread-proven (see the per-site evidence in the
 * C2-1 fold report); the {@code isSameThread()} guard below is a defensive fallback that
 * degrades an off-thread call to the plain (pre-fix) behavior instead of corrupting the global.
 */
@Environment(EnvType.CLIENT)
public final class SodiumRendererRepoint {

    private SodiumRendererRepoint() {}

    /**
     * Runs {@code action} with {@code mc.levelRenderer} temporarily repointed to {@code dim}'s
     * per-dim renderer, so sodium's call-time {@code checkRenderer()} resolution routes any
     * dirty-marking inside {@code action} to the MATCHING per-dim {@code SodiumWorldRenderer}.
     * Restores in finally. Plain-runs the action (no repoint) when sodium is absent, off-thread,
     * when the dim has no registered renderer yet, or when {@code mc.levelRenderer} already is
     * that dim's renderer (the zero-overhead fast path — covers the active dim and calls already
     * inside a renderer swap).
     */
    public static void runWithRendererRepointed(ResourceKey<Level> dim, Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        if (!com.warwa.seamlessportals.compat.SodiumCompat.isSodiumLoaded()
            || !mc.isSameThread()
        ) {
            action.run();
            return;
        }
        // Raw map get, deliberately NOT getWorldRenderer(): no warn/create side effects on this
        // hot path. A dim without a registered renderer plain-runs (pre-fix behavior; the world
        // and renderer are created together, so a dirty mark for it has nothing to route to yet).
        LevelRenderer target = ClientWorldLoader.WORLD_RENDERER_MAP.get(dim);
        if (target == null || target == mc.levelRenderer) {
            action.run();
            return;
        }
        LevelRenderer saved = mc.levelRenderer;
        ((IEMinecraftClient) mc).ip_setWorldRenderer(target);
        try {
            action.run();
        }
        finally {
            ((IEMinecraftClient) mc).ip_setWorldRenderer(saved);
        }
    }
}
