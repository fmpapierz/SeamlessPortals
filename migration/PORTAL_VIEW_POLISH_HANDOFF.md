# PORTAL-VIEW POLISH / REGRESSION HANDOFF (2026-07-23, post-wave-closure)

The shaders-ON **lighting wave is CLOSED** (commit `00d15cf`, pushed `origin iris-on/is5-shadow`, 8-leg suite
ALL LEGS PASS — full story in that commit message + memory `portal-shaders-lighting-wave`). This handoff is the
NEXT engagement: the **portal-view fidelity regressions/polish items** the user reported while confirming the
wave fixes. Read `§0 STANDING RULES` first, then `§2 THE QUEUE`.

---

## §0 STANDING RULES (do not skip — every one of these was re-earned this arc)

- **NO GUESSING / DIAGNOSE-FIRST** (user, emphatic, repeatedly vindicated): instrument and confirm BEFORE
  designing any fix. This arc shipped a bytecode-confirmed + panel-SOUND fix that did nothing (the counter
  bump), and its replacement's first build silently didn't fire. A confirmed mechanism is NOT permission to
  ship — a LIVE-CONFIRMED mechanism is.
- **ADD LOGS FOR EVERYTHING — including the FAILURE paths.** Every fix and probe gets: a once-only LIVENESS
  line on first fire, a confirm-COUNTER read+reset by a 1Hz probe line, and once-only WARN lines on EVERY
  silent-skip/throw path (the heal's `instanceof`-miss was dark and cost a full run). 1Hz max on the render
  thread (the ~130ms log4j-stall rule).
- **READ THE FULL latest.log every run** (`fabric/runs/client-sodium/logs/latest.log`) — census the GL errors
  (`grep -c GL_INVALID_OPERATION`; baseline = ~4-8 known "Invalid format" residual lines, ANY new class or
  count explosion = investigate first), find your liveness/counter lines, compare against the prior run. A
  51MB log IS the finding.
- **MODEL TIERS (user rule, standing):** `model:'fable'` for THEORY / DESIGN / ADJUDICATION / ADVERSARIAL
  VERIFY (the hard thinking); `model:'opus'` for MECHANICAL extraction (javap/bytecode recon, source-fact
  extraction, table-building, log tabulation). Multi-agent panels for non-trivial mechanisms: independent
  analysts (2-3, distinct lenses) → adjudicate → 2-3 adversarial verifiers → majority-bound fold. Depth over
  speed.
- **Worktree:** `C:/Users/warwa/ModDev/Portals/Portal 26.2/.claude/worktrees/is5-shadow`, branch
  `iris-on/is5-shadow`, HEAD `00d15cf`. Push stage commits to origin. Commit isolation: `git add` explicit
  file lists only.
- **Live testing:** the user runs `.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true` from the worktree
  (iris 1.11.2+26.2 + sodium 0.9.1 + Complementary Reimagined r5.8.1). Reserve the user for EXPERIENTIAL
  verdicts; pre-screen everything log-classifiable yourself. The user films willingly — frame EXACTLY what to
  look at. TRUST THE USER'S LIVE OBSERVATIONS over any screenshot/bytecode reading (they corrected the record
  at least four times this arc: the same-wave identification, "colored blocks"→"actually shadows", the
  source-side content ID, the toggle verdicts).
- **Lever discipline:** every fix DEFAULT-ON with `-Dseamlessportals.disableX`; every probe DEFAULT-OFF with
  `-Dseamlessportals.xProbe`; every lever gets a `-P` passthrough in BOTH `fabric/build.gradle` blocks
  (`clientSodium` ~L146-210 and `crossingGametest` ~L250-300 — follow the existing rows).
- **Suite gate:** `.\gradlew.bat :fabric:runCrossingGametest` (8-leg, NO GUI) must pass before any stage
  commit. It runs sodium/iris-ABSENT — proves plain-path inertness.
- **Java-process cleanup:** only kill java PIDs whose command line contains THIS worktree path; never
  `gradlew --stop`; never touch idea64 (global CLAUDE.md rules).
- **In-game shader-settings applies** used to corrupt whole sessions (stale uniform-location cache → 248k
  wrong-uniform writes). FIXED (IS5-H `GlProgramClipCacheMixin`), but still VERIFY settings once at session
  start and keep an eye on the GL-error census after any apply.
- **iris jar for javap:** `/c/Users/warwa/.gradle/caches/modules-2/files-2.1/maven.modrinth/iris/1.11.2+26.2-fabric/f7d526b1062c4bfe2567113cf933d1de26eddd3f/iris-1.11.2+26.2-fabric.jar`;
  sodium: `.../maven.modrinth/sodium/mc26.2-0.9.1-fabric/14f3388694fa77f870d28262f74562de67eabcbe/sodium-mc26.2-0.9.1-fabric.jar`;
  MC mapped: `/c/Users/warwa/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`. javap EVERY symbol before
  reflecting/mixing (the naming-assumption ban). Complementary's GLSL is extractable from
  `fabric/runs/client-sodium/shaderpacks/ComplementaryReimagined_r5.8.1.zip`.
- **HARD-LEARNED SEAM RULES:** (1) at the compat anchor, NEVER read the woven `LevelRenderer.pipeline` field —
  it is NULL in the finally window; use `Iris.getPipelineManager().getPipelineNullable()` (the guard/heal
  precedent). (2) NEVER cite iris's post-restore `debugStringTerrain` C-string as shadow-scope truth (mixture
  value). (3) glReadPixels ⇒ GL_PACK_* bracket (26.2 invariant #4). (4) per-PORTAL vs per-FRAME semantics
  matter — state written per-portal must be handled per-portal (IS5-G lesson). (5) mixin-added iris fields
  keep their names — resolve exact-then-substring.

## §1 WHERE THE ENGAGEMENT STANDS — the shipped stack + its levers

All DEFAULT-ON fixes (disable levers for A/B), all counter-instrumented, all live-attributed:

| Piece | What | Off-lever (`-Dseamlessportals.`) |
|---|---|---|
| Fix 1 | shadow-scope clip suppression (the WASH killer) | `disableShadowScopeClipFix` |
| Fix 2+3 | shadow/camera scope-split keys + per-upload layer-batch clear | `disableShadowScopeIsolation` |
| IS5-G | per-portal zeroing of guard-saved TAA/temporal history before each dest render | `disableIrisDestTaaClear` |
| IS5-PH | prev-uniform heal (the GHOST killer — one notifier re-tick post-portals) | `disablePrevUniformHeal` |
| IS5-P | temporal-target guard save/restore (the old whole-screen phantom fix) | `disableIrisTemporalGuard` |
| IS5-H | per-program clip-location-cache invalidation at `GlProgram.close` | (unlevered — pure correctness) |
| retired | IS5-L counter-bump default OFF (`irisPerFrameRefresh=false`); shadow-sync mixin DELETED | — |

Probes/instruments available (all default-OFF): `-PshadowAliasProbe` (per-pass batch provenance + occupancy
grid + ALL fix counters `[FIX-1]/[IS5-G]/[IS5-PH]`), `-PfullbrightProbe` (per-draw uniform compare at
trySetup), `-PshadowProbe` (ShadowEmptinessProbe §1-§7), `-PclipProbe`, `-PdebugTintStamp` (magenta stamp dye),
`/imm_ptl_client_debug debug_no_stamp_depth_clamp` (+ the older debug_dye_* commands), the client-gametest
self-run harness.

**ACCEPTED COSTS (not bugs — do not "fix"):** the in-window view runs TAA-history-free (sub-pixel jitter
wobble when stationary + fresh-per-frame reflections INSIDE the aperture; IS5-G's cost; the ledgered proper
upgrade is per-dest persistent history). Main view outside the window must remain byte-stable.

## §2 THE QUEUE — the regressions/polish items (user-reported 2026-07-23 while confirming the wave fixes)

### 2a. DEST-VIEW BOBBING (NEW, unpinned)
Symptom: "the portal view is bobbing like player bob" — the dest view seen through the window bobs with the
player's walk-bob. UNKNOWN whether new-with-the-fix-stack or pre-existing-newly-noticed (the user first
reported it on a build where the heal was NOT firing, i.e. behaviorally old — leans PRE-EXISTING).
- Leading hypothesis (UNVETTED — diagnose first): view-bob applied TWICE in the compat chain — once in the
  passed `modelView` (main pass matrices include bobView) and once by the nested dest render's own
  GameRenderer-side matrix build. Or the inverse: bob in the stamp matrices but not the dest render.
- FIRST MOVES: (1) shaders-OFF stencil renderer A/B — does the stencil portal view bob the same way? (pins
  compat-path vs global). (2) MECHANICAL (Opus): trace where bobView enters — `MyGameRenderer
  .renderWorldFullPipeline`/`switchAndRenderTheWorldFullPipeline` camera/matrix setup (~:591+),
  `SecondaryWorldRenderCore.renderDestWorldFullPipeline` Step-7 projection install (~:1616), the stamp's
  `modelView` argument (`IrisCompatOn262Renderer.doRenderPortal` → `stampPortalArea(portal, ..., modelView,
  getCurrentProjectionMatrix())`). (3) PROBE: 1Hz matrix dump comparing the dest draw modelView's bob
  component vs the main pass's — log, don't reason.
- IP fidelity reference: does IP 1.21.3 bob its portal views? The user can run original IP side-by-side
  ([[ip-sidebyside-ground-truth]]) — a 2-minute experiential check that sets the CORRECT target behavior.

### 2b. ENTITIES NOT VISIBLE THROUGH THE PORTAL (KNOWN old regression, now priority)
Symptom: no entities render in the dest view through the window. **This is the ledgered C2-era sodium
regression** (memory `entity-vanish-cooldown-mirror-gate`: entities-through-window worked pre-sodium,
user-confirmed twice 2026-07-17; absent under sodium since C2 2026-07-19). NOT caused by this arc's fixes —
but now the most visible fidelity gap.
- Scope: sodium/compat path. The dest entity path: the M4 cross-portal entity machinery
  (`MixinLevelRenderer_CrossPortalEntity` submitEntities), `SecondaryWorldRenderCore` LRS/extract identity
  asserts (§8-14), the D1 shared-RSM drive. Under the shaders-ON compat renderer the dest render is the
  nested full-pipeline pass — do entities get SUBMITTED there at all?
- FIRST MOVES: (1) MECHANICAL (Opus): map the dest-pass entity submit path end-to-end (extract → submit →
  draw) in both the shaders-OFF stencil route and the shaders-ON compat route; find where sodium's presence
  changes it. (2) PROBE: 1Hz counters — entities submitted in the dest pass vs entities drawn (visibleSections
  / submit counts, the S18 lessons name the per-pass structures). (3) A/B: shaders-OFF same rig — are entities
  visible in the stencil portal view under sodium? (Splits sodium-general vs compat-renderer-specific.)
- CAUTION: the old ledger says the regression predates the compat renderer — do not assume this arc's scope
  split (Fix 2) changed entity visibility; VERIFY with the lever (`-PdisableShadowScopeIsolation`) before
  attributing anything.

### 2c. SOURCE PARTICLES BLEEDING INTO THE DEST VIEW (NEW, unpinned — may be partially CORRECT behavior)
Symptom: source-world particles visible over/in the portal window ("source particles bleeding into/visible
through portal view in dest view").
- The physics: particles PHYSICALLY BETWEEN the camera and the portal plane SHOULD render over the window
  (correct occlusion). Particles BEHIND the portal plane should NOT. The snapshot (deferredBuffer) holds the
  source scene INCLUDING its particles; the stamp paints dest over it inside the aperture, GEQUAL-depth-tested
  vs the SNAPSHOT depth — and vanilla particles typically do NOT write depth, so the snapshot depth at
  particle pixels is the terrain BEHIND them → the stamp should paint over behind-plane particles. IF the user
  sees behind-plane particles over the window, something in that chain is off (or iris's particle pass DOES
  write depth under Complementary — check `particles.properties`/iris particle settings — which would make
  behind-plane particles WIN the stamp's depth test = the bug).
- FIRST MOVES: (1) USER CHARACTERIZATION: are the bleeding particles between them and the portal (correct) or
  clearly behind/beside it (bug)? Frame a film: stand so rain/campfire smoke is strictly BEHIND the portal
  plane. (2) `-PdebugTintStamp`: magenta = stamped pixels; particle pixels NOT magenta = the stamp skipped
  them (depth-test loss) → the depth-write question; particle pixels magenta-but-particles-visible = they're
  in the DEST pass (different story). (3) MECHANICAL (Opus): iris particle depth-write behavior under
  Complementary (`shaders.properties` particle options, `gbuffers_textured`/particle program DRAWBUFFERS +
  depth mask) — javap/GLSL, no guessing.

### 2d. VISUAL INVENTORY PASS (the "probably other things I didn't notice")
Open the session with a structured film pass: one slow 360° at a same-dim portal + one at a cross-dim portal,
shaders ON, all defaults — user narrates anything off (the wave's absence should make smaller faults visible
now, exactly as the wash unmasked the ghost). Triage new items into this queue before fixing anything.

## §3 FOLLOW-UP LEDGER (pre-existing, do not lose)
- **Per-dest TAA history** (real temporal AA inside portals) — the proper upgrade replacing IS5-G's
  neutralization. Design sketch: dest-owned persistent texture pairs swapped in around the dest render
  (the guard's scratch machinery is reusable); per-portal keying; resize handling.
- **Leg C:** world-anchored sun-ward caster shortfall at the dest island's edge (native selection over the
  bounded island) — walk-around-recedes, time-of-day-tracked; polish via island supply.
- **Cross-dim shadow C:0/0** (map empty at rest, earlier session) — assumed-distinct RSM-single-instance
  fault; never diagnosed on the current stack; re-test cross-dim under the shipped fixes first.
- **~4-8 "Invalid format" GL lines** at world join (phantom-fix realloc-frame residual) — known-minor.
- **Scaled-portal stale `gbufferProjection`** (8e51e5b [5]/[6] probe queued) — latent, unscaled portals
  unaffected.
- **Multi-dim same-frame heal caveat:** the heal targets the CURRENT dim's main pipeline; portal-in-portal
  cross-dim stacks leave the other dim's tracker one frame stale (TAA re-converges ~3 frames) — benign,
  ledgered.
- The UX ledger items from IS4 (no IPConfig binding for the shaderpack-views opt-out; the stale D8 notice).

## §4 FRESH-SESSION STARTER PROMPT
Saved alongside as `migration/PORTAL_VIEW_POLISH_NEXT_PROMPT.md` (paste verbatim).
