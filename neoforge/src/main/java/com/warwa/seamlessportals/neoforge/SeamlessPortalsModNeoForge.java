package com.warwa.seamlessportals.neoforge;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.neoforge.network.NeoForgePlatformHelper;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * NeoForge entrypoint.
 *
 * <p><b>S20 (2026-07-26) — READ THIS BEFORE ASSUMING THIS MODULE DOES ANYTHING.</b> S20 deleted the
 * block-era portal system, and on NeoForge <b>that system WAS the entire portal implementation</b>.
 * The {@code entityPortals} flag has never been {@code true} here: {@code EntityPortalsFlag} forced
 * it false whenever FabricLoader was absent (two sites, its {@code readFromDisk} and
 * {@code seedIfUnset}), so NeoForge only ever ran the block-era driver. The ported Immersive-Portals
 * engine cannot take over yet — {@code IPModMain.init} is never called from this class, IP's
 * {@code IPGlobal} static initialiser hard-fails here with {@code NoClassDefFoundError} on Fabric's
 * {@code EventFactory}, and ~31 {@code net.fabricmc.*} types the ported tree compiles against exist
 * on this platform only as {@code compileOnly} shells (see {@code common/build.gradle}'s
 * {@code fabricStubs}).
 *
 * <p>So this module compiles and boots but has <b>NO portal behaviour</b>, by decision, until
 * checkpoint <b>C7</b> (NeoForge parity — the ask-first post-S20 stage; the module is a
 * "KEEP-skeleton" until then). That was a USER DECISION on 2026-07-25: accept the gap and ledger it
 * loudly. Full reasoning and the evidence chain: port-note
 * {@code migration/port-notes/S20-block-era-deletion.md} §G.1.
 *
 * <p><b>Why the notice below is not optional.</b> The S20 adversarial audit proved this failure mode
 * is otherwise SILENT and undetectable by a smoke test: weave and boot both succeed (the only
 * {@code net.fabricmc} references inside registered mixin classes are {@code @Environment}
 * annotations, which Mixin reads via ASM and the JVM ignores), and the symptoms are all first-use —
 * most dangerously a world that sends ZERO chunks with no exception and no log at all. A green
 * NeoForge boot therefore proves nothing. The loud init line is what converts an invisible
 * no-portals state into an obvious one for anyone who installs this on NeoForge.
 */
@Mod(SeamlessPortalsConstants.MOD_ID)
public class SeamlessPortalsModNeoForge {

    public SeamlessPortalsModNeoForge(IEventBus modEventBus) {
        SeamlessPortalsConstants.LOGGER.info("Seamless Portals initializing (NeoForge)");

        // S20 REQUIRED NOTICE (user decision, see the class javadoc). Deliberately at ERROR level and
        // multi-line: this state is a functional absence the user must not have to discover by
        // finding that portals do nothing.
        SeamlessPortalsConstants.LOGGER.error(
            "\n============================================================\n"
                + "[Seamless Portals] NeoForge: portal functionality is NOT AVAILABLE on this\n"
                + "loader. The block-era portal implementation was removed at S20, and the ported\n"
                + "Immersive-Portals engine is Fabric-only until NeoForge parity lands (C7).\n"
                + "Nothing in this build creates, renders, or teleports through portals here.\n"
                + "Use the Fabric build for portals. This mod is otherwise inert on NeoForge.\n"
                + "============================================================"
        );

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(NeoForgePlatformHelper::onRegisterPayloadHandlers);

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        // S20: the per-tick listener is gone with its two block-era bodies — the flag-OFF-gated
        // block-era chunk scan (PortalChunkTracker.tick) and the live-block-mirror flush
        // (BlockUpdateMirrorBuffer.flush, whose only filler was the deleted
        // LevelChunkSetBlockStateMixin). Nothing remains for it to drive, so registering an empty
        // per-tick callback would be pure overhead.
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // S20: ModPayloads.registerCommon() is gone — the bespoke block-era payload set died with
        // the block era. IP's own networking registers through the generic
        // registerClientboundPayload/registerServerboundPayload seam on PlatformHelper, which
        // NeoForgePlatformHelper still services via its PENDING_* queues.
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // NOTE (pre-existing, not a 26.2 change and not an S20 change): the NeoForge client render
        // path was never wired. This mod's client integration is maintained on Fabric (see
        // SeamlessPortalsClientFabric). Wiring the NeoForge equivalents (RenderLevelStageEvent +
        // ClientTickEvent) is C7 work, intentionally out of scope.
    }

    private void onServerStarting(ServerStartingEvent event) {
        // S20: PortalManager.getServerInstance() is gone with the block-era registry. There is no
        // NeoForge-side portal engine to warm up until C7.
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // S20: PortalManager.resetServer() + chunkTracker.clear() are gone with the block era.
    }
}
