# S14 HANDOFF — cross-dimension rung 2 (written at S13 sign-off, 2026-07-17)

This is the session-handoff document for **S14**, written immediately after **S13 was signed off by the
user** ("no wobble, rung 1 is done"). Read this in full, then the plan's S14 section, before doing anything.

## 1. Where the migration stands

- **S0–S13 COMPLETE.** The entire IP source tree (all 492 files) is ported, flag-gated (`entityPortals`,
  Fabric-only), flipped (`ip_scc_closed=true` committed), and **live-tested through the full rung-1
  checklist**. Tree = `f6f444a` on `claude/nifty-kepler`, pushed to github.com/fmpapierz/SeamlessPortals.
- **Rung-1 final census:** every gameplay system measured IP-correct (crossings, parallax incl. the 2×
  scaled rate, scale/giant physics, rotation, block manipulation through the window, one-sided/one-way
  semantics, R11 persistence verified at save-file level, the non-fuse scaled-portal white bar =
  authentic IP, user-verified against the original mod). All real defects were in the new 26.2 render
  re-expression, all fixed + Fable-verified: the clouds fence crash (dest-clouds skip, deviation-until-S18),
  the fog-color stub (dest atmosphere fill), the view-bob wobble (bobbed projection at the dest draw AND
  every aperture site + scale-compensated bob translation).
- **The S13 bring-up saga** (8 launch attempts, 11 defect classes, every fix) is chronicled in
  `port-notes/S13C-weave-audit.md` (the combined bring-up note) + `S13H-driver-core-design.md` +
  `S13M-view-bob-wobble.md`. The governing docs: `EXECUTION_PLAN.md` (amended), `CUTOVER_SPEC.md`,
  `EXCLUSIVITY_LEDGER.md` (incl. the weave-exclusivity suppression set).

## 2. The S14 mission

**Cross-dimension portals — rung 2** (EXECUTION_PLAN S14 section): the first REAL nether-view portal.
First live contact for: R1 (secondary ClientLevel construction + the seaLevel protocol), R7 (packet
redirection ordering under real cross-dim traffic), R9 (per-dim fog/lightmap), IP chunk loading's first
genuinely-loading cross-dim tickets, and the S13-H driver core's cross-dim path (its extractor-identity
bug was caught and fixed PRE-EMPTIVELY by the Fable verifier — commit e817b57 — but has never run live).

**Suggested opening move (the proven S13-G pattern): a READINESS AUDIT before the user's first live run.**
Statically trace the whole cross-dim chain end-to-end and fix inert/mis-wired links first:
dim-sync (DimIdSyncPacket + dimSeaLevelTag) → `ClientWorldLoader.createSecondaryClientWorld` (R1 seaLevel
consumption per SPIKE-R1 §3) → per-dim renderer/extractor identity (memory: nether-block-freeze-orphaned-
extractor — tracker identity is everything) → per-dim fog/lightmap (R9 FORM = the standalone-fog-buffer
fallback; fog-flicker watch) → `ImmPtlChunkTracking`/`ChunkVisibility` cross-dim tickets (first REAL
loading) → `SecondaryWorldRenderCore` cross-dim (sharedState=false path — never run live) → entity sync
across dims (PacketRedirection). Then fix → Fable verify → commit/push → READY → the user's first nether
portal (`/portal make_portal 3 3 minecraft:the_nether shift 20`-style, dedicated test world).

**S14 watch items (from the plan + the ledger):**
- **C8 RECHECK (required):** the pre-existing block-era flake — portal's own chunk not loading until
  relog — must be rechecked under IP loading; if it survives, it's a real bug to fix now.
- **R10 FLAG_SIMULATION:** IP semantics re-enable natural mob spawns in dest chunks (block-era used
  LOADING-only tickets to avoid piglin floods) — regression watch at the first nether portal.
- **MixinFabricInvalidateRenderStateCallback** — S13-B deferral, verify at S14 (redundant render-state
  invalidation during secondary-world creation = perf hiccup risk).
- The deterministic `ImmPtlChunkTickets` "Chunk loading failure" log line is KNOWN-BENIGN (IP ships it).

## 3. The standing deferred ledger (do NOT re-diagnose these)

- **U2 GL "framebuffer bind before generate" flood** — root-caused, 3 fix options, needs live verification
  (chip task_70fec4eb; suspects incl. the static `StencilState.gameFboId` capture vs dynamic discovery).
- **Dest clouds** omitted (S18 lands proper per-dim isolation); **weather + world border** omitted (S18).
- **Fuse-view scaled-portal live A/B** — S13-L's covector clip fix applies ONLY to fuse-view portals and
  has never been exercised live (coverage gap; `create_scaled_box_view` is the test vehicle).
- **reversePortalId left undefined** after global conversion (bookkeeping glance, low priority).
- **Convert-to-global chat feedback** — USER-APPROVED additive enhancement, land at S19/S20 polish.
- **Sodium (C2) + NeoForge (C7)** — user decision: ONLY after Fabric + all IP features are fully done.
  Sodium 0.9.1+mc26.2 exists (plan's "no 26.2 Sodium" rationale is stale). NeoForge flag hard-forced OFF.

## 4. Hard-won process rules (violate none of these)

1. **The user launches the game ONLY on an explicit READY** (all workflows idle). Agent gradle builds
   rewrite `build/classes` while a running JVM lazy-loads from it — this manufactured a phantom
   NoClassDefFoundError crash once (attempt 6). Gate every user run.
2. **Any new/changed mixin needs the static weave audit** (every @Mixin/@Shadow/@Inject/@At/@Accessor
   anchor vs the loom NAMED dev jar + mc262-ref). javac proves nothing about weave validity. @Shadow needs
   the member DECLARED IN the target class.
3. **Runtime bring-up = crash-per-layer.** Each live failure is one precise defect class; sweep the WHOLE
   class before the next launch (weave anchors → self-identity → init landmines → weave collisions →
   duck implementors/registrations → dispatch wiring → transform feeds → depth/fill).
4. **Ground truth beats theory:** save-file NBT reads, the user's live evidence, and — decisively — the
   user can RUN THE ORIGINAL IP MOD (1.21.3) side-by-side to settle any "bug or IP behavior?" dispute in
   one test (this closed the white bar). Ask for it before burning diagnosis cycles on ambiguous findings.
5. **Confirm all visual interpretations with the user** before acting on them (their standing rule); they
   frame test portals in black wool for contrast.
6. **Push after every commit** (`git push origin claude/nifty-kepler`). Never force-push without first
   parking the remote tip on a backup ref.
7. **Ultracode workflows** for every substantive investigation: Fable for design + adversarial verify,
   `model:'opus'` EXPLICIT on every other agent AND on the Opus-fallback branches (session model may be
   Fable → un-tagged agents inherit it — the documented inheritance trap).
8. **Workflow resume mechanics:** the journal caches completed agents; edit scripts only between journaled
   boundaries; a "started" with no "result" = the agent died (its disk edits = UNVERIFIED DRAFTS — tell the
   re-run agent so); re-arm progress monitors on the NEW task handle after every resume (the output file is
   an empty placeholder at launch — completion = non-empty, `-s` not `-f`).
9. **Commit hygiene:** grouped stage commits + port-note per stage; NEVER unscoped `git add -A` (it once
   swept 3,705 build artifacts; `.gitignore` now exists but stay scoped); the commit gate = 3-loader
   compile + `:common:test` (agent-run gates on an unchanged tree count — don't rebuild under a running game).
10. **Fable verify has caught a MAJOR on almost every pass** (identity matrix, extractor ordering, pipeline
    clobber, aperture bob...). Never skip it to save time; the user explicitly refused quality compromises.

## 5. Config quick reference (dev client)

- Flag: `fabric/runs/client/config/seamlessportals.properties` → `entityPortals=true` (load-time; full
  restart to change). Fabric only.
- IP config: `fabric/runs/client/config/immersive_portals.json` (`enableClippingMechanism`,
  `crossPortalEntityClipMechanism` = the C4 A/B switch for S18, etc.).
- Logs: `fabric/runs/client/logs/latest.log` + `debug.log`; crashes in `crash-reports/`; saves in `saves/`.
- Test script pattern: `migration/S13-FIRST-LIGHT-TEST.md` (write an S14 equivalent; pre-answer expected
  IP behaviors so they don't get re-filed as bugs; the visible-destination-marker lesson).

## 6. User checkpoint decisions on file (memory `c3-c4-checkpoint-decisions`)

C3 rebuild approved · C4 R3 proceed + BINDING A/B rider (surface the clip-mechanism switch whenever
entities-through-portals get live-tested: S15/S17/S18) · C5 view-bob IP-verbatim (landed) · C2/C7
post-everything · polish backlog as §3 above.
