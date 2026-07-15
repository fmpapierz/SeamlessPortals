# S13 — FIRST LIGHT: the USER runClient script (rung 1)

This is the hands-on test script for **S13 first light** — the first time the ported entity-portal
engine runs in a live client. It has two parts. **Part 0** is a flag-OFF baseline sanity check (proves
the closure landed inert — the old block-portal system is untouched). **Part 1** is the flag-ON rung-1
bring-up: **same-dimension command portals only**, in a throwaway world. Rung 1 deliberately isolates
the R5 reversed-Z sign flips and the driver core from the cross-dimension risks (R1 secondary-world
construction, R7 packet ordering, R9 per-dim fog) — those are S14 (rung 2).

Assembled from `EXECUTION_PLAN.md` S13(d)/(e), `port-notes/S13A-closure-sources.md §7.3`,
`spikes/SPIKE-R11-saveddata.md` (D-R11-3), and `port-notes/S12A-renderers.md`/`S11C-r3-renderers.md`
(the S13 render watch items). Nothing here changes source — it is a checklist you run against the build
this stage produced.

---

## 0. Before you launch

- **Build:** the stage tree is GREEN GATE-green on all three loaders. Launch the Fabric dev client
  (`gradlew :fabric:runClient`, or the IDE run config). Do NOT need a fresh build to switch the flag —
  the flag is read at load, so a **full client restart** is required for any flag change (it is a
  load-time switch, not a live toggle).
- **Where the log lives:** `fabric/runs/client/logs/latest.log` (and the fuller `fabric/runs/client/
  logs/debug.log`). Capture these after each part. Crash reports land in `fabric/runs/client/crash-reports/`.
- **Where the flag lives:** `fabric/runs/client/config/seamlessportals.properties`, key `entityPortals`.
  It is ABSENT by default → the flag is OFF. (The other keys — `enablePortalRendering`, `portalRenderDistance`,
  etc. — are the block-era config and are irrelevant to Part 1.)
- **Fabric only.** The flag is hard-gated to Fabric (P4): on NeoForge it force-resolves OFF regardless of
  config, so first light is a Fabric-client exercise.

---

## PART 0 — flag-OFF baseline sanity (default; run FIRST)

**Goal:** prove the flip landed inert — flag-OFF behavior must be byte-equivalent to the pre-flip mod.

1. Launch with **no `entityPortals` key** (or `entityPortals=false`) in the config. Confirm the client
   reaches the main menu with **no crash** — in particular no `IllegalStateException("…game data is
   foobar…")` from `EntityRenderers.validateRegistrations()` (the unconditional entity types now have
   unconditional renderers; if this throws, the renderer seam regressed).
2. Open your **normal existing block-portal world** (a real world is fine here — Part 0 does not spawn
   entity portals). Everything must be **UNCHANGED**:
   - Build a vanilla obsidian nether portal, light it — it forms and behaves exactly as before.
   - Cross it — the block-era seamless crossing works as it always did.
   - No new log spam, no new errors, no missing/extra entities, no render changes.
3. **What "UNCHANGED" means:** the flag-OFF gates are pure fall-throughs, so Part 0 is a regression check
   on the OLD system. Any behavior difference vs the last committed build is a defect in a `!entityPortals`
   gate (a gate that is not byte-equivalent flag-OFF is the worst defect this stage can produce). If you
   see one, capture `latest.log` and stop — do not proceed to Part 1.

Regression items to confirm flag-OFF (the sanity subset): block-portal formation, crossing, no console
errors. This is the "old system untouched" proof.

---

## PART 1 — flag-ON rung 1 (same-dimension command portals)

### 1.1 Turn the flag ON, in a THROWAWAY world

- **Enable the flag:** edit `fabric/runs/client/config/seamlessportals.properties`, add a line
  `entityPortals=true`, save, then **fully restart the client**. (If the file has no `entityPortals`
  line yet, just append it.)
- **Create a NEW dedicated test world — NEVER a main world (D3).** Ideally **superflat, creative,
  peaceful**, cheats ON. Rung 1 spawns experimental portal entities; keep them off anything you care
  about. Give yourself room and a clear sightline.

### 1.2 The rung-1 command sequence + EXPECTED result per step

Use tab-completion — the `/portal` command uses the utility-group syntax (`ducks-api-misc.md §2.3`).

1. **`/portal make_portal 3 3 minecraft:overworld shift 20`**
   EXPECTED: a **3×3 see-through window** showing terrain 20 blocks away, **stable at all view angles,
   no Z-fighting**. Walk around it and look from both sides.
2. **Walk through — forward AND backward AND strafing.**
   EXPECTED: seamless reposition, **no camera snap, hand steady, sprint preserved, motion-side exits, no
   oscillation** (regression items 1, 2 — the S3-soaked anchor now drives IP's `manageTeleportation`).
3. **`/portal set_portal_scale 2`**, then **`set_portal_rotation …`**, then **`set_portal_destination …`**
   variants; then **`/portal view_portal_data`**.
   EXPECTED: the view updates **live** with each change; the `view_portal_data` NBT dump renders.
4. **`/portal complete_bi_way_portal`**, then cross back and forth **10×**.
   EXPECTED: a return portal appears; **no ping-pong** across the 10 crossings (regression item 1).
5. **Break/place a block THROUGH the window** (cross-portal block interaction, `block_manipulation`).
   EXPECTED: the block edit lands on the far side as seen through the window.
6. **Global portal:** create one (`/portal global …` variants), then **RELOG** (quit to title, reopen the
   world) and verify it **persisted** — see §1.4.
7. **renderMode smoke:** toggle the debug renderer on/off (the A1 debug-render family is present).
   EXPECTED: debug overlay appears/clears without crashing.
8. **Console watch:** no per-frame render-thread logging (regression item 11), no packet floods.

Regression items exercised (flag-ON): 1, 2 (same-dim form), 7 (partial — `CrossPortalEntityRenderer`
absence is status quo; completes S18), 10 (partial — large portal by command), 11, 12.

### 1.3 R5 SIGN-FLIP SYMPTOM GUIDE — what a wrong depth flip looks like → which row to suspect

The R5 reversed-Z 16-row checklist (`S12A-renderers.md §2`) is the highest-risk surface at rung 1.
A depth-sign or transform-sign error shows up as one of these visual signatures. Map the symptom to the
suspect, capture a screenshot + `latest.log`, and note which step triggered it:

| Visual symptom (in the portal window) | Most likely cause → suspect |
|---|---|
| **Black / empty window** (no view of the far side at all) | View-area mesh not drawn or stencil mask wrong → **R5/R6**; the `ViewAreaRenderer.renderPortalArea` GEQUAL mesh pipeline or the stencil clear/compare (checklist rows 1–7, esp. the **Row-12 GEQUAL clobber**, §1.5) |
| **White / solid fill** where the window should be | The dest sky/fog fill drawing depth-independent over the whole window → checklist **Row 16** `replaceFrameBufferClearing` (the `portalCompositeBlit`/`COLOR_FILL` depth-OFF fill) + **R9** fog color source |
| **Portal draws IN FRONT of everything** (paints over near terrain / floats over the world) | The reversed-Z flip was MISSED — a depth compare using the old `LESS`-sense instead of `GREATER_OR_EQUAL` → **R5** (the `RendererUsingStencil` reversed-Z heart; DEFAULT depth = `GREATER_THAN_OR_EQUAL`, clear = 0.0) |
| **Inside-out / mirrored / swimming view** (parallax inverts as you move) | Transform SIGN error, not a depth error → **S6** portal-transform notes (`transformPoint` vs `transformTeleportPoint` depth-parallax sign) |
| **Window shows but the portal ENTITY is invisible** (no frame, but a hole is there) | Entity tracking-range conversion → **F14** (the entity is not being sent/rendered though its view is) |
| **Crash on spawn** (the `make_portal` command throws) | Entity-type build / NBT → **R11** (entity-type registration or NBT round-trip) |

If the window is correct and stable at all angles with clean Z, R5 is clear for rung 1.

### 1.4 Global-portal RELOG persistence check (R11 silent-loss)

After step 6, **RELOG** and confirm the global portal is still there. The R11 hazard is **silent loss**,
not a crash: a swallowed exception during the saved-data read constructs a fresh-empty storage, marks it
dirty, and the next save clobbers the old file (SPIKE-R11 D-R11-3).

- **If it comes back EMPTY**, that is the swallowed-NPE / silent-loss signature. Grep `latest.log` (and
  `debug.log`) for BOTH of these tokens — the ported `GlobalPortalStorage` saves under the id
  **`minecraft:global_portal`** (on-disk `<dim>/data/minecraft/global_portal.dat`), so the SavedDataType
  toString token is `SavedDataType[minecraft:global_portal]`:
  - **Signature A** (read/fix exception, or file corruption): `Error loading saved data: SavedDataType[minecraft:global_portal]`
  - **Signature B** (codec rejects the payload — logged WITHOUT a stacktrace, easy to miss): `Failed to parse saved data for 'SavedDataType[minecraft:global_portal]'`
- **What SHOULD happen instead:** F15's loud guard should have fired (a loud, non-silent error) rather
  than a silent fresh-empty overwrite. If the portal persists AND neither signature appears, R11 is clear.
- You can also confirm on disk: the world's `<dim>/data/minecraft/global_portal.dat` should exist and be
  non-trivial (not the ~59-63 byte fresh-empty payload the spike documented after a loss).

### 1.5 S13 render watch items (named checks — verify, don't assume)

These are the three render seams flagged at S12 as needing live rung-1 confirmation:

- **Row-12 GEQUAL clobber** (`S12A-renderers.md §6`): IP's exact-projected-depth mesh re-render needs an
  ALWAYS_PASS pipeline variant; the GEQUAL mesh pipeline can clobber the raw `glDepthFunc(GL_ALWAYS)`
  bracket. **Symptom:** a subtle depth artifact at the portal plane / the far view failing to composite
  cleanly at grazing angles. If the window is crisp at all angles, this is clear.
- **`MixinPreparedFrame` throw-path** (`S12A-renderers.md §6`, `S11C-r3-renderers.md`): `executePhase`
  RETURN-on-throw hardening. The main `PreparedFrame` throws `IllegalStateException("PreparedFrame already
  in use")` if re-entered. **Symptom:** a crash/log of that exact string during a portal-view render pass.
  Watch the log during steps 1–5.
- **`PassState` eviction** (`S11C-r3-renderers.md §2.6`, wire item 5): a discarded secondary renderer's
  per-`SubmitNodeStorage` `PassState` must be evicted at the `CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` hook.
  Rung 1 is same-dim (no secondary renderer churn), so this is mostly latent here — but watch for any
  slow memory creep across many portal recreations (`make_portal` / `set_portal_destination` repeated), and
  flag it for the S14 cross-dim rung where it is actually exercised.

### 1.6 A/B clip-mechanism switch (C4 rider)

Per the C4 auto-memory decision, BOTH clip mechanisms are kept live-switchable. Whenever you live-test
**entities through portals** (which begins in earnest at S15/S17/S18, but note it now), exercise the A/B
switch and note any difference between the two clip mechanisms. Rung 1 has no cross-portal entity render
yet (`CrossPortalEntityRenderer` is S18), so this is a reminder to carry forward, not a rung-1 step.

### 1.7 Close out

9. **Flip the flag OFF again** (`entityPortals=false` or remove the line), restart, reopen the **block-
   portal world**: it must be **UNCHANGED** (the dual-driver honesty proof — exactly one driver set ran
   in each session).

---

## What to capture and hand back

For each part, capture:
- `fabric/runs/client/logs/latest.log` (+ `debug.log` if anything looked wrong) — copy them out before
  the next restart overwrites `latest.log`.
- Any `fabric/runs/client/crash-reports/*` if the client crashed.
- A screenshot of each portal-window symptom (esp. anything in the §1.3 symptom guide).
- For step 6: the RELOG result (persisted / empty) + the §1.4 grep result for both signatures.
- A one-line PASS/FAIL per numbered step in §1.2, and which R5 row / watch item any failure maps to.

**Rollback:** the flag defaults OFF, so simply removing `entityPortals=true` restores today's behavior
wholesale. Reverting the flip commit restores the held state. Both are proven before the stage closes.
