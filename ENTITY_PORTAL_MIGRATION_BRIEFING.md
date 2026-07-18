# Entity-Portal Migration Briefing
## The handoff document for the COMPLETE IP-FIDELITY port. Read this + the memory index before any migration work.

**Mission (user directive, 2026-07-08):** convert the mod from block-based portals to Immersive Portals' entity-based portals — **fully, exactly as IP, with NO deviation. Complete IP fidelity.** No simplifications, no stubs, no "our version." Where the mod currently deviates, the deviation ends here.

---

## 1. Origin context — why the mod is block-based today

Block portals were a **deliberate, documented** scoping decision, not an accident: `IP_DEVIATIONS_ANALYSIS.md` (2026-04-14) lists deviation **#1 "Portal lifecycle (blocks vs entities)" — Low priority, "Future"** (and #12 "Teleportation (PortalLink vs entity)" — Low/Future). The project was rendering-first: nail IP's seamless stencil render on vanilla nether portals, defer IP's portal-object subsystem. That "Future" is now. The block choice is also the root of every remaining ceiling: no recursion, no custom shapes/orientations/scale, redstone/rails infeasible, and the entire cached-secondary mirror render layer with its streaming/lighting complexity.

## 2. References

- **IP source (the ground truth to port):** `C:\Users\warwa\ModDev\ImmersivePortalsMod` (upstream stops at 1.21.3 — this mod's own 26.2 code is the base for API translation; `MIGRATION_API_MAP.md` maps 1.21.x→26.2 APIs).
- **Decompiled MC 26.2:** `C:\Users\warwa\ModDev\mc262-ref`
- **Memory (auto-loaded each session):** `C:\Users\warwa\.claude\projects\C--Users-warwa-ModDev-Portals-Portal-26-2\memory\` — every root cause + rule from the block era. Read `MEMORY.md` first.
- **Repo spec artifacts:** `IP_DEVIATIONS_ANALYSIS.md`, `MIGRATION_API_MAP.md`, `PHASE5_STENCIL_DIRECT_SPEC.md`, `PHASE_B_PORT_SPEC.md`, `RENDER_PIPELINE.md`, `IP_CONTEXT_SWITCH_EXACT.md`.
- **Build:** `Set-Location "C:\Users\warwa\ModDev\Portals\Portal 26.2"; .\gradlew.bat :common:compileJava :fabric:compileJava --console=plain --no-daemon`. Logs: `fabric\runs\client\logs\latest.log` + `debug.log` (debug.log shows ONLY Fabric payload channels, not vanilla packets). Never `gradlew --stop`; kill only PIDs whose command line contains this project's path.

## 3. What the migration replaces (the block-era architecture, for orientation)

- **Portal objects:** `PortalInfo` (origin/axis/width/height) + `PortalLink` + `PortalManager`/`PortalTracker` + `PortalDetector`/`PortalShapeForm` block scanning → replaced by IP's `Portal` entity (qouteall.imm_ptl.core.portal.Portal) + its creation/placement (incl. `NetherPortalGeneration` for vanilla-frame portals), sync-as-entity, arbitrary shape/orientation/scale.
- **Crossing:** client-first `LocalPlayerMixin` plane-segment + `ClientPortalCrossingPayload` + `SeamlessClientTeleport`/`SeamlessServerTeleport` + `EntityMixin` server detection + `PortalTeleporter` + `ProjectilePortalHandler` (two paths!) → replaced by IP's `ClientTeleportationManager` + `ServerTeleportationManager` (one unified entity path; players client-first with a REAL server fallback).
- **Render:** mirror/stencil-direct `PortalContextSwitch` + `StencilPortalRenderer` + cached secondary ClientLevels (`PortalWorldManager` promote/demote, the whole promote-bridge/SOG/extractor machinery) → replaced by IP's recursive stencil `PortalRenderer` family + `ClientWorldLoader` (real per-dim ClientLevels, no promote/demote), `MyGameRenderer.switchAndRenderTheWorld`, `CrossPortalEntityRenderer`, `FrontClipping` (already ported), `MyRenderHelper.lateUpdateLight` (already ported, commit 3a2c14e).
- **Streaming:** `PortalChunkTracker` (redirected chunks + ACK ledger + resend suppression + residency tickets) + `RedirectedPacketApplier` + `PortalEntityTracker` (remote_entity_* mirror) + `RemoteBlockUpdater` → replaced by IP's `ImmPtlChunkTracking`/`ChunkVisibility` (per-player cross-dim chunk tracking through vanilla packets), IP's graduated dest loading (see memory `ip-dest-loading-model`), IP's MixinChunkHolder light forwarding.

## 4. REGRESSION CHECKLIST — block-era behaviors the new architecture MUST preserve

Every one of these was a user-visible bug fixed this cycle; each has a memory file with the mechanism. Test all after each migration stage:

1. Crossing is smooth both directions, backward/strafe crossings exit on the motion side (motion-signed exit; `backward-crossing-motion-keyed-exit`). No oscillation/ping-pong (plane-segment detection + short dedup, NOT containment + long cooldown).
2. No FOV pulse / sprint loss / hand glitch / velocity zero at crossing (`player-reuse-self-copy-traps`, `teleport-hand-glitch-chain`).
3. Thrown items/mobs land on the player's emergence side, reachable, visible through the portal immediately (no 15s invisibility), don't drift into frame lava (`entity-vanish-cooldown-mirror-gate`).
4. Arrows fly through continuously, full speed, no 8× velocity scaling, findable on the far side.
5. Shot animals keep panicking after crossing (transient hurt state; note the `Brain.getMemory` unregistered-slot crash guard — `hasMemoryValue` first).
6. No phantom boost rocket when elytra-crossing with fireworks (attached fireworks must not cross — guard needed in EVERY crossing path; see the two-path rule below).
7. Entities straddling the portal render whole in the view (entity clip margin / CrossPortalEntityRenderer supersedes it properly).
8. Nether portal-view lighting correct BEFORE first crossing (light engine runLightUpdates at render-end — IP lateUpdateLight; `portalview-light-engine-half-port`).
9. No chunk holes / limbo bands / distant-chunk vanish in either dim after crossings; block break/place always remeshes (these were mirror-era bugs — IP's architecture removes the mechanisms, but VERIFY the symptoms stay gone).
10. Large/tall portals validate crossings (box-based proximity); negative-coordinate portals link exactly (floored scaling) — commit 80dc82d.
11. No GPU-buffer leak (endFrame for every mod-created RenderBuffers), no per-frame LOGGER on the render thread (log4j ~130ms stalls — measure off-thread).
12. Relog/kick/rejoin gets a clean session (lifecycle cleanup on BOTH ClientLevel.disconnect AND updateLevelInEngines(null) — c99607f).

## 5. Backlog that lands ON the new architecture (build after the core port)

- **Crossing completeness (task #11):** server-side player fallback detector (the dispatch exists, trigger missing — a client-missed crossing is currently a SILENT no-teleport); mounted/vehicle crossing (IP `teleportVehicleAcrossDimensions`, ServerTeleportationManager:431-455 + the :115 vehicle skip); unify projectile crossing to ONE path.
- **Redstone/rails/minecarts through portals** (`redstone-rail-minecart-deferred`): IP does NOT implement these (empty marker interface) — novel work, much easier on entity portals (opening cells aren't blocks). Design notes + geometry pinned in that memory.
- **Portal recursion:** natural on IP's recursive stencil renderer.
- **Two-sided entity render:** port `CrossPortalEntityRenderer` (dual outer+inner clip; `setupOuterClipping` exists but is dead today). Covers: approach-side sink-in, animal threshold clip both directions, missing damage flash in portal view, cross-portal punch/reach.
- **First-visit worldgen stall:** replace the radius-32 residency hold with IP's graduated loading (`ip-dest-loading-model`; the 29s/12.8s stalls).
- **Light-only server forward:** IP MixinChunkHolder (propagation-only light events don't carry today).
- **Renderer identity on cleanup** (mod renderer left as mc.levelRenderer after disconnect-in-promoted-dim) — likely moot once promote/demote dies, verify.
- **Mirror population growth ~3×** — likely moot (mirror dies), but verify the SERVER entity population near portals doesn't genuinely grow.
- **Underwater portal-window fog composite (planned deviation — user-approved 2026-07-17, S14
  rung-2 step 8):** with the camera submerged, the portal window shows the dest world with dest
  fog only — no source-dim water fog over the camera→window span, so the window "shines clean
  through" the surrounding water fog. **User-verified side-by-side: original IP 1.21.3 behaves
  identically** (dest pass picks fog from the transformed camera's dest-side position; IP's
  FogRendererContext is a per-dim color swapper with zero fluid awareness — no fidelity gap).
  The improvement is OURS to design: composite source-dim fog over the aperture region by
  per-pixel distance to the portal plane (screen-space pass over the stencil-masked area).
  Applies to any source-side fog medium (water, lava, powder snow, thick nether fog looking OUT).
- **Source-dim full-RD keep-loaded toggle (planned deviation — user-requested 2026-07-18, S14
  churn classification):** when the player crosses, the departed dim keeps only the portal
  loaders' bounded radius; the player's far ring (out to the full 32-chunk RD) loses its tickets
  and unloads after the vanilla grace (~seconds), re-streaming + re-meshing on return.
  **User-verified side-by-side: original IP 1.21.1 behaves identically** — IP-authentic, no
  fidelity gap; classified CLOSED as-shipped. The improvement (block-portal-era parity feature):
  an IN-GAME CONFIG TOGGLE that plants a keep-alive ticket set at the player's departure
  position covering the full render distance across crossings, so round-trips never drop the
  far ring. Cost when ON: both dims' full RD stays server-resident (memory/tick cost — the
  block-era mod paid it). Default OFF (IP behavior). Design note: tie ticket lifetime to
  "until the player next crosses away from a DIFFERENT position" or a generous timer, not
  permanence, to avoid unbounded multi-dim residency.

## 6. Porting discipline (lessons that cost real debugging time — do not relearn them)

- **Two-path rule:** any per-entity-type crossing behavior must exist in EVERY crossing path (the phantom-rocket fix shipped broken once because only one of two paths was guarded). The migration should end at ONE path.
- **IP splits work across tick + render-end** (pollLightUpdates vs runLightUpdates). When porting a per-frame IP routine, find BOTH halves.
- **Every packet-handler mixin needs an isSameThread guard** (netty pre-pass runs HEAD injects twice; RETURN cleanup skipped by the re-queue throw).
- **Tracker/extractor identity is everything** — writers and readers must share the object, and `allChanged()` REPLACES trackers (the block-freeze bug family). Applies anywhere renderer state is adopted/swapped.
- **Any "client holds X" claim must be verified AND survive retention** (5 ledger lies + the honest-ledger-fossilizes-render-loss rule). IP's vanilla-packet chunk tracking should retire this class, but the doctrine applies to anything custom that remains.
- **`Brain.getMemory` on an arbitrary mob throws on unregistered slots** — `hasMemoryValue` first.
- **Never trust a prior geometry-sign claim** — re-derive clip/transform signs from source (two sign errors caught by adversarial verification this cycle).
- **debug.log ≠ vanilla packets; `[SEAMLESS SERVER-CROSSING] ... in X` names the DESTINATION dim; world-save (stats + entities .mca) is ground truth for entity-fate questions.**
- Strict fidelity applies to VANILLA too: don't "fix" faithful vanilla behavior (firework billboard rotation).
- Process: read logs deeply first; ultracode workflows for every substantive investigation with ADVERSARIAL verification (it caught ~a dozen real errors this cycle, including in its own syntheses); one tested increment at a time; commit messages via `git commit -F <file>` (no here-string quoting traps); memory updated every root cause.

## 7. Suggested opening move for the migration session

Do NOT start writing code. Start with an ultracode research/planning phase:
1. Full inventory of IP's portal subsystem (core packages: portal/, teleportation/, render/, chunk_loading/, ClientWorldLoader, network/) — what each class does, in dependency order.
2. Map each IP class to its 26.2 API surface (extend `MIGRATION_API_MAP.md`; IP is 1.21.3 — the renderer rewrite gap is the big one, but Phase 5 stencil-direct work already proved raw-GL stencil + renderGroup→main-target viability on 26.2).
3. Produce the staged execution plan (aim: mod runnable between stages if possible; decide honestly whether render cutover must be atomic).
4. Only then execute stage by stage, regression checklist after each.
