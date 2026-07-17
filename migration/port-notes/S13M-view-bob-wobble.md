# S13-M — view-bob "window head-bob" fix (Finding B) + the "bar is expected" verdict (Finding A)

**Stage S13-M of the entity-portal migration.** Closes the two results the S13-L closeout run returned
(user, live, `enableClippingMechanism=true`, S13-L clip fix in):

- **(R1) the "white bar" on a `set_portal_scale 2` command portal PERSISTED**, clearing only after digging
  out the blocks that bury the opening → **Finding A**.
- **(R2) a NEW defect: the dest view WOBBLES relative to the frame** ("like the window camera has a head-bob
  like the player") → **Finding B**.

The full running triage is in `S13C-weave-audit.md §S13-M`; this is the standalone port-note.

Ground truth: IP source `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`; 26.2 ref
`C:/Users/warwa/ModDev/mc262-ref`. Discipline: ZERO IP-semantic deviation; sign-derived; game NOT run; NO
commit; flag-OFF (`com.warwa`) untouched.

---

## Finding A — the bar is EXPECTED IP behavior on a BURIED non-fuse command portal (DOC-only, P4)

**Verdict: no code change.** Reconciling the S13-K contradiction (the *logical* geometry is byte-identical,
yet the user reliably sees a buried window after `set_portal_scale 2`):

**NBT GROUND TRUTH (the mission's "GROUND TRUTH FIRST").** The user's newest save `New World (16)`, overworld
entities region `r.-1.0.mca` (2026-07-16 18:43) holds **4 `immersive_portals:portal` entities, ALL `fuseView=0`.**
The scale-2 portal under test (#2): origin `(-0.5,-58.5,14.5)`, `width=3 height=3`, **`scale=2`, `fuseView=0`,
`teleportChangesScale=1`**, `axisW=(-1,0,0)`→normal `(0,0,−1)`, same-dim `destination=(-0.5,-58.5,34.5)`,
`reversePortalId=#4`, window **y-extents `[-60.0,-57.0]`** ⇒ bottom row at/below the superflat surface (the
buried bottom). Its reverse #4 is on disk at **`width=6 height=6`, `scale=0.5`, `fuseView=0`** (the 6×6 the L.4
correction predicts). #1/#3 are a scale-1 non-fuse pair. So the DRAWN window is 3×3 (raw `width`/`height`, no
scale on the model-view) — the "6×6 fuse-view DRAW matrix" candidate is refuted at both the flag and the mesh.

1. **`set_portal_scale` produces a NON-fuse portal (`fuseView=0`).** So S13-L's covector clip refinement —
   which only engages when `shouldApplyScaleToModelView = hasScaling && isFuseView` scales the model-view
   (`PortalRenderer:395-397`, byte-identical IP) — is **provably INERT for every command portal**. The
   S13-L author's own RETEST CAVEAT called exactly this branch ("bar persists = narrowed to the non-modelView
   clip path"). The `enableClippingMechanism=false` A/B removed the bar only because it disabled clipping
   *wholesale*, not because the scaled-clip edge was the cause on a non-fuse portal.

2. **The bar IS the "buried portal" look.** The opening's below-ground band shows the S13-I Row-16 backdrop
   fill (`getCurrentFogColor`) where source-side blocks in FRONT of that band occlude the dest terrain
   behind the window — source-depth occlusion, the S13-K depth-competition mechanism. **Digging out the
   burying blocks clears it** (the user's own repro: *"goes away after I dig out the blocks that bury the
   portal"*). Accepted rung-1 look, byte-identical IP placement.

3. **Save-data ground truth corrects the L.4 "grows NEITHER rectangle" absolute.** `complete_bi_way_portal`
   → `createReversePortal` spawns the REVERSE at `width*scale × height*scale` with scaling `1/scale`
   (`PortalManipulation.createReversePortal:98-99,112`) — a scale-2 3×3 portal has a **6×6 reverse on disk**.
   The scaled portal itself keeps its placed rectangle; its reverse is grown. So the pair is NOT "same size
   both sides", and the "bigger after scaling" perception is real geometry on the reverse side.

**Disposition:** the S13-L docs that promise "NO white bar with clipping ON" and assert "grows NEITHER
rectangle" are corrected in `S13C-weave-audit.md` (L.4, L.6.1, §S13-M) and `S13-FIRST-LIGHT-TEST.md §1.2
step 3`, so the retest does not re-file the bar. **S13-L's clip fix STANDS** and is to be confirmed on a
`fuseView=true` scaled portal (scale-box / rendering group), NOT on `set_portal_scale`.

---

## Finding B — the view-bob window-wobble fix (CODE, P1/P2/P3)

### The IP invariant

26.2 applies BOTH view-bob and the nausea/portal spin skew to the **projection**, not the model-view
(`GameRenderer.renderLevel:535-557`):

```
projectionMatrix = new Matrix4f(cameraState.projectionMatrix);   // Pbase  (:535, kept bob-FREE)
projectionMatrix.mul(bobStack.last().pose());                    // Pbase*B (:542, B = R_hurt·T·R_z·R_x)
   ...spin rotate/scale/rotate (:547-554)  →                     // Pbase*B*SPIN
RenderSystem.setProjectionMatrix(levelProjectionMatrixBuffer.getBuffer(projectionMatrix), …); // (:557 upload)
```

`cameraRenderState.projectionMatrix` stays BOB-FREE (it is the extract-time cull base). IP's ambient
`RenderSystem.getProjectionMatrix()` was `base*bob*spin`, and IP drew the stencil aperture, the cull frustum,
the depth-restore AND the dest content with that same ambient → aperture + content + frame all bobbed
together. Our port had drifted off that in three ways.

### P1 — the aperture drew UNBOBBED → return the bobbed draw projection

`getCurrentProjectionMatrix()` returned the bob-free `cameraRenderState.projectionMatrix`, so
`RendererUsingStencil` (stencil write `:287-293`, Row-11/12 restore `:362-370`) and `PortalRenderer` (cull
frustum `:163-166`) drew the aperture with an unbobbed projection while the frame + dest content bobbed → the
aperture wobbled vs both (bob translate ~0.1 eye-units ⇒ tens of pixels at close range).

**Fix:** `getCurrentProjectionMatrix()` now returns `RenderStates.getPortalDrawProjection(base,
PortalRendering.getExtraModelViewScaling())` — the captured bobbed main-pass projection, scaled to the
current layer. Centralized in the one helper the base `PortalRenderer` exposes, so ALL renderers
(stencil / framebuffer / debug / iris) get it uniformly, matching IP's single `RenderSystem.getProjectionMatrix()`
source. Signature unchanged → no caller edits. The `ViewAreaRenderer` bracket (`:144-163`) already installs
the passed projection around the quad rasterization, so the quad now bobs; its false "NO-OP at the outer
site" comment (true only when bob==0) is corrected.

### P2 — the capture was PRE-spin → capture POST-spin at `:557`

`MixinGameRenderer` captured at the `:542` bob multiply (PRE-spin) on the FALSE premise "IP's dest excluded
the spin". IP's dest re-enters the FULL `renderLevel` (`IP:MyGameRenderer:231`; grep confirms zero spin/nausea
suppression anywhere in IP), so IP's dest is `base*bob*spin` exactly like its main pass.

**Fix:** the `@WrapOperation` target moved from `Matrix4f.mul` to
`ProjectionMatrixBuffer.getBuffer(Lorg/joml/Matrix4f;)…` **ordinal 0** at `:557` (the POST-spin upload — the
proven interception point of the block-era `GameRendererObliqueClipMixin`, which is NOT registered in any
`.mixins.json`, so there is no same-target weave conflict; the `:570` HUD upload takes a `Projection`, not a
`Matrix4f`, so the descriptor is unambiguous). In normal play `spin==0`, so this equals `base*bob` — the
normal-play wobble fix is unaffected; only the nausea/portal-overlay edge is corrected.

### P3 — the dest bob was UNSCALED → scale the bob translation by `getExtraModelViewScaling()`

IP's dest re-enters `renderLevel` under the pushed portal, so its A2 bob ModifyArg multiplies the walk-bob
translate by `viewBobFactor * getExtraModelViewScaling()` (`RenderStates.getViewBobbingOffsetMultiplier` +
`PortalRendering.getExtraModelViewScaling:113-121` — a NON-fuse scale-`s` portal IS in the product). That
`×s` bob is the exact depth-compensation: on-screen shift = `t/z`, so content at dest eye-depth `s*z` needs
bob translate `s*t` to shift by `(s*t)/(s*z) = t/z`, locking to the portal-plane aperture's `t/z`. Our
decomposition does NOT re-enter `renderLevel`, so it must synthesize this.

**Fix:** `SecondaryWorldRenderCore` derives the dest DRAW projection via
`RenderStates.getPortalDrawProjection(destProjection, PortalRendering.getExtraModelViewScaling())`, which
scales the bob TRANSLATION by `s`.

**Sign-derivation (why a column op on the composite = IP's ModifyArg on the pose):**
- The captured `Pfinal = Pbase · B · SPIN`. `SPIN` (rotate·scale·rotate, no translation) has 4th column
  `(0,0,0,1)`, so the 4th (translation) column of `B·SPIN` equals `B`'s = `R_hurt·t` — precisely IP's
  ModifyArg target (`translation of R_hurt·T·R_z·R_x is R_hurt·t`).
- `B_scaled` = `B` with translation column `×s` (IP's ModifyArg on the translate args). Because a translation
  matrix's 4th column is `(t, 1)`, the scaled column is `(s·t, 1)` — the homogeneous `w` stays 1.
- Column algebra: `col_j(Pbase·M) = Pbase·col_j(M)`. Cols 0–2 of `B_scaled·SPIN` equal `B·SPIN`'s, so cols
  0–2 of `Pdest` equal `Pfinal`'s. For col 3, with `col3(B·SPIN) = (q,1)` and `col3(B_scaled·SPIN) = (s·q,1)`:
  `col3(Pdest) = Pbase·(s·q,1) = s·(Pbase·(q,1)) + (1-s)·(Pbase·(0,1)) = s·col3(Pfinal) + (1-s)·col3(Pbase)`.
- Implementation (`RenderStates.getPortalDrawProjection`): start from `Pfinal`, and if `s != 1` set
  `m30/m31/m32/m33 = s·Pfinal.m3x + (1-s)·Pbase.m3x`. `s == 1` (non-scaling, or a fuse-view portal whose scale
  is baked into the model-view and thus EXCLUDED from `getExtraModelViewScaling`) returns `Pfinal`
  bit-unchanged → the first-light-confirmed unscaled render path is untouched.

### Coherence

`getPortalDrawProjection` is the SINGLE derivation used by both the aperture
(`getCurrentProjectionMatrix`, evaluated at the OUTER layer's scaling because the portal is pushed only
around its own content — so the stencil write + depth restore see outer scaling and MATCH each other) and the
dest content (`SecondaryWorldRenderCore`, at this-portal scaling). A nested aperture drawn into a dest pass
(Step 10.10) therefore uses the identical projection as that pass's content — pre-emptively correct for the
S18 recursion rung.

---

## Files changed (S13-M) — 5 tracked sources, ALL `qouteall.*` (flag-ON only)

| File | Change |
|---|---|
| `render/context_management/RenderStates.java` | NEW `getPortalDrawProjection(base, extraScaling)` helper; `capturedMainPassBobbedProjection` javadoc → POST-spin. |
| `render/renderer/PortalRenderer.java` | `getCurrentProjectionMatrix()` → bobbed+scaled draw projection (P1); signature unchanged. |
| `render/SecondaryWorldRenderCore.java` | dest DRAW projection via `getPortalDrawProjection` (P2 post-spin, P3 scaled). |
| `mixin/client/render/MixinGameRenderer.java` | capture `@WrapOperation` moved to POST-spin `getBuffer(Matrix4f)` upload `:557` (P2); handler ⑩ note updated. |
| `render/ViewAreaRenderer.java` | corrected the false "NO-OP at the outer site" comment (P1). |

Flag-OFF surface byte-identical: the D3 config-plugin gate skips every `qouteall.*` mixin when
`entityPortals` is OFF, and the capture site is the flag-ON `MixinGameRenderer` — no `com.warwa` change, no
`mixins.json` / AW / AT / build wiring change.

**Green gate PASS:** `:common:build`, `:fabric:build`, `:neoforge:build`, `:common:test` all BUILD
SUCCESSFUL under `ip_scc_closed=true` (the per-loader mixin AP validated the new `@WrapOperation` target
descriptor for both loaders). Logs: `scratchpad/s13m-greengate.log`, `scratchpad/s13m-shipping.log`. Game NOT
run; NO commit (orchestrator ships + commits).

## STATUS: S13-M — Finding B (view-bob window-wobble) FIXED zero-deviation (P1 aperture returns the bobbed
draw projection; P2 capture POST-spin at renderLevel:557; P3 dest bob translation ×`getExtraModelViewScaling()`,
`col3(Pdest)=s·col3(Pfinal)+(1-s)·col3(Pbase)`, `s==1` bit-identical). Finding A (the bar) = EXPECTED IP
behavior on a BURIED non-fuse `set_portal_scale` command portal (S13-L clip fix is `isFuseView`-gated → inert
for command portals; dig-out clears it = source-depth occlusion); DOC-only (P4). 5 qouteall sources changed;
flag-OFF `com.warwa` untouched; 3-loader `build` + `:common:test` green; game NOT run; NO commit.
