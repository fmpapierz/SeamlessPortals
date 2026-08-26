package com.warwa.seamlessportals.compat;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.platform.Platform;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.ExperimentalCompatGate;
import qouteall.imm_ptl.core.compat.iris_compatibility.ExperimentalIrisPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.q_misc_util.my_util.MyTaskList;

/**
 * NF-PARITY W19 (2026-08-25): the S19-E/C2-4 render-compat detection + honest gating,
 * EXTRACTED VERBATIM from {@code SeamlessPortalsClientFabric.detectAndGateRenderCompat} /
 * {@code warnAndForcePortalRenderingOff} into {@code :common} so both loader client
 * entrypoints share one copy (the only loader-specific line was
 * {@code FabricLoader.isModLoaded} → {@link Platform#isModLoaded}). The full design
 * rationale lives on the original method's javadoc history (C2-4 per-mod verdicts under one
 * gate, D7 loud-resolve iris install, D11 presence-gated chunk-tracker feed) — kept in the
 * body comments below.
 *
 * <p>Must run flag-ON only, AFTER {@code IPModMainClient.init()} has loaded IPConfig — the
 * force below must win over the config-derived renderMode (both callers preserve that
 * ordering). Detection note: on NeoForge, first-party Sodium keeps the id {@code sodium}
 * and Iris keeps {@code iris}, so the same ids detect on both loaders (verified against
 * the Modrinth-published -neoforge builds).
 */
public final class RenderCompatGating {

    private RenderCompatGating() {}

    public static void detectAndGateRenderCompat() {
        boolean isSodiumPresent = Platform.get().isModLoaded("sodium");
        boolean isIrisPresent = Platform.get().isModLoaded("iris");

        // IP IPModEntryClient logs "Sodium is present"/"is not present" (Helper.log) — kept.
        SeamlessPortalsConstants.LOGGER.info("Sodium is {}present", isSodiumPresent ? "" : "not ");
        SeamlessPortalsConstants.LOGGER.info("Iris is {}present", isIrisPresent ? "" : "not ");

        boolean sodiumLever = Boolean.getBoolean("seamlessportals.experimentalSodiumCompat");
        boolean gateOrLever = ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT || sodiumLever;
        boolean sodiumActive = isSodiumPresent && gateOrLever;
        boolean irisActive = isIrisPresent && gateOrLever;

        if (irisActive) {
            // ===== C2-4 IRIS ACTIVE (D7 loud-resolve install) ==================================
            // OnIrisPresent classloads only here (lazy discipline). The ctor resolves the
            // IP-exact LevelRenderer.pipeline reflection LOUDLY (D7); a resolve failure marks
            // iris compat BROKEN → refuse the install AND drop the sodium verdict (sodium chains
            // must not run under an iris pipeline the bracket cannot detach) → the warn+force
            // branch below runs, exactly the pre-C2-4 posture. Never silent.
            IrisInterface.OnIrisPresent onIrisPresent = new IrisInterface.OnIrisPresent();
            if (onIrisPresent.ip_isPipelineFieldResolved()) {
                IrisInterface.invoker = onIrisPresent;
                // IP IPModEntryClient:95 — init() is empty (verified upstream + here); called at
                // IP's slot for fidelity. The Experimental renderer stays D9-deferred.
                ExperimentalIrisPortalRenderer.init();
            }
            else {
                irisActive = false;
                sodiumActive = false;
                SeamlessPortalsConstants.LOGGER.error(
                    "Seamless Portals: iris compat BROKEN (pipeline field unresolved — see the "
                        + "error above); falling back to warn + portal-views-off for this session.");
            }
        }

        if (sodiumActive) {
            // ===== C2-1 SODIUM ACTIVE (gate or lever) ==========================================
            // The compat mixin set (seamlessportals-ip-compat.mixins.json) is woven whenever
            // sodium is present flag-ON; this install is what makes the invoker-gated bodies
            // live. OnSodiumPresent classloads only here.
            SodiumInterface.invoker = new SodiumInterface.OnSodiumPresent();

            // One-shot HONEST experimental notice (GOLD — a notice, not a failure).
            final boolean irisInstalled = irisActive;
            IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
                String text = irisInstalled
                    ? ("[Seamless Portals] Sodium + Iris support is EXPERIMENTAL — with shaders "
                        + "OFF portal views are fully active (clipping enabled); with a "
                        + "shaderpack ON portal views are not yet supported and render as "
                        + "pass-through. Please report any terrain artifacts near portals.")
                    : ("[Seamless Portals] Sodium support is EXPERIMENTAL — portal clipping "
                        + "is newly enabled; please report any terrain artifacts near portals.");
                CHelper.printChat(Component.literal(text).withStyle(ChatFormatting.GOLD));
            }));
        }
        else if ((isSodiumPresent || isIrisPresent) && !irisActive) {
            // ===== HONEST-GATING (per-mod verdict fell through) ================================
            // D11-LANDED: the presence-gated chunk-tracker feed must install BEFORE
            // warnAndForcePortalRenderingOff or flag-ON's ImmPtlClientChunkMap leaves the main
            // world BLANK under sodium (see the original method's D11 ledger note). Gated on
            // isSodiumPresent (never iris alone): FeedOnlyOnSodiumPresent classloads sodium types.
            if (isSodiumPresent) {
                SodiumInterface.invoker = new SodiumInterface.FeedOnlyOnSodiumPresent();
                SeamlessPortalsConstants.LOGGER.info(
                    "Seamless Portals: sodium present but compat inactive — installed the D11 "
                        + "presence-gated chunk-tracker feed (main-world meshing only; portal "
                        + "views stay disabled)");
            }
            String subject = isIrisPresent
                ? (isSodiumPresent ? "Sodium + Iris (shaders)" : "Iris (shaders)")
                : "Sodium";
            warnAndForcePortalRenderingOff(subject);
        }
    }

    /**
     * The user-facing half of the S19-E honest-gating deviation: a loud init log, a one-shot
     * world-join chat message, and a session-only force of {@code IPGlobal.renderMode = none}.
     * {@code subject} names the incompatible renderer(s) detected. See {@link ExperimentalCompatGate}.
     */
    public static void warnAndForcePortalRenderingOff(String subject) {
        // (a) loud, multi-line log at init.
        SeamlessPortalsConstants.LOGGER.error(
            "\n============================================================\n"
                + "[Seamless Portals] {} detected, but the ported {}-compatible portal\n"
                + "rendering layer is NOT enabled yet (still being ported — C2).\n"
                + "Portal VIEWS are disabled for this session to avoid rendering errors.\n"
                + "(Teleportation and portal creation still work.)\n"
                + "This is temporary; a future update will restore portal rendering with {}.\n"
                + "============================================================",
            subject, subject, subject
        );

        // (c) force portal rendering off for THIS SESSION only — after config load, session
        // flag survives runtime config reload, never written to disk.
        ExperimentalCompatGate.forcePortalRenderingOffThisSession = true;
        IPGlobal.renderMode = IPGlobal.RenderMode.none;

        // (b) one-shot chat on world join (IP's Iris-warning idiom).
        final String subjectFinal = subject;
        IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
            CHelper.printChat(
                Component.literal(
                    "[Seamless Portals] " + subjectFinal + " detected: portal views are disabled "
                        + "this session (rendering not compatible with it yet). "
                        + "Teleportation still works."
                ).withStyle(ChatFormatting.RED)
            );
        }));
    }
}
