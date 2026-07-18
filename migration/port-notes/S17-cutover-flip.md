# S17 — THE CUTOVER FLIP

Stage spec: `migration/EXECUTION_PLAN.md` §S17. The default flips: a fresh install runs the
entity-portal engine; `entityPortals=false` returns to block portals (two-way until S20).

## 1. The flip (S17.1)

`EntityPortalsFlag.readFromDisk`: the three clean missing-state paths (no config dir / no
file / no key) now resolve **TRUE on Fabric**; an explicit `false` is honored; hard I/O
failure mid-resolution still falls to the proven block-era baseline (never half-resolve to
the new default on an ERROR); off-Fabric force-false unchanged (S13-B P4). `seedIfUnset`
untouched (it only ever receives EXPLICIT keys — noted at the site). Config-file header +
javadocs updated. **Existing-install semantics:** a config file WITHOUT the key (pre-S13
installs) resolves to the new default TRUE; an explicit `false` (anyone who opted out)
stays block-era.

**Gametest-tooling consequences:** the crossing suite keeps its explicit `=true` seed (the
test contract must not silently depend on the shipping default); the TITLE-CARD capture is
pinned `=false` (it drives the block-era flow — its June-era run-dir config predates the
flag key and would have resolved TRUE; a flag-ON title card is a future re-shoot).

## 2. Pre-flip hardening (S17.2 — the two S14-ledgered items, round-7 §0b-4/§0c)

`LevelExtractorWindowHardeningMixin` (always-active set, in-body flag gates):
1. **Capture-point window resolution** — at `extract`'s `flipUpdateTrackingSets` INVOKE
   (after the render state captures the set REFS, before the flip), resolve added∩removed
   loadedChunks pairs against live chunk truth in place (`preResolvePromotedWindow`).
   Closes BOTH ledgered residuals in one move: the main-dim quantified-LOW unresolved
   application AND the sub-tick promote→first-capture re-poison race. No-op when no pair
   exists (the common case); idempotent over the promote-tick hook.
2. **setLevel re-arm assertion** — one-shot loud error (never a crash) if a future caller
   re-arms `shouldResetLevelRenderData` with the SAME kept ClientLevel (the silent
   loadedChunks under-seed landmine; unreachable today).

## 3. Pre-flip guard sweep (S17.3 — workflow `wf_5183007f-fee`, 69 mixins audited)

**3 CONFIRMED LEAKS gated** (the SectionCompilerMixin scalp's mandate paying off):
1. `NetherPortalUninteractableMixin.continueDestroyBlock` — my own S16.2 fix gated only the
   startDestroyBlock sibling; this handler still suppressed survival continue-destroy
   feedback on flag-ON vanilla portal blocks. Gated identically.
2. `ServerLevelFireSpreadMixin` — no self gate (transitively inert on Fabric via the empty
   block-era registry, but the NeoForge driver was ungated). Self-gated: structural
   inertness on all platforms.
3. `SeamlessPortalsModNeoForge.onServerTick` — the block-era portal scan ran UNGATED on
   NeoForge (Fabric registers it flag-OFF-only). Gated (`!isEntityPortals()`; force-false
   today makes it constant — this is C7-proofing).

**2 DEFENSIVE GATES** (judge-recommended per the 4x-failed inert-by-dormancy record):
`HandleRespawnMixin.beforeRespawn` (supersedes the B4 "no gate" decision — every flag-ON
branch judge-verified inert: gated writers, and the RemoteChunkDataPayload branch has NO
sender anywhere in the tree; flag-ON vanilla respawns ride vanilla + IP's onSetWorld
cleanup, crouch-hatch live-proven) and `ClientPacketListenerAddEntityAdoptMixin` (a
spurious fire would cancel vanilla's passenger/leash side effects).

**66 clean verdicts AFFIRMED** after adversarial re-verification (the clip-bridge pair,
SkyRendererTargetMixin, the stencil quartet, the GameRendererMixin TAIL family, all
accessors). Notes recorded: NeoForge platform-parity pass wanted at C7 (payload handlers
registered unconditionally there); `LevelExtractorCreateRegionBudgetMixin` is the one
unconditional behavior-modifying redirect on the flag-ON main path (BUDGET=24 spread cap,
verified non-dropping — one targeted crossing in the (d) round watches the fill-in);
`LevelRendererBlockOutlineMixin`'s outline fix covers only block-era portals → joins S18's
dest-outline item; the `SectionOcclusionGraphPartialUpdateSkipMixin`/
`LevelExtractorFlashBridgeMixin` inertness must be re-checked if any flag-ON code ever
touches `PortalContextSwitch.isRenderingPortal`/`armPromoteBridge`.

## 4. CUTOVER_SPEC R4/R5 sign-off (S17(a) — the spec is the contract)

**VERDICT: SIGNED OFF — WITH NAMED RESIDUALS** (verified against the S13-S16 runtime
record: rung-1 first light accepted (S13M:44), rung 2 USER-SIGNED-OFF 2026-07-18 after 8
defect rounds, rung 3 USER-SIGNED-OFF 2026-07-18, rung 4 USER-SIGNED-OFF 2026-07-18).

### R4 (§1) — the ImmPtlViewArea rebuild: ALL FIVE OBLIGATIONS PROVEN
- **Install + §1.3 flag gating:** shipped at S13 gated on `entityPortals`, never renderer
  identity (S12A:225); installed main + secondaries, consumed every flag-ON frame via
  `ip_getBuiltChunkStorage` (S13H Step 2; the duck half was the attempt-4 crash, fixed +
  one-pass-swept, S13B §14).
- **§1.2-1 coord-stable identity / unbounded store:** `rawFetch` exercised every armed
  discovery pass (S13C row 9) across all four rungs; survived the round-7 far-walk storage
  hunt with the root cause landing in the SOG delta pump, not the store (S14C-round7).
- **§1.2-2 fixed-size occlusion coupling:** stable advertised dimensions through every
  invalidate/`waitAndReset` across promote/demote cycles + the S14.51 reload-cascade fold;
  round-7 traced `waitAndReset(viewArea)` semantics to source. Zero size-instability
  failures.
- **§1.2-3 thread safety:** off-thread SOG full updates ran continuously (SPIKE-R4 E2
  callers real) incl. dp=11 many-portal stress; zero concurrency findings in 8 adversarial
  rounds (soak-class proof; no targeted race harness — noted).
- **§1.2-4 externalized dirty tracking:** the deepest-exercised obligation — S13-H Step
  5(c) extract→delta-feed→compile-drain in one finally; defect-proven by round-7 (S14.42)
  and the S14.51 F1 main-dim-arm mark-ownership fix + verify folds.
- **§1.4 earlyRemoteUpload named check — resolved with a DOCUMENTED SUPERSESSION:** S13-H
  proved the pump required BY CONSTRUCTION (the decomposition never runs `render()`'s
  upload tail) and pre-wired it (`MinecraftFramePumpMixin:79-81`), superseding the spec's
  land-iff-observed protocol; runtime concurred — no stranded-upload signature at rung-1
  or the S14 zero-chunk cold dest.

### R5 (§2) — the reversed-Z checklist: ALL 16 ROWS + §2.2/§2.3 PROVEN; §2.4 + R13i pending their assigned stages
- Rows 1-3, 5, 6, 8-11, 13 (direction-independent): S12-A row→line→constant execution
  table; live every portal frame; rung-1 acceptance encoded the R5 failure signatures and
  passed; S14C-round3 closed the stencil family BY PROOF under the wedge hunt.
- Row 4: GEQUAL compare flip proven by the stable all-angle window + recursion;
  **fuse-view writeDepth=FALSE branch unexercised** (residual below).
- Row 7 (the one raw-constant flip): S12-A P1 made the FAR clear land (DEPTH_CLEAR purpose
  pipeline); dest terrain never z-rejected from rung 1 on.
- Row 12: shipped IP's exact-projected-depth form via the S13-C H.4 ALWAYS_PASS variant —
  the spec's own sanctioned S13 option, MORE faithful than the flat NEAR shield; no
  main-pass overdraw of any opening S13-S16 (incl. S14 step-6 rain).
- Rows 14/15: recursion is the consumer — dp=11 captures, S15 layer≥2 + self-render closed
  live, S16 clusters.
- Row 16: S12-A P2 depth-independent fill; S14 step-1 PASS (nether fog correct
  pre-crossing, no bleed).
- §2.2 depth clamp: verbatim raw-GL, live with FrontClipping S13-S16 (OpenGL).
- §2.3 frustum hazard: BINDING as S13-H I7 (conventional-Z culling projection,
  captured-frustum applyFrustum skip); zero render-thread hangs — the SPIKE-R1 hang is
  deterministic, so absence is proof.
- §2.4 F18 Vulkan degrade: landed S12; runtime = **S18 (Vulkan run, if available)** per
  the spec Appendix — inherited residual, not an S17 blocker.
- R13i fabulous re-anchor: landed S12; runtime proof = **this stage's (d) step 13** by
  design.

### Named residuals carried into the (d) round
1. **R13i Fabulous test** — (d) step 13 (the one in-stage R5 runtime item).
2. **R4 >71-chunk same-dim dest check** — two command portals with dests >71 chunks apart
   (closes the unbounded store's headline retirement at runtime).
3. **Row-4 fuse-view write-mask** — optional spot-check if constructible by command; else
   routed to S18 (mirrors/periphery).
4. **F18 Vulkan** — stays on S18's ledger (recorded here for honesty).

Adjacent known-opens already routed to S18 (multi-portal pass-cost parity gap, dest
clouds/particles/outline, window rain, straddle render) are NOT sign-off failures for the
round.

## 5. S17 diff verify (`wf_f6e47e29-c9d` lens 1): PASS, zero blockers

Folds applied: the two stale EntityPortalsFlag inner javadocs (bootstrap-ordering + isOn);
a WARN log on the Throwable resolution path (post-flip that path is a visible RATCHET-DOWN
— saveTo persists false; a transient I/O blip pinning block-era must never be silent);
ledger PORT-FORWARD rows updated for the two newly gated mixins. Recorded-not-folded: the
exotic-bootstrap CWD-fallback opt-out miss (reflection-failure + CWD≠gameDir resolves TRUE
against an explicit false — theoretical on standard Fabric launches, session-consistent);
the titlecard run-dir pin overwrites the whole properties file (intentional, same pattern
as the crossing seed). Confirmed by the verify: the keyless-config ratchet-UP is intended
(the two-way switch holds); the hardening mixin's capture-point semantics, setLevel-assert
safety at both legitimate armers, HandleRespawnMixin's writer-audited flag-ON inertness,
and the NeoForge class-load-ordering safety.

## 6. (d) — the full 12-point regression round (user-gated)

(pending READY)
