# NeoForge Parity — the C7 backlog discharged (2026-08-25/26)

The `:neoforge` module is no longer a KEEP-skeleton: it wires the SAME entity-portal (IP)
engine as `:fabric`, both dists, flag defaulting ON. This document is the ledger of what
changed, what is measured-verified, and what remains for live sessions.

Branch: `claude/nifty-kepler`. Commits: `c615e6eb` (Phase 0) → `e3ede802` (facade skeleton)
→ `566edab3` (facade rewrite W8-W12) → `a3f94ebb` (W13 chunk seams) → `81b58b1e` (module
rebuild W15-W21+W23) → `324a8da9` (dist splits + registry windows) → the E0/L1 fix tail.

## 1. Architecture: the hybrid facade (recon decision A3)

- **Kept as-is**: the 188 `@Environment(EnvType.CLIENT)` annotation refs (94 files). The
  fabricStubs annotation was corrected to the REAL Fabric shape (`@Retention(CLASS)`,
  measured by javap on fabric-loader-0.19.3) — NeoForge classes now carry
  RuntimeInvisibleAnnotations, byte-matching Fabric builds. Annotations of an absent type
  are invisible to the JVM (JVMS §4.7.16; zero reflective readers in-tree).
- **Facaded**: every functional `net.fabricmc.*` reference (~72 refs / ~43 files) onto
  - `com.warwa.seamlessportals.platform.Platform` (server-safe: paths/env/mod-list/
    versions/registries/game-bus lifecycle/server+attack events),
  - `com.warwa.seamlessportals.platform.ClientPlatform` (client tick/join/connection-reset/
    chunk-event posts/generic client commands),
  - `PlatformHelper` extensions (configuration-phase networking W12; chunk-sent seams W13),
  ServiceLoader-bound per loader like the existing PlatformHelper. Fabric impls are 1-line
  delegations to the exact calls the sites used before — Fabric behavior unchanged by
  construction. NeoForge impls verified by javap against FML 11.0.13 + neoforge262-ref.
- **Deleted dead**: IPMixinPlugin (zero registrations), O_O.getIsPehkuiPresent,
  DimensionIntId's addPhaseOrdering (no-op holder).
- The four `Event`/`EventFactory` importers switched to the mod-owned drop-ins.
- `ClientDebugCommand` genericized to `<S>` + a `ClientCommandSupport` feedback adapter
  (the only Fabric-source member ever used was `sendFeedback`).

## 2. The NeoForge module (rebuilt end-to-end)

- **`SeamlessPortalsModNeoForge`**: config at ctor (`FMLPaths.CONFIGDIR`); data-pack-registry
  hoist at ctor (NewRegistry fires BEFORE the unfreeze window); the FULL flag-ON IP init
  chain at the FIRST `RegisterEvent` dispatch (ATTRIBUTE — inside the unfrozen window,
  because `EntityType.Builder.build()` creates intrusive holders); per-registry drains
  (entity types, blocks, items, components, generators, biome sources, TICKET_TYPE, tabs);
  flag-OFF block-era branch mirrored (trackers + D3 type mirror).
- **`SeamlessPortalsClientNeoForge`** (`@Mod(dist=CLIENT)`): renderer registration inside
  `RegisterRenderers` (fires before FMLClientSetupEvent, NF Minecraft.java:702 vs :719);
  IP client chain at `FMLClientSetupEvent.enqueueWork` in the exact Fabric order; flag-ON
  driver on `RenderLevelStageEvent.AfterTranslucentBlocks` (the exact-instruction match of
  AFTER_TRANSLUCENT_TERRAIN) with the load-bearing re-entrancy guard; `IConfigScreenFactory`
  = the ModMenu route (cloth flag-ON / SeamlessConfigScreen flag-OFF, both now shared code).
- **W21**: `MixinLevelRenderer_ClipBracketMainPassNeoForge` (NEOFORGE_ONLY) fills the
  executeOutline→renderGroup(TRANSLUCENT) gap NeoForge has no event for; Fabric keeps its
  event (per-user decision: common mixin, NeoForge-only for now). Lambda index
  javap-verified (`lambda$addMainPass$0`).
- **Loader-shape mixin variants** (`NEOFORGE_ONLY_MIXINS`/`NON_NEOFORGE_MIXINS` in
  SeamlessMixinConfigPlugin, FMLLoader class-probe): the `extractVisibleBlockEntities`
  lever (3-arg vanilla vs 4-arg NF) and the `startWaitingForNewLevel` loading-screen skip
  (3-arg vs the 5-arg NF overload handleRespawn actually calls) — each variant holds
  `require=1` strictness on its own loader. State shared via the
  `SeamlessRespawnTransitionAccess` duck (which must live OUTSIDE the mixin package —
  measured IllegalClassLoadError otherwise).
- **W16**: `seamlessportals-ip-fabric.mixins.json` → `-ip-platform` (a misnomer — both
  mixins target vanilla), registered in neoforge.mods.toml; the B4 `teleportTo` double-fire
  deleted (subsumed via `Entity.teleport` delegation — was latent on Fabric too).
- **W15 deps**: `cloth-config-neoforge:26.2.155` implementation+jarJar (mod id
  `cloth_config`, autoconfig classes in-jar — the recorded C7 NCDFE landmine discharged).
  Sodium/Iris compat ships ON: all 23 compat-mixin target FQNs verified present in the
  first-party `-neoforge` builds (sodium's inside its nested jarjar mod jar; mod ids stay
  `sodium`/`iris`); compile classpath keeps the `-fabric` classifiers (the `-neoforge`
  sodium maven artifact is a bootstrap wrapper with no classes).
- **W18**: `dontinline` C2-JIT protection on both runs (measured on the live command line);
  launcher vendor re-pinned per-run-task (moddev DROPS the toolchain vendor — measured:
  the live process runs `C:\Program Files\Zulu\zulu-25`); `validateAccessTransformers=true`.
  Verification levers: `-PquickPlayWorld=<save>` / `-PquickPlayServer=<host:port>`.
- **W23**: EntityPortalsFlag's force-false-off-Fabric gate retired; reflective
  `FMLPaths.CONFIGDIR` arm added (safe at mixin-bootstrap — verified by FMLLoader offsets).

## 3. The dist doctrine (measured, in memory as neoforge-dist-verifier-rules)

NeoForge has NO @Environment stripping. A class fails to LINK on a dedicated server iff
any method BODY needs an assignability proof involving a client-only class. Splits landed
(logic verbatim): O_O.createMyClientChunkManager→inlined at MixinClientLevel;
RequiemCompat→RequiemCompatClient; GlobalPortalStorage→GlobalPortalStorageClient;
ImmPtlNetworking handle() bodies→ImmPtlNetworkingClient; CollisionHelper→
CollisionHelperClient; PortalWandItem.onClientLeftClick param LocalPlayer→Player;
MixinClientboundCustomPayloadPacket body→PacketRedirectionClient.handleAtPacketHandle.
Also: block-era ticket types moved out of <clinit> into bootstrapTicketType(s) (the
June-2026 ExceptionInInitializerError-at-constructMods mystery, root-caused).

## 4. W20 (stencil) — the recon's "structurally dead" verdict REFUTED at source level

NF's per-draw `_disableStencilTest()` clobber is CACHE-GUARDED (`BooleanState.setEnabled`:
`if (enabled != this.enabled)`), and the mod's raw-GL stencil never touches that cache —
the two systems are mutually blind, which is exactly the property the mod needs. The
substrate survives: RenderTargetMixin's `createFbo` RETURN-inject runs AFTER NF's explicit
stencil-detach and re-attaches DEPTH_STENCIL (with completeness check); GlConstMixin /
GlBackendMixin targets unchanged. Measured live: "[SEAMLESS STENCIL] Requested 8 stencil
bits" + "Changed depth format: DEPTH32F -> DEPTH24_STENCIL8" in the NF client boot.
RESIDUAL (documented): a third-party mod using NF's pipeline StencilTest mid-portal-frame
would desync the cache once; none exists in the target runtime today.

## 5. Verified matrix (all MEASURED this engagement)

| Leg | Result |
|---|---|
| `:common`/`:fabric`/`:neoforge` compile + full builds | GREEN after every commit |
| NF jar contents | 0 `net/fabricmc` entries; cloth jarJar'd; platform mixins + AT shipped |
| javap audits (deobf NF jar) | B1 4-arg call, B2 5-arg call, clip-bracket lambda index, `_MA` teleport target, `rebuildSync` LVT — all confirmed |
| **E0: NF dedicated server, flag ON** | **Done (1.655s)**; full IP chain in-window; 145 mixins woven; 0 post-boot errors |
| **L1: NF client, flag ON** | Title screen; 0 injection errors across ~250 woven mixins; stencil substrate live; RenderCompatGating ran; frame hook ticking (~60fps) |
| Server engine liveness | forms registered: 8 (datapack registries), perf monitor, command registration, entity-create path (IP's NBT reader ran on a summoned portal) |
| Latent-defect fixes proven en route | LoadingModList detection (mixin-plugin Sodium probe was a silent NPE no-op on NF); the 14 missing block-era payload types (the June portal_link crash); B4 double-fire; the stale sendChunk @IPVanillaCopy (debug-synchronizer tracking) |
| Fabric regression client boot (flag ON) | GREEN — full init both sides, title screen, 0 injection errors (the facade rewrite did not regress Fabric) |

## 6. Known-opens (live-session verdicts — the user is the verdict)

1. **L6 visual**: portal renders through the stencil mask on a NF client; recursion ≥2.
   (Engine + substrate proven live; the visual composite needs eyes.)
2. **L3 full handshake**: NF client ↔ NF dedicated server join. QuickPlay (both variants)
   stalls behind dev-env UI screens the headless harness cannot click — the levers are in
   place (`-PquickPlayServer=127.0.0.1:25565`); one manual Join settles it, watching for
   `serverVersion` non-null + `ImmPtlConfigurationTask` completion.
3. Datapack `summon immersive_portals:portal` with inline NBT reaches the reader as an
   EMPTY tag on 26.2 (the reader runs; the tag doesn't arrive — likely the ValueInput
   summon-path change). Portal creation via the wand/commands is unaffected; only the
   headless probe path. runs/server has the `nfsmoke` datapack for reproduction.
4. C5.2 (NF payload `versioned()` strictness vs IP's patch-tolerant handshake): config
   payloads are registered `.optional()` + unversioned, so IP's own ModVersion handshake
   stays the sole arbiter — E5 answered structurally; a mismatched-patch pair test would
   confirm live.
5. The wand's `LeftClickBlock` creative-mode caveat (L5) — one creative swing.
6. Flag-OFF NeoForge = tracker baseline only (block-era client/server payload HANDLER sets
   remain Fabric-only statics) — recorded deviation; the block-era dies at S20.

## 7. Env facts for future sessions

- FML resolves as fancymodloader loader-11.0.13; SPI classes live INSIDE the loader jar.
- `DataPackRegistryEvent.NewRegistry` fires BEFORE the RegisterEvent unfreeze window
  (CommonModLoader.begin:52-55); ATTRIBUTE dispatches first (GameData hoist).
- moddev drops the toolchain VENDOR (ModDevRunWorkflow copies languageVersion only) — the
  run-task launcher re-pin in neoforge/build.gradle is what makes Zulu actually launch.
- mc262-ref carries NeoForm's cosmetic @OnlyIn but VANILLA behavior; neoforge262-ref is
  the patched tree. `minecraft-patched-26.2.0.1-beta.jar` under neoforge/build/moddev is
  the javap target for weave audits.
