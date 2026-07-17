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

> **Weave audit passed (S13-C).** The first flag-ON launch crashed at mixin-weave time; the emergency
> S13-C audit (`port-notes/S13C-weave-audit.md`) swept **all 137 registered IP mixins across the 5 `ip-*`
> configs** against the loom **named** dev bytecode, re-anchored/neutralized the 5 weave-broken mixins
> zero-deviation, and left 0 unaudited — the registered set is now weave-clean and Part 1 can be relaunched.
> **If it still crashes at load:** because `required=true` logs **every** failed mixin apply before it
> aborts, one run surfaces the complete set — report the FULL set of any remaining
> `Mixin apply ... failed` (and `InvalidInjectionException` / `@Shadow ... NOT located`) lines from
> `latest.log`, not just the first, since they are all logged before the crash.

> **Driver core landed (S13-H).** The dest-render driver core is now in — the last inert link on the
> command→pixels chain (`port-notes/S13C-weave-audit.md` §S13-H; design contract
> `port-notes/S13H-driver-core-design.md`). BEFORE S13-H the portal window re-rendered the player's OWN
> view (the invoke was a bare `renderLevel` that re-used the already-extracted main-world state, so the
> transformed camera was consumed by nothing = visually no portal). It now renders the **transformed
> destination world** into the main target masked by the live stencil, from the portal-transformed camera,
> via the stencil-direct decomposition (extract → SOG delta feed → `compileSections` drain → armed
> `VisibleSectionDiscovery` → `renderGroup`). So Part 1's windows should show the FAR SIDE, not a copy of
> where you stand — §1.2 step 1 spells out the expected result and the first-frames compile behavior.

> **FIRST LIGHT CONFIRMED (S13-J, attempt 8 at `9870606`).** The dest view renders with correct parallax
> through a same-dim portal; **walk-through crossings, live `set_portal_scale`/rotation/destination updates,
> and break/place THROUGH the window all WORK**, and the S13-I black horizon seam is gone. The full rung-1
> triage is in `port-notes/S13C-weave-audit.md §S13-J`. Three things below are now KNOWN and pre-answered —
> read them before you re-run so you don't re-file them as bugs:
> - **§1.2 step 1 — `make_portal` is ONE-SIDED and ONE-WAY. This is EXPECTED IP behavior, not a bug** (the
>   IP citation is in step 1). A single portal is visible only from the FRONT and has NO return portal.
> - **§1.2 step 3 — a `set_portal_scale 2` crossing turns you into a permanent 2× GIANT** (high camera, "can't
>   go back down" = the giant's eye height, not a stuck position). EXPECTED IP physics; `set_portal_scale 1`
>   to un-scale.
> - **§1.2 step 3 — the "white bar" on a BURIED scaled COMMAND portal is EXPECTED IP behavior, not a failed
>   fix (S13-M correction of the earlier S13-L note).** `set_portal_scale` produces a NON-fuse portal
>   (`fuseView=0` — confirmed by the S13-M **NBT ground truth**: all 4 portals in the current test save
>   `New World (16)` are `fuseView=0`, the scale-2 one at window y-extents `[-60,-57]` = buried bottom; see
>   `port-notes/S13C-weave-audit.md §S13-M`), and the S13-L scaled-clip refinement only engages a **fuse-view**
>   scaling MODEL-VIEW
>   (`shouldApplyScaleToModelView = hasScaling && isFuseView`, `PortalRenderer:395-397`, byte-identical IP) —
>   so it is provably **INERT for every `set_portal_scale` command portal**. The residual bar the user sees is
>   the SAME "buried portal" mechanism as step 1: the opening's below-ground band shows the S13-I Row-16
>   backdrop fill where source-side blocks in FRONT of that band occlude it (source-depth occlusion, the S13-K
>   depth-competition mechanism). **DIG OUT the blocks that bury the opening and the bar clears** — this is the
>   user's own repro ("the portal is still buried after scaling and goes away after I dig out the blocks"). It
>   is byte-identical IP placement; **do NOT re-file it**. The S13-L clip fix is exercised ONLY by a
>   `fuseView=true` scaled portal (a scale-box / rendering group), NOT by `set_portal_scale` command portals.
>   (Note on geometry: `set_portal_scale` leaves the scaled portal's own crossable rectangle as placed, but
>   `complete_bi_way_portal` on a scaled portal spawns the REVERSE at `width*scale × height*scale` — a scale-2
>   3×3 gives a **6×6 reverse** (`PortalManipulation.createReversePortal:98-99`, scaling `1/scale`) — so the
>   pair is NOT "same size both sides".)
> - **§1.2 step 3 — the portal-view "window head-bob" WOBBLE is FIXED (S13-M Finding B).** The dest view
>   used to wobble slightly RELATIVE to the frame ("like the window camera has a head-bob like the player").
>   The stencil aperture, the dest CONTENT, and the frame now all bob TOGETHER: the aperture/cull/depth-restore
>   draw with the live bobbed main-pass projection (P1), captured POST-spin (P2), and the dest content's bob
>   translation is scaled by the portal's `getExtraModelViewScaling()` so content at dest eye-depth `s*z` tracks
>   the portal-plane aperture (P3). EXPECTED now: **no relative wobble between the window and the frame** on any
>   portal (scaled or not). Any residual relative sliding is a §1.3 symptom — capture it.
> - **§1.2 step 3 — on a scaled portal the dest view moves FASTER than you (this is CORRECT).** Through a
>   scale-2 portal the render camera moves 2× your displacement (the scaling factor in `Portal.transformPoint`).
>   "The view moves away when I move away / it isn't pinned to the dest spot" is correct window parallax, not a
>   bug — a pinned camera would be a static painting. See §1.2 step 1 (parallax) and the S13-L camera verdict.
> - **§1.2 step 1 — the "buried portal" / xray-through-a-hole look is byte-identical IP placement.** The portal
>   bottom row sits below the grass line as placed (`make_portal` places the frame at your location; the bottom
>   band is below the surface). The below-ground band of the opening shows the DEST world's sub-surface
>   (dest-underground backdrop) — the accepted rung-1 look, NOT a mis-placed portal. Digging a hole and looking
>   through the below-ground band is this same mechanism.
> - **§1.2 step 3/8 — dest CLOUDS are deliberately OMITTED at rung 1** (S13-J documented deviation-until-S18).
>   They were causing a deterministic "Cannot wait on a fence for the current submit" crash with multiple
>   portals; that crash is FIXED by skipping them. No clouds through the window is the accepted rung-1 look.

### 1.1 Turn the flag ON, in a THROWAWAY world

- **Enable the flag:** edit `fabric/runs/client/config/seamlessportals.properties`, add a line
  `entityPortals=true`, save, then **fully restart the client**. (If the file has no `entityPortals`
  line yet, just append it.)
- **Create a NEW dedicated test world — NEVER a main world (D3).** Ideally **superflat, creative,
  peaceful**, cheats ON. Rung 1 spawns experimental portal entities; keep them off anything you care
  about. Give yourself room and a clear sightline.

### 1.2a ATTEMPT-8 LESSON — make the destination VISIBLE (do this first!)

A **working** same-dim portal on featureless superflat is **invisible by design** (the view through
it is identical to the world behind it — attempt 7 proved this the hard way: the portal worked and
nobody could tell). Before `make_portal`, **build a distinctive marker ~20 blocks away in the shift
direction**: a tall pillar of colored wool (mixed colors, 5+ blocks high) and/or a ring of torches.
Then the moment of truth is unambiguous: **the window shows the pillar where the pillar isn't.**
Circle the portal — the pillar's parallax must track as if you were looking from 20 blocks over.

### 1.2 The rung-1 command sequence + EXPECTED result per step

Use tab-completion — the `/portal` command uses the utility-group syntax (`ducks-api-misc.md §2.3`).

1. **`/portal make_portal 3 3 minecraft:overworld shift 20`**
   EXPECTED (S13-H driver core): a **3×3 see-through window** showing the **TRANSFORMED DESTINATION** —
   the overworld as seen from the shifted viewpoint **20 blocks away**, NOT the player's own view. (Before
   the S13-H driver core landed, the window showed the player's own view = visually no portal; that is now
   the FAILURE signature, not the expected result.) **What to verify:**
   - **View parallax is correct when you circle the portal** — the far view shifts like a real window into
     an offset space (as you strafe around it, the offset scene moves with correct depth parallax); it does
     **not invert or swim**. An inverted/swimming parallax is a transform-SIGN error, not a depth error →
     §1.3 (suspect **S6** `transformPoint` vs `transformTeleportPoint` sign).
   - **Stable at all view angles, no Z-fighting.** Look from both sides.
   - **Walk up and step through — the transition is seamless** (the detailed crossing checks are step 2);
     the window you saw is the space you arrive in.
   - **First-frames compile delay is EXPECTED, not a defect:** at the instant of spawn the window may be
     briefly empty or show incomplete/holey terrain that fills in over the next few frames, as dest
     sections that were not already in the player's direct view mesh under the budgeted (**3 ms/frame**)
     compile drain. Give it a moment to settle. Holes that PERSIST after it settles are a §1.3 symptom —
     capture them.
   - **The below-ground band of the opening shows the DEST underground — EXPECTED (S13-L "buried portal").**
     `make_portal` places the frame at your location, so the portal's bottom row sits below the grass line;
     that below-ground band of the window shows the dest world's SUB-SURFACE (dest-underground backdrop), not
     a black hole and not a mis-placed portal. Placement is byte-identical IP. Digging a hole and looking
     through the below-ground band ("xray") is this same mechanism — the accepted rung-1 look.
   - **ONE-SIDED + ONE-WAY is EXPECTED (S13-J verified vs IP — do NOT file as a bug):**
     - **The window is visible only from the FRONT.** Walk BEHIND the portal and it vanishes; that is
       correct. The gate is `Portal.isRoughlyVisibleTo` → `RectangularPortalShape.roughTestVisibility`
       (`IP:…/portal/shape/RectangularPortalShape.java:161`), whose entire body is `return localPos.z() > 0`
       — a pure front-half-space test. It is NOT an angular cull, so the front view is stable at **all
       oblique angles**; our port calls the identical `isRoughlyVisibleTo`
       (`render/renderer/PortalRenderer.java:216`). (If the front view vanishes at a grazing FRONT angle,
       THAT would be a frustum-cull bug — but a plain half-space dot cannot produce it.)
     - **`make_portal` creates NO return portal.** It spawns exactly one `Portal` entity
       (`IP:PortalCommand.placePortalShift:2334` — a single `spawnServerEntity`, no reverse). "No dest portal
       to walk back through" is the designed one-way state; the return portal comes ONLY from step 4's
       `complete_bi_way_portal`. To make a portal visible from BOTH sides, use
       `/portal complete_bi_way_bi_faced_portal` (`IP:PortalManipulation.completeBiFacedPortal:124`).
   - The **§1.3 R5 sign-flip symptom table still applies** to this window — map any wrong-looking result to
     its suspected row/risk.
2. **Walk through — forward AND backward AND strafing.**
   EXPECTED: seamless reposition, **no camera snap, hand steady, sprint preserved, motion-side exits, no
   oscillation** (regression items 1, 2 — the S3-soaked anchor now drives IP's `manageTeleportation`).
3. **`/portal set_portal_scale 2`**, then **`set_portal_rotation …`**, then **`set_portal_destination …`**
   variants; then **`/portal view_portal_data`**.
   EXPECTED: the view updates **live** with each change; the `view_portal_data` NBT dump renders.
   - **SCALE-2 CROSSING MAKES YOU A PERMANENT 2× GIANT — EXPECTED IP physics, NOT a bug (S13-J).** After you
     CROSS a scale-2 portal you are permanently 2× tall: the camera now sits ~3.24 blocks above your feet
     (vs the normal ~1.62), so you feel "forced upward / floating / can't get back down." That is the
     giant's eye height, not a stuck position — you ARE grounded, just tall. The chain
     (`Portal.transformPoint` scales the eye-offset; `ScaleUtils` applies the 2× `SCALE` attribute) is a
     faithful IP port and client+server stay consistent (no desync). **To return to normal, run
     `/portal set_portal_scale 1`** (or cross a scale-1 return portal) — do NOT judge your Y-position until
     you un-scale.
   - **RE-TEST — "white bar" on a BURIED scaled COMMAND portal is EXPECTED IP behavior (S13-M verdict;
     supersedes the S13-L "FIXED / no bar" note).** On a `set_portal_scale 2` COMMAND portal the bottom band
     may still show the white/sky-colored backdrop fill — **this is EXPECTED, not a failed fix.** Why: a
     `set_portal_scale` portal is NON-fuse (`fuseView=0`), and S13-L's covector clip refinement only engages a
     **fuse-view** scaling model-view (`shouldApplyScaleToModelView = hasScaling && isFuseView`,
     `PortalRenderer:395-397`) — so S13-L is **provably INERT for command portals** (the earlier
     `enableClippingMechanism=false` A/B removed the bar because it disabled clipping *wholesale*, not because
     the scaled-clip edge was the cause on a non-fuse portal). The bar is the **buried-opening** look: the
     below-ground band shows the S13-I Row-16 backdrop fill where the source-side blocks in FRONT of it occlude
     the dest terrain. **DIG OUT the burying blocks and the bar clears** (the user's own repro — source-depth
     occlusion, the S13-K depth-competition mechanism). Do NOT re-file it as a failed S13-L fix. **The S13-L
     clip fix stands, but is exercised ONLY by a `fuseView=true` scaled portal** (scale-box / rendering group);
     confirm it there, not on `set_portal_scale`. If a bar persists on a scaled portal whose opening is FULLY
     ABOVE ground (nothing burying it), THAT would be the S13-K residual dest-frustum/discovery-EXTENT suspect
     — capture a screenshot.
   - **RE-TEST — scaled parallax is FASTER than you, and that is CORRECT (S13-L camera verdict).** As you
     move/strafe near a `set_portal_scale 2` portal, the dest view shifts **2× your displacement** (the
     scaling factor in `Portal.transformPoint`). This is the correct scaled-window parallax — the camera is
     NOT pinned to the dest portal spot (that would be a static painting), and the 2× motion is what makes it
     conspicuous on a scaled pair. It is IP-identical; do NOT file it as a bug. On an UNSCALED portal the
     parallax is 1:1 (§1.2 step 1).
4. **`/portal complete_bi_way_portal`** (point at the portal first), then cross back and forth **10×**.
   EXPECTED: a return portal appears; **no ping-pong** across the 10 crossings (regression item 1). This is
   the ONLY command that gives you the return trip after a bare `make_portal`
   (`IP:PortalManipulation.completeBiWayPortal:79` → `createReversePortal:88`); the reverse portal is spawned
   facing back through the pair (S13-J §J.1).
5. **Break/place a block THROUGH the window** (cross-portal block interaction, `block_manipulation`).
   EXPECTED: the block edit lands on the far side as seen through the window.
6. **Global portal:** create one, then **RELOG** (quit to title, reopen the world) and verify it
   **persisted** — see §1.4. **Exact syntax (S13-J, `IP:PortalCommand.registerGlobalPortalCommands:159`):**
   the global subcommands live under `/portal global …`:
   - **Simplest for this test —** make a normal portal (`make_portal`, step 1), point at it, then
     **`/portal global convert_normal_portal_to_global_portal`** (points-at the portal you're looking at;
     `IP:PortalCommand.java:343`). This writes a `GlobalPortalStorage` entry — exactly what the §1.4 RELOG
     check exercises. Reverse it with `/portal global convert_global_portal_to_normal_portal`.
   - **Vertical connecting global portal:** **`/portal global connect_floor <from_dim> <to_dim>`** or
     **`connect_ceil <from_dim> <to_dim>`** (`:250/:275`).
   - **World-wrapping global portal:** **`/portal global create_inward_wrapping <p1> <p2>`** or
     **`create_outward_wrapping <p1> <p2>`** (ColumnPos args, `:162/:180`).
   - **Inspect / clean up:** **`/portal global view_global_portals`** (`:332`),
     **`/portal global delete_global_portal`** (point at it, `:385`).
7. **renderMode smoke:** toggle the debug renderer on/off (the A1 debug-render family is present).
   EXPECTED: debug overlay appears/clears without crashing.
8. **Console watch:** no per-frame render-thread logging (regression item 11), no packet floods.
   - **MULTI-PORTAL STABILITY RE-TEST (S13-J §J.4 — the clouds fence crash is FIXED).** Spawn/have
     **multiple portals in view at once** and confirm there is NO crash. The prior deterministic
     `IllegalStateException: Cannot wait on a fence for the current submit` (`CloudRenderer.render` ←
     `MappableRingBuffer.currentBuffer`, `crash-2026-07-16_11.50.22`/`_11.58.54`) came from drawing DEST
     clouds mid-submit into the shared main-renderer ring buffer; it multiplied with each portal. Dest
     clouds are now **deliberately skipped** (documented deviation-until-S18), so multiple portals must be
     crash-free and simply show **no clouds through the windows** (accepted rung-1 look; sky is unaffected).
   - **Known non-fatal spam (already censused, S13-J §J.5):** a HIGH-severity
     `GL_INVALID_OPERATION … 'Framebuffer name must be generated before being bound.'` from
     `RendererUsingStencil.prepareRendering:173` may appear ~100×/session in `latest.log`. It is the driver
     core's own deferred runtime-verify item (the `:170-171` note) and did not block first light — do not
     re-file it, but note if it changes.

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
