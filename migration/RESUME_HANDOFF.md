# RESUME HANDOFF — stopped 2026-07-19 on the Fable 5 usage limit

**Read this first, then `migration/S19E_HANDOFF.md` (which chains to `migration/S19_HANDOFF.md` §0
for ALL standing rules — unchanged and still binding).**

Two commits are pushed on `claude/nifty-kepler`: `b2187ad` (S19-E wiring, fully verified) and
`28999ce` (commons tail, **VERIFY-PENDING** — see §2).

---

## 1. WHERE WE STOPPED

The session ran S19-E to completion, then hit the **Fable 5 usage limit** partway through two
concurrent workflows. Everything Opus-side finished; everything Fable-side (verify + design) died.

| Work | State |
|---|---|
| S19-E wiring (deps/stubs/cloth/detection+gating) | **DONE, verified, pushed** `b2187ad` |
| C2 phase-1 recon (IP 27-file depth) | **DONE, pushed** → `migration/C2_IP_COMPAT_DEPTH.md` |
| C2 phase-2 recon (0.9.1/1.11.2 javap + our substrate) | **DONE, pushed** → `migration/C2_091_MAP.md` |
| S19 commons tail (4 mixins) | **Code landed + gates green, but FABLE-VERIFY NEVER RAN** `28999ce` |
| C2 design round | **DID NOT RUN AT ALL** — `migration/C2_DESIGN.md` does not exist |

The model has since been switched to **Opus 4.8**. Note the standing model rule (Opus for
mechanical agent work, Fable for verify/design) — see the decision in §4.

---

## 2. THE FIRST THING OWED ON RESUME: the commons-tail verify

`28999ce` carries four ported mixins that are compile-green (x3) and suite-green (8 legs) but
**never faced an adversarial verify lens**. Standing rule 4 is unsatisfied → **no READY may be
issued and no live round may include this code until the verify runs.**

Re-run the two lenses that died (the workflow script is preserved and resumable):

```
Workflow({scriptPath: "…/workflows/scripts/s19-commons-tail-wf_d2838b99-db8.js",
          resumeFromRunId: "wf_d2838b99-db8"})
```
The impl agent replays from cache instantly; only the two verify agents re-run.

**The PRIORITY verify target** (flag it explicitly to the lens): the **D3 carve-out**. The impl
agent added `D3_UNCONDITIONAL_ITEM_DATAFIX` to `SeamlessMixinConfigPlugin`, making the legacy-item
datafix weave in **both** flag states. Its reasoning (recorded in the set's javadoc + the commit):
the wand/stick items and their DataComponentTypes are registered unconditionally (D3 save-parity,
port-note §1.1), flag-OFF is the shipping default, so gating the datafix flag-ON would skip it on
the primary migration path (an IP 1.20.4 world opened here) and silently sweep the stored
command/mode into `minecraft:custom_data`. That reasoning is plausible and evidence-cited but
**unaudited** — it is the one judgement call in the diff. Second target: the `MixinSplashManager_CVB`
26.2-forced rewrite (List<Component> + immutable-list reassign + the shadowed `literalSplash`).

Also unverified-by-anything: the four new mixins' target anchors (the agent cited mc262-ref line
numbers; nobody re-derived them independently).

---

## 3. WHAT IS LEFT, IN ORDER

1. **Commons-tail Fable verify** (§2) → fold corrections → amend/commit → **S19 CODE CLOSES**.
2. **S19 close-out**: EXECUTION_PLAN "S19 CLOSED" block, memory consolidation, and the two
   outstanding S19-E **live-round items** (both GUI/runtime, suite-unreachable):
   - the config-screen click: Mods → Seamless Portals → config must show the **real IP cloth
     screen** (this is the S19-B feature finally going live; the `crossPortalEntityClipMechanism`
     lang pair was added this session so no raw translation key should appear);
   - optional: `.\gradlew.bat :fabric:runClientSodium -PsodiumRuntime=true` to confirm the honest
     gating fires (loud log + red world-join chat + portal views off, teleport still works). The
     "teleportation still works" claim in that warning is **UNVERIFIED** — this is its test.
3. **C2** (user-directed to run BEFORE S20 + polish — decided this session, see §4 of
   `c3-c4-checkpoint-decisions` memory). Both ground-truth docs are on disk and committed; the
   **design round is what's missing**. The design-panel workflow script is preserved:
   `…/workflows/scripts/c2-design-panel-wf_ecdedcf8-4dd.js` — it takes the two docs as input and
   writes `migration/C2_DESIGN.md` (§0 decision record, §1 stage ladder, §2 the 27-file
   disposition table, §3 the three redesign specs, §4 open questions/probes, §5 deviation ledger,
   §6 D3/C7/S20 interactions). Resuming it re-runs all four agents (none completed, so nothing is
   cached).
4. **S20** (block-era deletion + survivor audit + 12-point regression), then **polish** — top item
   remains the **dim-persistence gap** (alt dims don't survive world reopen; the un-ported DimLib
   persistence half; diagnosis in port-note §6.2).

---

## 4. THE ONE DECISION TO MAKE AT RESUME

The blocked work (commons-tail verify, C2 design) is exactly the work the standing model rule
reserves for **Fable**. The session is now on **Opus 4.8**. So:

- **If Fable credits are available** → resume as designed (Fable verify + Fable design panel).
- **If not** → decide whether to run the verify/design on Opus instead. That is a genuine
  deviation from the standing rule (which exists because Fable caught real pre-ship blockers in 7
  of the last verify rounds), so it should be the user's call, not an assumption. Recommended
  framing if asked: run the **commons-tail verify** on Opus (small, mechanical, well-scoped diff —
  acceptable risk) but **hold the C2 design round for Fable** (it is the hardest design problem in
  the whole migration: three redesign-not-remap subsystems with no upstream ground truth).

---

## 5. CONTEXT THE NEXT SESSION SHOULDN'T HAVE TO RE-DERIVE

- **C2 is bigger than "port IP's compat"**. The phase-2 map proves three of the four Sodium chains
  are REDESIGN-not-remap against 0.9.1: render-list isolation (SectionTree model), culling (async
  multi-visitor + a 3-float section-center frustum test), and clipping (the entire `gl/` package is
  gone — RenderPipeline/UBO/bind-groups + a Vulkan backend; a loose injected uniform cannot exist).
  Only the freshness/tracker chain is a straight port.
- **Our clip mechanism ≠ IP's.** Ours is a view-space `seamlessportals_ClipPlane` (C4
  SUBMIT_ORDER_UNIFORM) uploaded on `GlCommandEncoder.trySetup`; sodium/iris terrain bypasses that
  seam entirely. C2's shader work must target OUR convention, not IP's world-space
  `iportal_ClippingEquation`.
- **Nothing sodium-related can weave yet**: there is no `imm_ptl_compat.mixins.json` in this repo.
  `ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT` (default false) must NOT be flipped until that
  mixin set exists — flipping it today CCEs at the `OnSodiumPresent` duck casts.
- **New ledger items from S19-E** (full detail in port-note §8.3): a C7 landmine (NeoForge lost the
  in-tree `me.shedaniel` classes → the qouteall config tree is NCDFE-if-reached there, latent only
  because the flag is force-OFF); a release-packaging gate (flag-ON now hard-requires cloth at
  runtime → needs JiJ before shipping); cloth's own example entrypoints now appear in dev runs and
  are NOT regressions.
- **Never pass `-PsodiumRuntime`/`-PirisRuntime` when running the 8-leg suite** — they are
  project-wide and would put Sodium on the suite's runtime, flipping the warn/force path inside it.
