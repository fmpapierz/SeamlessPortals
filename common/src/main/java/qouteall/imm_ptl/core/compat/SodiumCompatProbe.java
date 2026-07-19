package qouteall.imm_ptl.core.compat;

import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.compat.SodiumCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * C2-0 probe P1 (runtime half) — {@code migration/C2_DESIGN.md} §4 P1, stage C2-0 deliverable 4(iii).
 *
 * <p>Answers: do the mod's SECONDARY per-dim {@link LevelRenderer}s (created by
 * {@link qouteall.imm_ptl.core.ClientWorldLoader#createSecondaryClientWorld}) acquire an
 * initialised Sodium {@code SodiumWorldRenderer} + {@code RenderSectionManager}, and are those
 * instances per-dim-distinct or shared? It logs, ONE-SHOT per {@code ClientLevel} dimension, the
 * {@link System#identityHashCode} of {@code ((LevelRendererExtension) renderer).sodium$getWorldRenderer()}
 * and of its {@code renderSectionManager} (reached through the newly-registered
 * {@code IESodiumWorldRenderer} accessor).
 *
 * <p><b>Three guards, checked in order — ZERO effect and ZERO Sodium classloading without all
 * three:</b>
 * <ol>
 *   <li>the {@code -Dseamlessportals.compatProbe=true} lever ({@link #PROBE_ENABLED}, read once);</li>
 *   <li>{@link EntityPortalsFlag#isOn()} (flag-ON substrate only);</li>
 *   <li>{@link SodiumCompat#isSodiumLoaded()} (Sodium actually installed).</li>
 * </ol>
 *
 * <p><b>Lazy-classload discipline.</b> This top-level class references NO Sodium type — it is safe
 * to load on any loader (NeoForge included, where the Sodium types are compileOnly-only and absent
 * at runtime). Every reference to a Sodium type lives in the nested {@link SodiumBridge}, whose
 * methods run ONLY after the {@code isSodiumLoaded()} guard passes, so the nested class (a separate
 * {@code .class} file) is never even loaded when Sodium is absent.
 */
@Environment(EnvType.CLIENT)
public final class SodiumCompatProbe {

    /** The compat-probe lever, read once at class-init (a JVM property fixed at launch). */
    public static final boolean PROBE_ENABLED = Boolean.getBoolean("seamlessportals.compatProbe");

    /** One-shot-per-dimension guard (render thread, but synchronised for safety). */
    private static final Set<ResourceKey<Level>> LOGGED_DIMS =
        Collections.synchronizedSet(new HashSet<>());

    private SodiumCompatProbe() {}

    /**
     * Probe a freshly-created secondary per-dim renderer. Call site:
     * {@code ClientWorldLoader.createSecondaryClientWorld}, right after the renderer is put into
     * {@code WORLD_RENDERER_MAP}. Fully guarded + one-shot per dimension.
     */
    public static void probeSecondaryWorldRenderer(ResourceKey<Level> dimension, LevelRenderer renderer) {
        if (!PROBE_ENABLED) {
            return;
        }
        if (!EntityPortalsFlag.isOn()) {
            return;
        }
        if (!SodiumCompat.isSodiumLoaded()) {
            return;
        }
        if (dimension == null || renderer == null) {
            return;
        }
        if (!LOGGED_DIMS.add(dimension)) {
            return; // already logged this dim
        }
        try {
            SodiumBridge.log(dimension, renderer);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.info(
                "[COMPAT PROBE P1] {} — could not read Sodium world renderer: {}",
                dimension.identifier(), t.toString());
        }
    }

    /**
     * The ONLY Sodium-touching code. Loaded (and verified) lazily — never when Sodium is absent,
     * because {@link #probeSecondaryWorldRenderer} only reaches here past the
     * {@code isSodiumLoaded()} guard.
     */
    private static final class SodiumBridge {
        static void log(ResourceKey<Level> dimension, LevelRenderer renderer) {
            net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer swr =
                ((net.caffeinemc.mods.sodium.client.world.LevelRendererExtension) renderer)
                    .sodium$getWorldRenderer();
            if (swr == null) {
                SeamlessPortalsConstants.LOGGER.info(
                    "[COMPAT PROBE P1] dim={} renderer#{} sodium$getWorldRenderer()=null "
                        + "(secondary renderer has NO Sodium world renderer yet)",
                    dimension.identifier(), System.identityHashCode(renderer));
                return;
            }
            net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager rsm =
                ((qouteall.imm_ptl.core.compat.mixin.sodium.IESodiumWorldRenderer) (Object) swr)
                    .ip_getRenderSectionManager();
            SeamlessPortalsConstants.LOGGER.info(
                "[COMPAT PROBE P1] dim={} renderer#{} SodiumWorldRenderer#{} RenderSectionManager#{}",
                dimension.identifier(),
                System.identityHashCode(renderer),
                System.identityHashCode(swr),
                rsm == null ? "null" : Integer.toString(System.identityHashCode(rsm)));
        }
    }
}
