# S00-U0-decisions — Stage-S0 decisions record (entity-portal migration)

**Stage:** S0 (Substrate + decisions, U0) · **Written:** 2026-07-13
**Governing plan:** `migration/EXECUTION_PLAN.md` (FINAL, 2026-07-12) — this note restates the
plan-shaping decisions D2, D3, D6, D7 and forced-deviation F21 **normatively**, with citations,
so later stages consult THIS file instead of re-reading the whole plan. Nothing here amends the
plan; where wording differs, the plan governs. The D1 holding machinery itself is documented in
its own artifact (`buildSrc/src/main/groovy/com/warwa/seamlessportals/gradle/IpHeldPaths.groovy`,
landed as S0 commit 1) and is not restated here.

Status legend: **SETTLED** = binding from S0 onward. **AWAITING-ACKNOWLEDGMENT** = the plan's
resolution is adopted provisionally and MUST be put to the user at the named checkpoint.

---

## D2 — Package/namespace policy: verbatim retention of `qouteall.*` (SETTLED)

*Source: EXECUTION_PLAN.md §1 D2 (hard constraint 6); DEPENDENCY_ORDER.md §4.2;
memory `seamless-portals-is-ip-port`.*

**The rule.** Ported IP code keeps IP's original packages **exactly** —
`qouteall.q_misc_util.*`, `qouteall.imm_ptl.core.*`, `qouteall.imm_ptl.peripheral.*` — including
mixin packages and ResourceLocation namespaces where IP hardcodes them. Ported source lands at
its FINAL path from its first commit: `common/src/main/java/qouteall/**` (no later `git mv`;
D1/D4.3 depend on path stability).

**The boundary.** NEW code with no IP original — the U0 loader seams, build machinery, any
mod-owned dispatch or glue — lives in `com.warwa.seamlessportals.*`. The package name IS the
ported-vs-mod-owned boundary; a file under `qouteall.*` is a 1:1 port subject to the D4.3 diff
gate, a file under `com.warwa.seamlessportals.*` is mod-owned and never diff-gated against IP.

**Rationale (from the plan, verbatim in substance):**

1. **RPC FQN strings are wire protocol** (DEPENDENCY_ORDER §4.2). `McRemoteProcedureCall`
   addresses handlers by literal FQN strings (e.g.
   `qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos`).
   Cross-mod wire compat is not required (new standalone mod), but a rename would demand
   catching every hardcoded literal, whitelist entry, mixin-JSON package and reflection site —
   each miss a RUNTIME-ONLY failure. Verbatim retention reduces that hazard class to zero.
2. **The diff gate becomes mechanical** (D4.3): with paths preserved,
   `git diff --no-index <IP>/src/main/java/qouteall/<unit> common/src/main/java/qouteall/<unit>`
   is a per-stage 1:1 fidelity check where every hunk must be an API translation traceable to a
   slice-map row or a forced-deviation register entry — and it works for HELD units, giving the
   silent staging stages (S4–S12) a mechanical definition-of-done before the code compiles.
3. **Collision risk with upstream IP on 26.2 is nil** — upstream stops at 1.21.3; this mod is
   the successor line. Coexistence was never a goal.
4. **Corollary (Appendix A.9):** IP's Fabric entrypoint classes (`MiscUtilModEntry`/
   `MiscUtilModEntryClient`, `platform_specific` entrypoints `IPModEntry`/`IPModEntryClient`/
   `IPModEntryDedicatedServer`/`IPModMenuConfigEntry`, `IEClientWorld_MA`,
   `platform_specific/mixin/*`) are NOT ported as classes: their roles are absorbed by the
   D2.4/F12 mod-owned loader seams in `com.warwa.seamlessportals.*`. Their init/registration
   CARGO is inventoried at S0 (seam inventory) and wired at owning stages (§4.2 init order → S10;
   `DimIdSyncPacket` login slot via `MixinPlayerList_Misc` → landed S7, registered S13;
   entity-renderer registrations from `IPModEntryClient:27-28` → S0 seam, wired S13 step 5).
   Any behavior found in these entrypoints beyond the seam-inventoried cargo is a stage-time
   diff-gate item — never silently dropped.

---

## D3 — Flag strategy: `entityPortals`, load-time read, exclusivity ledger (SETTLED)

*Source: EXECUTION_PLAN.md §1 D3; API_RISKS.md cutover verdict ("the atomicity is in the
user-facing switch, not the development process"); current-mod-core.md §7 (SeamlessPortalsConfig
KEEP), §8 (SeamlessMixinConfigPlugin ≙ IP's IPMixinPlugin family); precedent: the mod's proven
flag-gated dual render path `STENCIL_DIRECT` (memory `stencil-direct-rework-status`).*

**The flag.** ONE boolean key **`entityPortals`** in the existing `SeamlessPortalsConfig`
properties file (a KEEP class, current-mod-core §7).

- Default `false` until S17; default `true` from S17 (the cutover flip); the key is **deleted
  with the old system at S20**.
- **Load-time, not runtime:** read ONCE at mixin-plugin time by the KEEP'd
  `SeamlessMixinConfigPlugin` (which absorbs the role of IP's `IPMixinPlugin` family,
  current-mod-core §8; the ported `IPMixinPlugin` class' gate is wired THROUGH the mod's plugin
  — see S4(a)). Flipping the flag requires a **game restart**. This is accepted deliberately:
  it eliminates every half-initialized-manager hazard a runtime flip would create.

**The exclusivity ledger.** `migration/EXCLUSIVITY_LEDGER.md` is a COMMITTED artifact (skeleton
at S0 — deliverable 3 of this stage; fully populated and ENFORCED at S13 before the first
flag-ON run; updated at S16; removed at S20). It is the explicit dual-driver contract that makes
flag honesty auditable. Its four normative categories (see the ledger itself for the census):

1. **Flag ON suppresses the block-era DRIVERS** — each gated `!entityPortals` in MOD-OWNED code,
   landed in the SAME commit that registers the IP mixin set at S13.
2. **Block-era SUPPRESSIONS active in BOTH flag states** — e.g. the `handlePortal` cancel keeps
   vanilla nether-portal blocks inert in flag-ON worlds until S16 replaces it with IP's
   structural suppression (redirected `findEmptyPortalShape`; ledger updated then).
3. **Substrate KEEPs that apply ALWAYS** (both flag states) — stencil FBO chain, extractor
   plumbing, `DimensionRenderHelper`, `lateUpdateLight`, `FrontClipping`, diagnostics (F16), etc.
4. **Shared host mixins** (e.g. the S3-relocated pre-render pump host): the MOD-OWNED mixin body
   dispatches on the flag to either the old pump or the ported IP call chain — **mod code
   dispatches; IP code bodies are never flag-polluted** (zero-deviation preserved).

**Consequence (the operational invariant):** at no point do two portal drivers run in one
session — the answer to API_RISKS' shared-mutable-seams / one-driver-per-frame prohibition.

**Registries-unconditional rule.** From S13, registry entries are **unconditional in both flag
states**: the Portal entity-type family, `PortalPlaceholderBlock`, argument types, payload
registrations. Registries must not differ between flag states or world saves break on flips.

**Dedicated-test-world rule (documented consequence of the above).** Because registries are
unconditional from S13 while behavior is flag-gated, flag-ON testing happens in **DEDICATED test
worlds only**; **never open a main world with `entityPortals=true` before S17**.

**Rollback story.**

- Any stage S13–S19: rollback = set `entityPortals=false` (or revert the stage's commits).
- After S20 (flag + old system deleted): rollback is **git-only** — which is exactly why S20 is
  gated on full green 12-point regressions at S17–S18.
- Pre-S13 stages have no flag surface at all: rollback = revert commits (each stage's (f) line).

---

## D6 — Spike discipline: branch-only empirical probes (SETTLED)

*Source: EXECUTION_PLAN.md §1 D6 and §3 S1; hard constraint 2 (no stubs / no simplified
variants); D4 instruments.*

**The rule.** S1 runs exactly **four never-merged probes**, each on its own `spike/*` branch,
each producing a memo in `migration/spikes/`. Spike branches are deleted after memo extraction;
**nothing merges to main** — the memos are the only artifact that survives.

The four S1 spikes (details in the plan's S1(a)):

| Spike | Probes | Deliverable |
|---|---|---|
| SPIKE-R1 | 26.2 `ClientLevel` ctor's trailing `int seaLevel` + secondary-world construction on the current machinery (API_RISKS R1) | seaLevel protocol design v1 (rides the S7 dim-id sync path) + remote-tick role placement (F10) — the GATING design round |
| SPIKE-R7 | packet re-queue via `packetProcessor().scheduleIfPossible(...)` + cancel, ordering + netty double-invocation shape (network.md §A; briefing §6 isSameThread) | confirmation/correction of §A before S7 implements it (F7) |
| SPIKE-R4 | surviving `ViewArea` surface (public ctor, overridable `repositionCamera`/`getRenderSectionAt`) + the redirect retarget in `LevelRenderer.invalidateCompiledGeometry` (API_RISKS R4) | evidence for the S11 CUTOVER_SPEC R4 decision |
| SPIKE-R11 | `SavedDataType` + candidate `DataFixTypes` constants; the null→NPE→silent-fresh-storage loss trap (API_RISKS R11; portal-generation G1) | the constant that round-trips a GlobalPortalStorage-shaped payload + a reproduction of the silent-loss signature (consumed by F15 at S7 and the S13 relog check) |

**Boundary defense — why spikes do not violate the no-throwaway rule (stated once, here).**
Hard constraint 2 bans **stub or simplified variants of IP code held in the shipped tree** as a
holding mechanism. Spikes are the opposite category on every axis:

1. **Never merged** — no spike line ever enters the shipped tree;
2. **They probe vanilla 26.2 API surfaces** (ClientLevel ctor, PacketProcessor, ViewArea
   subclass surface, SavedDataType), **not IP logic** — no IP class is ever written in a
   throwaway form;
3. **Their deliverables are DESIGN EVIDENCE** (memos + decided constants), which the
   zero-deviation port then consumes.

SPIKE-R4 in particular is scoped to a minimal **pass-through** `ViewArea` subclass proving
ctor/override/redirect viability only — explicitly NOT a toy `ImmPtlViewArea` variant.

---

## D7 — R3 constraint reconciliation (AWAITING-ACKNOWLEDGMENT — user decision at checkpoint C4)

*Source: EXECUTION_PLAN.md §1 D7, §5 C4, Appendix A.1; API_RISKS.md R3 + verdict;
DEPENDENCY_ORDER.md §2.2; briefing hard constraint 4.*

**This section is a QUESTION for the user, to be presented at checkpoint C4 (S11 design review;
re-confirmed at S18).** The plan adopts a resolution provisionally; executing stages follow it
unless the user overrides at C4. Both readings, stated fairly:

**Reading 1 — literal.** Hard constraint 4 says "R3 design+port trails the cutover": nothing of
R3 (`CrossPortalEntityRenderer`, the cross-portal entity render bracketing) — neither design nor
code — lands until after S17.

**Reading 2 — the plan's resolution.** The literal reading does not compile. Verified facts
(Appendix A.1, source-verified during plan synthesis):

- `IPMcHelper` imports `CrossPortalEntityRenderer`
  (`IP:imm_ptl/core/IPMcHelper.java:22-24`; DEPENDENCY_ORDER §2.2), and `IPMcHelper` is a U3
  class in the SCC — so `CrossPortalEntityRenderer` must exist in COMPILABLE 26.2 form at the
  S13 closure, four stages before the cutover.
- Its hard parts live in the class BODY, not just a client hook:
  `client.renderBuffers().bufferSource().endBatch()` at
  `IP:imm_ptl/core/render/CrossPortalEntityRenderer.java:123,139,208` and
  `consumers.endBatch()` at `:308` — all targeting the mid-batch-flush model that is GONE on
  26.2 (API_RISKS R3) — plus the dead `renderEntity` duck. Making it compile IS the R3 design
  work; there is no compilable-but-undesigned middle state.
- The only ways to keep the literal reading are: a stub (forbidden by hard constraint 2) or
  editing `IPMcHelper` to drop the import (a deviation). Any plan that lands the class
  "unmodified, held" and empties the exclude list at closure does not compile (Appendix A.1).

**The adopted resolution (the plan calls it "the only coherent one"): the R3 compile-level
design round + full 1:1 port happen at S11 (pre-closure, inside the render unit U9); "trails
the cutover" means R3's RUNTIME wiring, verification and iteration trail — at S18.** This
matches the API_RISKS verdict's own rationale ("additive… its absence at cutover is status quo,
not regression").

**What the user is asked at C4** (per §5 C4, together with the R3 delivery-mechanism choice,
clip-uniform vs one-entity storage): confirm the D7 reading — the compile-level half CANNOT
trail; only runtime verification can. If the user intends the literal reading, they must pick
one of the two forbidden alternatives explicitly (stub or IPMcHelper edit), which would itself
be a new forced-deviation register entry. **Status: AWAITING-ACKNOWLEDGMENT — no stage before
S11 depends on the answer; S11 must not start its R3 design round without C4 being put to the
user.**

---

## F21 — Sodium/Iris(/autoconfig) `compileOnly` stub-classpath (SETTLED; register entry)

*Source: EXECUTION_PLAN.md §4 register row F21; §1 D1 (build-engineering boundary); §3 S4(a)
"Sodium/Iris compile reality"; §3 S12; Appendix A.8; §5 C2; DEPENDENCY_ORDER §2.7/§2.11;
platform-compat-peripheral.md:245; COVERAGE INFO-3.*

**The problem.** Neither Sodium nor Iris (nor cloth-config/autoconfig) exists for MC 26.2, yet
IP's compat files reference their types directly and cannot be dropped or held past S13:

- `SodiumInterface` (in `compat/sodium_compatibility`, NOT `compat/`) imports and USES six
  `net.caffeinemc.mods.sodium.*` classes **in its own static-method bodies**; it is imported by
  `ImmPtlClientChunkMap` (S9), `IPModMainClient` (S10), `FrustumCuller`/`MyGameRenderer` (S11)
  and client mixins (S12) — so it must compile at S13 closure.
- Its companion `IESodiumWorldRenderer` is a `@Mixin` on Sodium's `SodiumWorldRenderer` and
  cannot compile without Sodium at all.
- The S12 Iris renderer shells import `net.irisshaders.iris.*` directly (cycle 12).
- **Round-3 extension:** `IPConfigGUI.java:3` imports `me.shedaniel.autoconfig.AutoConfig`
  (cloth-config/autoconfig — no 26.2 build), and `ClientDebugCommand.java:56` imports
  `IPConfigGUI`, pulling it into the S13 closure. The stub artifact covers autoconfig too.

**The decision (this register entry).** A **`compileOnly` stub-classpath ARTIFACT**: empty
shells of **exactly** the third-party types the compat files reference — no more. Artifact
shape as the plan records it:

- **Content:** empty shells (correct package + FQN + the referenced member signatures, bodies
  empty/throwing) of precisely the `net.caffeinemc.mods.sodium.*` types `SodiumInterface` and
  `IESodiumWorldRenderer` reference, the `net.irisshaders.iris.*` types the S12 Iris shells
  reference, and `me.shedaniel.autoconfig.AutoConfig` (+ whatever minimal autoconfig surface
  `IPConfigGUI` touches). The exact type list is derived by stage-time import-grep at each
  consuming stage — the same mandatory-filter discipline as D1 carve-ins.
- **Scope:** `compileOnly` ONLY — the stub artifact never ships in any jar, never reaches a
  runtime classpath. Runtime compat is a separate question governed by checkpoint C2 (default:
  shells only; dead on 26.2 until those mods port; entering C2 requires the COVERAGE INFO-3
  full-depth pass on 27 files first).
- **Consumption schedule:** decided S0 (this entry); **sodium half consumed at S4** (commit 2
  lands the artifact's sodium half with the held compat files); **iris half consumed at S12**;
  **autoconfig consumed at S13** (round-3 extension). `IPFlywheelCompat` lands S4 held;
  stage-time import-grep decides whether it too needs F21 stub types.
- **What F21 explicitly CANNOT cover:** the same-package `sodium_compatibility` qouteall files
  `SodiumRenderingContext` (`SodiumInterface.java:60` constructs it) and
  `IESodiumRenderSectionManager` (`SodiumInterface.java:72-73` casts to it) — they are **IP
  SOURCE, not third-party types**; they land HELD at S4 per S4(a) and compile as ports.
  `MixinSodiumRenderSectionManager` (S19-gated) imports both at `:14-15`.
- **Registration reality:** `IESodiumWorldRenderer` is never registered on 26.2 — the mixin
  plugin's sodium-present gate stays false.

**Boundary defense (why this is not a constraint-2 violation, stated once, here):** these are
THIRD-PARTY types, not IP source — hard constraint 2's no-stubs rule governs **ported IP code**
and is untouched; a stub classpath for absent third-party dependencies is build engineering in
the D1 sense. C2 governs runtime compat only.

---

## Cross-references

- D1 holding machinery: `buildSrc/.../IpHeldPaths.groovy` (landed, S0 commit 1);
  probe = `.\gradlew.bat :common:compileJava -Pip_scc_closed=true --console=plain --no-daemon`.
- D3 census + transitions: `migration/EXCLUSIVITY_LEDGER.md` (skeleton, S0 deliverable 3).
- Spike memos (S1): `migration/spikes/`.
- Forced-deviation register (all F-entries incl. F21): EXECUTION_PLAN.md §4.
- User checkpoints (C1–C8, incl. C2 sodium/iris runtime and C4 R3): EXECUTION_PLAN.md §5.
