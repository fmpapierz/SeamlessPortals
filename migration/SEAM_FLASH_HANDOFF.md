# IS5-SEAM HANDOFF — the black flash at the portal seam (OPEN)

**Worktree** `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow`, branch
`iris-on/is5-shadow`. Session 2026-07-26 → 2026-07-27 01:20.

---

## §1 WHAT CLOSED FIRST — the MB portal-window smear (do not re-open)

**CLOSED, user-confirmed live** (*"FINALLY NOT BLURRY"*), shipped DEFAULT-ON, suite green, pushed.
Full record in `migration/MB_SMEAR_HANDOFF.md` §00. One-line summary: same-dim source and destination
share a dimension ⇒ iris hands both chains ONE pipeline ⇒ the dest `composite4` drew with **two** wrong
pairs at once (camera AND previous-matrix). Fixed by per-chain nearest-camera state restore written at a
clobber-proof seam. Took four rounds; three were adjudicated with a defective census.

**The seam flash below was UNMASKED by that fix** — a full-strength blur over the window had been hiding
it. User: *"i guess i didnt notice it with the blur before."* **Expect more of this: the entire polish
queue was assessed against a blurred window.**

---

## §2 THE OPEN PROBLEM

**Symptom (user-observed, authoritative):** crossing a portal shows **pure black, exactly at the seam**.
Crossing slowly it is sustained and clearly visible; crossing quickly it reads as a brief flash.

### 2a. MEASURED FACTS — every one of these is log- or A/B-verified, do not re-derive

| # | fact | how |
|---|---|---|
| 1 | pure black, exactly on the seam; slow crossing = sustained, fast = flash | user |
| 2 | **shaders OFF ⇒ gone**; no pack setting affects it | user sweep (see 2b) |
| 3 | `-PdebugTintStamp` turns the whole window magenta EXCEPT the band. **INFERENCE CORRECTED 2026-07-27 (§2c′):** the tint is a MULTIPLY, so this proves *no NON-BLACK fragment survives there* — NOT "the stamp does not cover the band" | user A/B + shader read |
| 4 | **predates this session** — reproduces at `082d533` | worktree checkout; log-verified (zero `IS5-RC` lines, that class did not exist yet) |
| 5 | **not `IrisDestPrevCamera`** | `-PdisableIrisDestPrevCamera`; log-verified `isIrisDestPrevCameraActive() = INACTIVE`, zero writes |
| 6 | the aperture mesh is **never null** and **never fully dropped**; it IS partially clipped from ~1.2 blocks in | IS5-SEAM census: `meshNull=false` 23/23, `dropped=0` 23/23, `clipped=2` on 13/23 |
| 7 | **the near-plane clip is INNOCENT** | `-PdisableAperturePlaneClip`; log-verified `kept=2 clipped=0 dropped=0` on 18/18 ⇒ every triangle passed through unclipped ⇒ **black still there** |

### 2b. The pack sweep that came back empty (all under Shader Pack Settings)

Temporal Filtering (Camera → TAA Settings) · Motion Blur (Camera) · Advanced Color Tracing
(Performance) · World Blur (Camera → World Blur Settings) · Edge Shadow SSAO Quality · Block Reflection
Quality · Water Reflection Quality · Light Shaft Quality · Distant Light Bokeh. **None removed it.
Only disabling shaders entirely did.**

### 2c. THE CONTRADICTION THAT DEFINES THE NEXT STEP

Fact 6 + fact 7 say the aperture geometry **fully covers** the band — with the clip disabled, every
triangle is passed through and nothing is dropped. Fact 3 says nothing non-black is **painted** there.
Geometry covers it, yet nothing visible lands.

### 2c′. THE TINT IS MULTIPLICATIVE (instrument audit, 2026-07-27)

`portal_area_sample.fsh`: `fragColor = texelFetch(InSampler, …) * vertexColor`. Magenta {1,0,1} ×
black {0,0,0} = black. **The magenta test cannot see a fragment that paints black content.** And an
overpaint AFTER the stamp would erase magenta too. So fact 3 never separated these **three live
branches**:

- **(A)** the stamp's fragments are **depth-rejected** at the seam (the original §2d hypothesis);
- **(B)** fragments **land**, but the SAMPLED dest content (`mainRT` after the nested dest render) is
  itself **pure black** in the band — the stamp faithfully copies black;
- **(C)** fragments land and are then **overpainted** by a later writer.

### 2d. THE NEXT MEASUREMENT (BUILT 2026-07-27, this session — awaiting the live legs)

The stamp pipeline is declared **`CompareOp.GREATER_THAN_OR_EQUAL` with depth WRITE ON**
(`IrisCompatPaste` static init; the GEQUAL direction is the R5 reversed-Z convention, the write is the
`#13` two-portal fix). The deferred buffer's depth is re-cleared and re-snapshotted from `mainRT` each
frame in `IrisCompatOn262Renderer.onBeforeHandRendering`.

Two new lever-gated stamp siblings (both DEFAULT OFF, diagnostic only; selection + once-only
`IS5-RC STAMP PIPELINE` self-report in `IrisCompatPaste.selectStampPipeline`):

- **`-PdisableStampDepthTest`** — depth state fully DISABLED (`Optional.empty()`, the proven
  `PORTAL_STRAIGHT_COPY` shape; GL disables depth WRITES with the test, so this strictly contains
  `-PdisableStampDepthWrite` and wins when both are set).
- **`-PdebugStampSolid`** — fragment paints solid vColor and IGNORES the sample
  (`portal_area_solid.fsh`; WHITE, or MAGENTA when combined with `-PdebugTintStamp`) — the
  content-free paint the multiplicative tint could never be. Composes with
  `-PdisableStampDepthTest` ONLY (SOLID+NO-WRITE is deliberately not built; the once-only line
  says so when both are passed).

**Panel-hardened (the verification panel's HIGH, bytecode-verified):** `RenderPipelines.register`
is a bare map-put — it neither compiles nor validates, and every REGISTERED pipeline joins the
eager precompile set of every subsequent resource reload (an invalid one hard-fails the reload).
So the siblings are **LEVER-GATED** (a default run registers zero new pipelines) and
**COMPILE-VALIDATED before registration** (`GpuDevice.precompilePipeline(...).isValid()`); an
invalid sibling is never registered, stays null, and selection degrades to the shipped default
with a VOID warning on the IS5-RC line. "Usable=true" on that line therefore means
*lever-requested AND compiled* for the three seam siblings (`noDepthTest`/`solid`/
`solidNoDepthTest`); the pre-existing `noDepthWrite` sibling ships unconditionally from the static
block and shares the shipped default's shader, so its column is a plain null-check.

**THE ADJUDICATION MATRIX (two legs, run in this order):**

| leg | config | band turns solid | band stays black |
|---|---|---|---|
| 1 | `-PdebugStampSolid` (depth DEFAULT) | fragments pass the depth test AND survive ⇒ **(B) the sampled dest content is black** — hunt the nested dest render / what iris leaves in `mainRT` at the seam | (A) or (C) — go to leg 2 |
| 2 | `-PdebugStampSolid -PdisableStampDepthTest` | **(A) the GEQUAL test vs the snapshot depth was rejecting** (and nothing overpaints) — find why the snapshot depth wins at the seam | **(C) overpainted after the stamp** or a non-depth rejector (scissor/mask) — next tool: the one-frame `DrawCallTrace` capture |

Optional leg 3: `-PdisableStampDepthTest` alone shows the seam with REAL content when depth is off —
worth one look if leg 2 lands on (A), since it is then a candidate shape for the fix.

**Before adjudicating ANY leg: read the `RUN CONFIG` block AND the once-only
`IS5-RC STAMP PIPELINE` line — it names the pipeline actually bound, every lever, every sibling's
registration state, and prints an explicit VOID warning on any degradation. A leg with a VOID
warning is re-run, not adjudicated. The IS5-SEAM census now also prints `stamp=<name>` on every row.**

The cheap `-PdisableStampDepthWrite=true` probe (write only, compare kept) remains available but is
superseded by the matrix above.

### 2e. REFUTED — do not re-open without new evidence

the near-plane clip (fact 7, mechanically verified) · a null/skipped mesh (`meshNull=false` on **41/41**
census rows over two runs — a full-screen fallback was built for this and REMOVED when the census
refuted it; do not rebuild it) · `IrisDestPrevCamera` (fact 5) · every pack effect (2b) · anything
introduced by this session (fact 4).

---

## §3 THE KIT LEFT IN THE TREE

| lever | what | default |
|---|---|---|
| `-PdebugTintStamp` | MULTIPLY-tints the stamp MAGENTA — produced fact 3; **blind on black content (§2c′)** | OFF |
| `-PdebugStampSolid` | stamp paints SOLID vColor, ignoring the sample (white; magenta with the tint lever) — the content-free coverage discriminator | OFF |
| `-PdisableStampDepthTest` | stamp depth state fully DISABLED (test+write) — the §2d depth discriminator | OFF |
| `-PdisableAperturePlaneClip` | passes every aperture triangle through unclipped (**diagnostic only**; nominally re-opens the S14.36 sky wedges, though none appeared in the live run) | OFF |
| `-PdisableStampDepthWrite` | stamp depth-WRITE off (**diagnostic only**; re-opens the `#13` two-portal artifact; superseded by `-PdisableStampDepthTest` for the seam work) | OFF |

**IS5-SEAM census** — always on, 1 Hz, only within 3 blocks of the aperture. Prints
`distToAperture`, `meshNull`, `stamp=<pipeline actually selected>`, and the near-plane clip's
`kept/clipped/dropped` triangle counts. This is what refuted two hypotheses in one run; read it
before theorising.

---

## §4 THE STANDING RULES (unchanged, binding — and this session is the case study)

NO GUESSING / diagnose-first · read the FULL `latest.log` every run · **check the `RUN CONFIG` block
before adjudicating ANY A/B** · never generalize from one sampled block · a probe's failure sentinel is
not a measurement · **instrument every branch, not just the one your hypothesis predicts** · A/B both
directions · panels + adversarial verify + final-diff for every non-trivial mechanism ·
`.\gradlew.bat :fabric:runCrossingGametest` before every commit · push every stage commit · `git add`
explicit file lists only · Java cleanup by own-project PID only, **never `gradlew --stop`**.

### Why those rules are in force, from this session specifically
- The smear took **four rounds**; every round where I reasoned from a compelling mechanism was wrong,
  and every round where I measured first was right.
- **Three separate census defects** each would have produced a confident false verdict: an all-eight
  uniform-location gate on a pass where two are inactive; a POST replay through stale matrices; a
  process-global action slot read at emit time.
- **Two watchdogs cried VOID on healthy runs** by counting frames in which the awaited event was
  impossible (title/loading screens).
- The `RUN CONFIG` block caught **two** wrong-configuration adjudications, including one where I had
  already told the user a conclusion drawn from the wrong run.
- On the seam specifically: a full-screen fallback was designed, built, shipped to a live run, and
  **refuted by its own census in one run** — because that census logged the alternatives, not just the
  hypothesis.
