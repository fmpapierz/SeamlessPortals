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
| 3 | **the stamp does not cover the band** — `-PdebugTintStamp` turns the whole window magenta EXCEPT the band | user A/B |
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
triangle is passed through and nothing is dropped. Fact 3 says the stamp **does not paint** it.
Geometry covers it, yet no fragment lands.

⇒ **The stamp's fragments are being REJECTED, not missing.** That is a different class of defect from
everything tried so far, and it has not been tested at all.

### 2d. THE NEXT MEASUREMENT (specified, not built)

The stamp pipeline is declared **`CompareOp.GREATER_THAN_OR_EQUAL` with depth WRITE ON**
(`IrisCompatPaste` static init; the GEQUAL direction is the R5 reversed-Z convention, the write is the
`#13` two-portal fix). The deferred buffer's depth is re-cleared and re-snapshotted from `mainRT` each
frame in `IrisCompatOn262Renderer.onBeforeHandRendering`.

**Build a third pipeline sibling with the depth test DISABLED** (`Optional.empty()` depth state, the
same shape `PORTAL_STRAIGHT_COPY` already uses) behind a diagnostic lever, and select it exactly as
`PORTAL_AREA_SAMPLE_NO_DEPTH_WRITE` is selected today.

| observation | verdict |
|---|---|
| band fills in with the depth test off | **the depth test rejects the stamp there** — find why the snapshot depth wins at the seam |
| band persists | the stamp is drawn and then **overpainted afterwards** — hunt what writes after the stamp (blit-back, aperture draw, hand rendering) |

Cheaper first probe, already wired: **`-PdisableStampDepthWrite=true`** changes the depth WRITE (not the
compare). If the band's shape or extent changes at all, depth state is implicated and the full test is
worth building.

### 2e. REFUTED — do not re-open without new evidence

the near-plane clip (fact 7, mechanically verified) · a null/skipped mesh (`meshNull=false` on **41/41**
census rows over two runs — a full-screen fallback was built for this and REMOVED when the census
refuted it; do not rebuild it) · `IrisDestPrevCamera` (fact 5) · every pack effect (2b) · anything
introduced by this session (fact 4).

---

## §3 THE KIT LEFT IN THE TREE

| lever | what | default |
|---|---|---|
| `-PdebugTintStamp` | paints the stamp MAGENTA — the coverage discriminator that produced fact 3 | OFF |
| `-PdisableAperturePlaneClip` | passes every aperture triangle through unclipped (**diagnostic only**; nominally re-opens the S14.36 sky wedges, though none appeared in the live run) | OFF |
| `-PdisableStampDepthWrite` | stamp depth-WRITE off (**diagnostic only**; re-opens the `#13` two-portal artifact) | OFF |

**IS5-SEAM census** — always on, 1 Hz, only within 3 blocks of the aperture. Prints
`distToAperture`, `meshNull`, and the near-plane clip's `kept/clipped/dropped` triangle counts. This is
what refuted two hypotheses in one run; read it before theorising.

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
