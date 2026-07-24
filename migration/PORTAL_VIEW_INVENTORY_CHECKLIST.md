# PORTAL-VIEW VISUAL INVENTORY — FILM-PASS CHECKLIST (§2d, 2026-07-24)

One structured film pass to (1) triage the three known queue items with zero-code discriminators and
(2) catch the "probably more" now that the wave's absence makes smaller faults visible.
Narrate out loud as you film — your spoken observations are the primary data.

## 0. SESSION SETUP (before filming)

- Launch from the worktree:
  `.\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PshadowAliasProbe=true -PentityProbe=true`
  (shadow probe verifies the shipped fix stack is live; the NEW entity probe captures the §2b dest-entity
  funnel — 1Hz `[ENT-PROBE]` lines — while you do the entity test, both shaders ON and OFF).
- VERIFY shader settings ONCE at start (do not apply mid-session before the calibration tests are done):
  Complementary r5.8.1 selected; **Temporal Filtering (TAA) ON, Real-Time Shadows ON, Edge Shadow ON**
  (the wave-era defaults — we judge regressions against the shipped stack under normal settings).
- Vanilla **View Bobbing ON** (Options → Video Settings) — the bob test toggles it later.
- Scene props (creative): a couple of **cows/chickens** (spawn eggs) + a **stack of items to throw** for the
  entity test; a **campfire + a few torches** for the particle test. Weather clear (we'll use campfire smoke,
  not rain — easier to position).

## 1. SCENE A — SAME-DIM PORTAL (main scene)

Set up a same-dim portal with clear, distinct terrain on both sides (dest a few hundred blocks away is ideal —
distinct scenery makes source-vs-dest content obvious on film).

1. **Slow 360° pan, standing still**, a few blocks from the window. Camera: the whole window + surrounding
   terrain. Narrate anything off. (Regression anchors: no brightness WASH sweeping with yaw; no camera-tracked
   GHOST shading; no whole-screen phantom; window content stays put.)
2. **Walk-bob test (§2a)** — recon update: the dest view is DESIGNED to share the main pass's bob (IP did the
   same), so "the window bobs" alone is expected. The question is whether it bobs WRONG. Walk straight toward,
   then parallel to, the window while WATCHING THE DEST VIEW inside it. Narrate THREE things:
   - **Sync**: does the in-window content move in LOCKSTEP with the terrain around the window (one rigid
     screen = correct), or does it lag / move opposite / move MORE than the surroundings (defect)?
   - **Amplitude**: same bob strength as the main view, or exaggerated? Also narrate whether it changes as you
     get CLOSER to the portal (there's a proximity ramp on the bob factor — a suspect if anything's off).
   - Then **Options → Video Settings → View Bobbing OFF** and repeat the same walk.
     **KEY DISCRIMINATOR: does the in-window bob stop too?** (Stops ⇒ it's the designed shared bob and likely
     correct — then it's a taste call, and the IP 1.21.3 side-by-side can confirm IP looks the same.
     Persists ⇒ real defect, new mechanism.)
   - View Bobbing back ON afterward.
3. **Entity test (§2b)**: put 2 cows on the DEST side, standing clearly inside the window's view; throw a
   handful of items through so they land visibly on the dest side. Camera on the window from ~5 blocks:
   - Are the cows/items visible THROUGH the window? (Expected per the ledger: NO under sodium — confirm.)
   - Walk through, confirm they exist on the dest side, walk back, look again.
4. **Entity A/B — shaders OFF (§2b key discriminator)**: iris shader screen → disable the shaderpack
   (routes the portal to the stencil renderer live). Same look at the same cows:
   **are entities visible through the window with shaders OFF?**
   (Visible ⇒ compat-route-specific loss. Still invisible ⇒ the old sodium-general regression, both routes.)
   Re-enable the pack afterward. (I'll check the GL census covers the toggle — IS5-H should hold it clean.)
5. **Particle test (§2c)** — recon update: by the stamp's depth math, behind-plane particles CANNOT bleed
   into the window; the likely explanation for what you saw is in-front/coplanar particles — quite possibly
   the portal's OWN ambient particles hugging the plane — which is depth-correct occlusion that just looks
   like bleed. So characterize precisely:
   - **First: are the bleeding particles the portal's own effect particles** (spawning at/on the window
     itself), or from a separate source (campfire/rain/etc.)? Narrate which.
   - Campfire in the SOURCE world **clearly BEHIND the portal plane** (a few blocks past the window, off to
     the side, smoke column visually overlapping the window from your angle): does behind-plane smoke draw
     OVER the dest view? (**Per the recon this should be impossible — if you see it, film it carefully from
     two angles; that's a big finding.**)
   - Campfire BETWEEN you and the window: smoke SHOULD draw over the window (correct — narrate that it does,
     and whether it LOOKS like the bug you originally reported).
6. **Close pass**: nose up to the window, strafe left-right across it slowly. Watch the aperture EDGES
   (seams, flicker, 1px borders), the hand/held item, and the transition as you cross the plane.

## 2. SCENE B — CROSS-DIM PORTAL (nether)

Same drill, abbreviated:
1. Slow 360° pan at the window (both directions: OW→nether and nether→OW).
2. **Shadow check (§3 ledger)**: from the NETHER side looking into the OW window in daytime —
   **do OW sun shadows exist in the window at all?** (The old cross-dim "C:0/0 empty shadow map" item —
   narrate shadowed-vs-flat.)
3. Bob + entity quick-check (one cow on the dest side): same verdicts as Scene A or different?
4. Anything cross-dim-specific: sky/fog mismatch at the aperture, lighting discontinuity at the frame.

## 3. EXPECTED / ACCEPTED — do NOT report these as new bugs
- **In-window sub-pixel wobble when standing still** + fresh-per-frame (history-free) reflections INSIDE the
  aperture only — the accepted IS5-G cost.
- ~4-8 "Invalid format" GL log lines at world join — known-minor.
- Brief low-detail/pop-in of far dest chunks on a fresh portal — faithful IP graduated loading.

## 4. OPEN-ENDED (the real point of §2d)
Narrate ANYTHING that looks off, however small: water/reflections at the window, fog color banding, sky in the
window, held-item/hand artifacts, depth-fighting flicker, entity shadows, clouds, the sun/moon seen through the
window, particle behavior in the DEST view (are dest-world particles visible in the window?), sounds tied to
visuals. "It might be nothing" observations are wanted — I triage, you observe.

## 5. AFTERWARD
Leave the client up (or note the time you closed it) and tell me the run is done — I read the FULL latest.log
(GL census + [FIX-1]/[IS5-G]/[IS5-PH] liveness) before we conclude anything from the film.
