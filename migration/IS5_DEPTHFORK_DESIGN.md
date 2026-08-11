# IS5-DEPTHFORK — the window depthtex1 fork (MB-off translation ghost vs the kept MB look)

**Status: DESIGNED + JUDGED + IMPLEMENTED 2026-08-11 (2 facts + synthesis + 1 judge,
wf_279fc214-760; the judge's BLOCKING correction folded — the remap is IDENTITY).
DEV DEFAULT OFF (= PLANE, shipped behavior); `-Pis5WindowContentDepth=true` = CONTENT.
THE FORK IS THE USER'S PICK — no clean both-ways exists (measured, below).**

## §1 THE DEFECT + THE COUPLING FACT

User (2026-08-11): MB-off window content shows a translation-driven ghost copy (moves with
player translation, absent when panning, converges when still, window-only). Mechanism: the
window's stamped depthtex1 holds PLANE depth; TAA's translation reprojection is
depth-dependent, so window history is fetched where the PLANE moved, not where the CONTENT's
dest-world parallax moved. MB-on masks it inside the kept cool blur (which is itself
plane-depth velocity).

**THE COUPLING (pack-measured, file:line):** Complementary r5.8.1 reads depthtex1 for BOTH
MB velocity (`program/composite4.glsl:90`) and TAA reprojection (`program/composite6.glsl:39`
→ `taa.glsl:174`). depthtex0 feeds only TAA's edge detect; depthtex2 is read NOWHERE in the
pack. One texture, two consumers, opposite verdicts — the clean split died on this fact.

| | PLANE (default) | CONTENT (`-Pis5WindowContentDepth`) |
|---|---|---|
| MB ON | KEPT cool strong blur, byte-identical | ordinary-correct blur (cool look gone) |
| MB OFF | translation ghost (this defect) | ghost FIXED (true content parallax) |

## §2 CONTENT MODE (the judge-corrected shape)

- **IDENTITY remap (judge, BLOCKING fold):** the slot matrices are SOURCE-side arm-time
  values (arm call site + the XTRACE dCam=0 record prove it), so the captured dest depth IS
  the main-view clip depth of the virtual content — the same screen-alignment invariant that
  makes the shipped 1:1 color texelFetch correct, scaled portals included. The originally
  frozen matrix chain double-applied the portal transform and mixed bobbed/unbobbed matrices;
  it is DELETED. CONTENT stamp = `gl_FragDepth = max(texelFetch(u_captureDepth,tc).r, planeZ)`.
- **Scope:** the `stampFboDepth1` replay ONLY. depthtex0 (occlusion/ring/hand/seam) and
  depthtex2 keep PLANE in both modes; the color draw is untouched.
- **Visibility mask (occlusion-hole resolution):** GL cannot test one value and write
  another, so the CONTENT replay runs GL_ALWAYS (depth writes stay on) and replicates the
  color draw's visibility decision: sample the just-stamped depthtex0 (attached to a
  DIFFERENT fbo at that moment — legal), discard where stored < planeZ − eps (a nearer
  occluder won; its depth is preserved — no TAA ghost ON pillars). eps = 6e-8
  (format-derived ~2^-24; the judged 1e-6 band would silently content-stamp plane-hugging
  occluders). Same program/VAO/u_combined ⇒ bit-identical rasterization.
- **Never nearer than plane:** `max(dDest, planeZ)` keeps the 0.001 hand floor and the
  C4-SEAM clamp bound. Dest sky (≈1.0) stamps far ⇒ rotation-only reprojection — correct.
- **Mode-aware 1Hz readback (judge fold 3):** the d0/d1 comparator's discriminator flips in
  CONTENT (DIFF at window center = EXPECTED); the log line now names the mode semantics.
- **IS5-HIST orthogonal:** the prev-capture history stamp is unchanged; the one-frame
  depth/color age mismatch degrades to sub-pixel error under the plane clamp + clamping.

## §3 CONSUMER WALK (judge-completed; dispositions for CONTENT mode)

Fix target: composite6 TAA reprojection (content parallax correct). Fork subject:
composite4 MB velocity + its fog fix. Flips accepted-and-disclosed (opt-in mode): the
`z0==z1` reflection gates (composite.glsl:74, composite1.glsl:164) flip at window pixels —
window pixels take the "translucent-like" branch (stale source colortex7/8 reflection data
possible inside the window); GetReflection's SSR march sees the window as a depth hole for
post-stamp reflections; composite1's z1-driven volumetric light / rainbow march to content
distances inside the window (vs today's glass-wall model — arguably MORE correct); the
lava-fog z1==1.0 repaint can hit dest-sky window pixels under isEyeInWater==2 (niche);
composite3 WORLD_BLUR/DOF re-keys to content depth (plausibly desirable); FXAA-mode z1 edge
detect sees content edges; refraction flips benign-direction. TAA's z1>0.56 hand test:
strictly more-likely-pass. NeighbourhoodClamping's z0≠z1 edge term inside the window mimics
the pack's translucent heuristic ⇒ slightly reduced history weight while translating (mild
shimmer possible, anti-ghost in direction). Hardware occlusion/hand/seam: depthtex0 — 
untouched by construction. Disclosure (judge fold 5): slot.depthTex is dest depthtex0 WITH
dest translucents stamped into a no-translucents-contract texture — dest-water window pixels
key MB/TAA to the water surface; accepted.

## §4 REJECTED (do not re-litigate)

Mid-composite restamp (PLANE through composite4, CONTENT before composite6 — both looks at
once): pack-coupled pass indices, violates no-hacks; the only both-ways road, rejected
unless the user explicitly demands it. Content into depthtex0: wall-bleed catastrophe.
Hand-flag z≤0.56 stamping: ghosts on every pan — strictly worse.

## §5 KILL-CHECKS (the fork leg; lever ON)

1. MB OFF + lever ON: the translation ghost GONE (lateral strafe past parallax-rich dest);
   pan unchanged; still-convergence gone as a symptom. THE gate.
2. MB ON + lever ON: ordinary-correct blur — shown to the user as the fork's cost.
3. MB ON + lever OFF (default): the kept cool look BYTE-IDENTICAL (hard form: RenderDoc
   bitwise depthtex1 compare).
4. Ring dead both modes. 5. Hand-over-window + seam A/B row pass. 6. Ghost-double dead.
7. Pillar-over-window: no TAA ghost ON the pillar (validates the discard); wall behind the
   portal never bleeds. 8. Plane-flush dest blocks ≈ planeZ (identity sanity).
9. Dest sky through window: no MB-off smear. 10. The §3 flips: eyes on reflections/VL
   inside the window; report, don't gate (disclosed class).
