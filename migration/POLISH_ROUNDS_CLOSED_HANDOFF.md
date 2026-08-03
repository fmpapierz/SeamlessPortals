# POLISH ROUNDS 1+2 CLOSED — NEXT-ENGAGEMENTS HANDOFF (2026-07-25)

Both portal-view polish rounds are **CLOSED, all fixes live-confirmed by the user + log-proven**, on
`iris-on/is5-shadow` (HEAD `076948b`, pushed). This handoff carries: the standing rules, the full
shipped stack + levers, the TWO USER-GATED COMMISSIONS (do not start either without the user's explicit
word), and the follow-up ledger. Working history: `migration/POLISH_SESSION_NOTES.md` (the full
per-item evidence trail); memory `portal-view-polish-queue` + `per-dest-state-commission` +
`polish-rounds-lessons`.

---

## §0 STANDING RULES (unchanged from the polish handoff — every one re-earned again this arc)

- **NO GUESSING / DIAGNOSE-FIRST.** Instrument + confirm on the live client before designing. This arc:
  a recon's geometry analysis shipped a provably-no-op fix (empty roster) that only adversarial verify
  caught; the despawn spec would have shipped a server-wide regression (view-square membership) ditto.
- **ADD LOGS FOR EVERYTHING incl. failure paths**: once-only liveness on first fire, confirm-COUNTERs
  probe-readable, once-only WARNs on EVERY silent-skip/throw path, 1Hz max render-thread (server thread
  exempt from the log4j rule but event-rate only).
- **READ THE FULL latest.log every run** (`fabric/runs/client-sodium/logs/latest.log`): GL census first
  (baseline ~6 known "Invalid format" = iris's copyPreHandDepth/copyPreTranslucentDepth on
  pipeline-recreate frames, NOW STACK-TRACED — the count scales with in-game shader applies; any NEW
  class = investigate), then liveness/counters vs the prior run.
- **MODEL TIERS**: fable = theory/design/adjudication/adversarial-verify; opus = mechanical recon
  (javap/decompile/log tabulation). Panels for every non-trivial mechanism: 2-3 independent
  analysts/designers → adjudicate → 2 adversarial verifiers (distinct lenses) → majority-bound fold →
  FINAL-DIFF verification against the implemented code (the spec-level pass is NOT enough — both major
  catches this arc were spec-level-invisible). Depth over speed (user-endorsed).
- **Worktree**: `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow` (NOTE: inside
  the project dir — NOT "Portal 26.2.claude"), branch `iris-on/is5-shadow`. Push every stage commit to
  origin. `git add` explicit file lists only. Suite gate before every commit:
  `.\gradlew.bat :fabric:runCrossingGametest` (8-leg, no GUI, sodium/iris-absent).
- **Live runs**: `.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true` (+probe -P flags), iris
  1.11.2 + sodium 0.9.1 + Complementary r5.8.1. User films willingly; pre-screen log-classifiable
  verdicts yourself; TRUST the user's live observations over screenshots/bytecode readings.
- **Lever discipline**: every fix DEFAULT-ON `-Dseamlessportals.disableX`; every probe DEFAULT-OFF;
  every lever a `-P` row in BOTH fabric/build.gradle blocks. A/B-attribute both directions.
- **JAVA_HOME gotcha**: Adoptium auto-updates delete the old JDK dir — the user's shell breaks with
  "JAVA_HOME is set to an invalid directory" after updates/reboots. Current:
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` (verify the dir, `setx` offered before).
- **HARD-LEARNED SEAM RULES** (cumulative): manager-slot-not-woven-field at compat anchors; the
  post-restore C-string ban; GL_PACK brackets on glReadPixels; per-PORTAL vs per-FRAME semantics;
  iris mixin simple names MUST contain "Iris" (IPCompatMixinPlugin footgun; "IrisSodium" checked
  first); ChunkPos is a RECORD in 26.2 (`x()`/`z()`); `E:\Immersive Portals - Copy` is NOT upstream
  IP — real IP 1.21.3 source = `C:\Users\warwa\ModDev\ImmersivePortalsMod`; iris pipeline reloads at
  frame boundaries only; NEVER gate iris-behavior fixes on isShaders() when a per-frame discriminator
  of what iris DID exists (the bob relocation lesson); verify a data source's FEEDERS in the target
  config before consuming it (the two roster catches).

## §1 THE SHIPPED STACK (this arc's commits, all live-confirmed; the IS5 wave stack in
`PORTAL_VIEW_POLISH_HANDOFF.md` §1 remains live beneath it)

| Commit | Fix | Off-lever (`-Dseamlessportals.`) |
|---|---|---|
| `19f0184` | EntityVisibilityProbe kit ([ENT-PROBE] funnel + dark-path WARNs) | probe: `entityProbe` (default OFF) |
| `e35de6a` | §2b Step-9.5-SD compat same-dim entity fill | `disableCompatSameDimEntities` |
| `e35de6a` | §2c source-particle cull (IP-entity roster; Fabulous bleed; live-DORMANT — iris sticky-disables Fabulous) | `disableSourceParticleCull` |
| `448f828` | §2a IS-BOB derive-pose bob sync (window tracks the bobbing world) | `disableIrisBobSync` (+`bobSyncProbe`) |
| `c3b4be3` | §2g portal-fed ticket despawn suppression | `disableTicketDespawnSuppress` (+`despawnProbe`) |
| `8130102` | §2h IS5-FF nested shadowcomp suppression (Ultra ACT lava phantom) | `disableNestedShadowComposite` (+`nestedShadowCompositeProbe`) |
| `d7cb09d` | §2f C3-BLOOM aperture mask (bloom ring) | `disableIrisBloomApertureMask` (+`bloomMaskProbe`, `debugTintBloomMask`, `debugBloomMaskBlackout`) |

Spec archive branches on origin: `c3bloom/adjudicated` (7485646, the C3-BLOOM spec), `c3bloom/branch-a`
(the rejected capture design, reference only).

## §2 THE TWO USER-GATED COMMISSIONS — **START ONLY ON THE USER'S EXPLICIT WORD**

### 2-A. PER-DEST PERSISTENT IRIS STATE (ACT volume + TAA history TOGETHER) — commissioned, gated
The user's words: "save the commission… i will say when to start that. dont start it automatically."
Full brief: memory `per-dest-state-commission`. One arc, both halves (shared binding-swap machinery):
1. **Per-dest ACT volume** (the §2h accepted cost the user DISLIKES — dest-side colored emitters show
   wrong bounce through the window): dest-owned floodfill_img/_copy(+voxel) pair swapped under the
   pack's image bindings; un-suppress the nested shadowcomp WHILE swapped (IS5-FF becomes "suppress
   only when no dest volume"). ~+1 GiB VRAM at Ultra per active dest.
2. **Per-dest TAA history** (round-1 ledger): dest-owned colortex history pairs replacing IS5-G's
   zeroing → real TAA in windows. NOTE: the user runs TAA_MODE=0 (sidecar, since the ghost saga) —
   ask whether they'll re-enable Temporal Filtering at engagement start; half 2 is moot until then.

### 2-B. MOTION-BLUR BLOOM-RING POLISH — commissioned ("save for a later polish fix"), gated
With Complementary MOTION_BLUR_EFFECT=1, composite4 becomes the LAST colortex0 writer (DRAWBUFFERS:30)
⇒ C3-BLOOM's "after last writer" mask point lands AFTER the bloom gather ⇒ today's ring returns with a
VALID plan (the shipped amendment-3 once-only INFO names it in the log). Fix shape (needs its own
mini-recon + panel): recognize the MB pack shape and mask at L instead of L+1 — requires proving
composite4's own c0 write (the motion-blur output) is mask-compatible (the mask would black what MB
writes outside the aperture — walk its consumers first).

## §3 FOLLOW-UP LEDGER (pre-existing + new; none user-gated, all ask-first)

- **Round-1 §3 leftovers** (PORTAL_VIEW_POLISH_HANDOFF): leg-C sun-ward caster shortfall; cross-dim
  shadow C:0/0 retest under the current stack; scaled-portal stale gbufferProjection — MECHANISM NOW
  KNOWN (writeProjectionSlice never triggers sodium's getBuffer wrap ⇒ iris re-captures the stale main
  value for dest passes; fix = update sodium's captured field or the iris capture for the dest pass);
  the IS4 UX items (no IPConfig binding for the shaderpack-views opt-out; stale D8 notice).
- **The nested dest render RE-RENDERS THE TRANSLUCENT HAND** (iris draws the hand inside
  endLevelRender; no suppression exists) — a phantom in-window hand candidate; unreported by the user
  so far.
- **prepareRenderer.renderAll() runs nested + unsuppressed** (IS5-FF ledger row): no persistent-writing
  PREPARE pass exists in Complementary r5.8.1; a future pack's would be a sibling pollution channel at
  a different seam (CompositeRenderer is shared by begin/prepare/deferred/composite — blanket-cancel
  would kill the dest deferred chain; needs its own design).
- **bufferObject.0** (773 MiB WSR face-data SSBO, never cleared) — a second potential §2h-family
  channel; user-exonerated for the lava symptom (WSR-off toggle); would matter if WSR reflections ever
  show cross-world imagery.
- **The sim-to-view-ring over-discard** (§2g verifier ledger): mod view-square tickets make the
  160..192-block ring entity-ticking ⇒ the mod discards mobs there that vanilla would freeze-and-save —
  pre-existing, invisible, NOT fixed by portalFed (correctly out of §2g's scope).
- **§2g minor**: portal squares overlapping a player's 128..160 sim ring over-retain (retention
  direction, tiny); noActionTime accumulates while suppressed (band-eligibility on lift); JEP-500-era
  final-field reflection fallback decision for IS5-FF.
- **Cross-dim compat entities (route `f`) still live-untested** ([ENT-PROBE] routes f=0 all sessions —
  no cross-dim portal viewed with the probe armed; Step-5 runs there so it PROBABLY works).
- **Accepted costs standing** (do not "fix" without the commissions): in-window ACT bounce =
  source-translated (2-A half 1 is the fix); in-window TAA-history-free look (2-A half 2; moot at
  TAA_MODE=0); in-window bloom dimming at the window edge (correct occlusion physics); MB-ON ring
  (2-B); ≤2px FXAA/sharpen crop-edge class.

## §4 PROBES / INSTRUMENTS (all default-OFF, `-P` rows in both gradle blocks)

`entityProbe` ([ENT-PROBE] funnel: lvl→cons/rej(hid,nv,nd5)→extr→sub→pes, routes x/f/sd/fsd, spc,
latches) · `despawnProbe` (event-rate [DESPAWN-PROBE] via Entity.setRemoved — NOT remove; unload
bypasses it) · `bobSyncProbe` (1Hz [BOB-SYNC] relocated/projDeltaMax/poseT/applies) ·
`nestedShadowCompositeProbe` (1Hz [IS5-FF] installs/noopHits) · `bloomMaskProbe` (1Hz [C3-BLOOM]
masks/misses) + `debugTintBloomMask` (magenta window = flip-side proof) + `debugBloomMaskBlackout`
(black window = footprint proof) · plus the whole IS5-era kit (shadowAliasProbe, fullbrightProbe,
shadowProbe, debugTintStamp…) per `PORTAL_VIEW_POLISH_HANDOFF.md` §1.

## §5 FRESH-SESSION PROMPT
`migration/POLISH_NEXT_SESSION_PROMPT.md` — paste verbatim.

---

## §6 SHARP-WINDOW RE-AUDIT — RAN 2026-08-01, ALL FIVE CLEAN

The whole polish queue was originally adjudicated while the portal window was BLURRED by the MB
smear (closed `32aa3cc`, *"FINALLY NOT BLURRY"*). Since a blur hides exactly the defect classes
those verdicts turned on — high-frequency, edge-localised and sub-pixel — the accepted-cost rows
were re-checked against a sharp window.

Selection principle: re-test the verdicts that rested on FINE DETAIL, not the ones that rested on
presence/absence (a blur never hid "is the cow there"). The five re-checked, and the user's verdict
on each: **all clean.**

1. **Phantom in-window hand** (§3 ledger: *"the nested dest render RE-RENDERS THE TRANSLUCENT HAND …
   unreported by the user so far"*) — the strongest candidate, since a faint translucent ghost is
   precisely what a blur erases. NOT OBSERVED sharp.
2. **The ≤2px FXAA/sharpen crop edge** (accepted cost) — a 2-pixel artifact accepted against a
   blurred window was an unfalsifiable acceptance. NOT OBSERVED sharp.
3. **Bloom dimming at the window edge** (accepted as "correct occlusion physics") — edge phenomenon,
   verdict formed blurry. Holds.
4. **In-window ACT bounce = source-translated** (accepted cost). Holds.
5. **Scaled-portal stale gbufferProjection** (round-1 §3 leftover; independently re-derived by the
   IS5-XCUT recon as a one-frame projection lag). NOT OBSERVED.

### §6.1 THE CAVEAT THAT KEEPS THIS HONEST
Two of the five have TRIGGER CONDITIONS that a normal look does not exercise, so their "clean" is
weaker than the other three and should not be quoted as a closed finding:
- **#5 can only manifest while the FOV is ANIMATING** (sprint start/stop, bow zoom, speed effects) —
  at constant FOV the stale projection equals the live one and the defect is *mechanically unable*
  to appear. A clean look at constant FOV measures nothing about it.
- **#4 needs a coloured emitter (lava / glowstone) positioned so its bounce falls inside the
  window** — with no such emitter in view the check has no target.
This is the same "a probe pointed at nothing reports clean" rule the instrument work in this arc
kept re-earning, applied to a human observer. Items 1-3 do not have this problem: their trigger is
simply "look at a portal window", which was satisfied.
