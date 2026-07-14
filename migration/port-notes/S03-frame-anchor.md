# S03 — LIVE substrate: R2 frame-anchor relocation + A4 upkeep re-home

Stage S3 of the entity-portal migration. MOD-OWNED code only (`com.warwa.seamlessportals.*`);
NO `qouteall/**` source is touched, so there is no held-paths / probe concern here. This is a
LIVE change to the CURRENT block-portal crossing path — it soaks under block portals from S3
through S17 and becomes the S13 flag-dispatch host.

Build gate (both modules green):

```
.\gradlew.bat :common:compileJava :fabric:compileJava --console=plain --no-daemon
> Task :common:compileJava
> Task :fabric:compileJava
BUILD SUCCESSFUL in 15s
```

---

## 1. R2 design round — the reconciliation and the decision

Two corpus prescriptions had to be reconciled:

- **portal-animation.md #14** (the frame-half injection, "highest-risk item in this slice"):
  the faithful IP anchor is **inside `Minecraft.renderFrame(Z)V`, BEFORE the `gameRenderer.update(...)`
  call** — `@At(value="INVOKE", target="GameRenderer.update(DeltaTracker)")` with no shift.
- **current-mod-render.md §2.2**: endorsed the existing `GameRendererFrameCrossingMixin` at
  `GameRenderer.update` HEAD as "the correct 26.2 translation of 'before the frame's camera is
  positioned'".

Both anchors precede camera positioning (26.2 moved `mainCamera.update` into `GameRenderer.update`;
the pre-render pump must run before it so a render-time teleport renders the crossing frame from the
destination — the seamless-crossing property, and the seam where the hand-glitch chain lived). The
distinguishing factor is **panorama/screenshot behavior** (below).

**DECISION — anchor at the `renderFrame` call-site (portal-animation #14).** It reproduces IP's
1.21.3 panorama non-firing exactly; §2.2's `GameRenderer.update`-HEAD endorsement is superseded by
the panorama-fidelity argument, which §2.2 did not weigh. The prior mixin
(`GameRendererFrameCrossingMixin`, `@Mixin(GameRenderer.class)` on `update` HEAD) is **removed**;
the pump moves to a new dedicated host **`MinecraftFramePumpMixin`** (`@Mixin(Minecraft.class)`).

Documented fallback (per plan S3(d)): if a crossing-seam regression appears in the live soak, revert
to `GameRenderer.update` HEAD (the documented alternative) and re-run the script.

---

## 2. Panorama-behavior documentation (the fidelity crux)

Verified directly against `C:/Users/warwa/ModDev/mc262-ref/net/minecraft/client/Minecraft.java`:

- **Normal frame** — `renderFrame(boolean advanceGameTime)` (`:1230`) runs, in order:
  `gameRenderer.update(deltaTracker)` (`:1290`) → `gameRenderer.extract(...)` (`:1295`) →
  `gameRenderer.render(...)` (`:1302`, where the main-world `FrameGraphBuilder` is built/executed).
- **Panorama/screenshot** — `grabPanoramixScreenshot(File)` (`:2729`) loops six cube faces and calls
  `this.gameRenderer.update(DeltaTracker.ONE)` (`:2779`) → `extract` (`:2780`) → `renderLevel`
  (`:2781`) **DIRECTLY**, entirely BYPASSING `renderFrame`.

Consequences of the two candidate anchors:

| Anchor | Fires on normal frame? | Fires during panorama? | Faithful to IP? |
|---|---|---|---|
| `GameRenderer.update` HEAD (old) | yes | **yes** — panorama calls `update` at `:2779` | NO (deviation) |
| `Minecraft.renderFrame` before `update` (new) | yes | **no** — panorama bypasses `renderFrame` | YES |

IP 1.21.3 anchored the pump at `GameRenderer.render` HEAD; the 1.21.3 panorama path likewise did not
route through that hook, so the pump did **not** fire mid-panorama. The `renderFrame` call-site is
the 26.2 fidelity-equivalent: **a crossing check never fires during a panorama capture, and the
per-frame upkeep does not run mid-panorama either** (the old renderLevel-HEAD upkeep DID run during
panorama via `:2781`, but every upkeep item is a no-op in the panorama context — no dest worlds
flushing, no pending adoptions, promote bridge inactive — so not running it is behavior-equivalent
AND more faithful to IP). Live-test item (plan S3(d)): take a panorama/screenshot near a portal — no
crash, no crossing fired mid-panorama.

---

## 3. Exact 26.2 injection target chosen + how it was confirmed

- **Host class / method:** `net.minecraft.client.Minecraft#renderFrame(boolean)` →
  descriptor `renderFrame(Z)V`. Confirmed at `Minecraft.java:1230`
  (`public void renderFrame(boolean advanceGameTime)`).
- **Injection point:** `@At(value = "INVOKE",
  target = "Lnet/minecraft/client/renderer/GameRenderer;update(Lnet/minecraft/client/DeltaTracker;)V")`,
  no shift → the callback fires immediately **before** the `gameRenderer.update` instruction.
- **Invoke target confirmed by reading source (not a bare line number):** grep of
  `Minecraft.java` shows `this.gameRenderer.update(this.deltaTracker)` at **`:1290`** is the ONLY
  `gameRenderer.update` INVOKE inside `renderFrame` (the other, `:2779`, is in
  `grabPanoramixScreenshot`, a different method — so no ordinal is needed and the panorama call is
  correctly excluded). `GameRenderer.update(DeltaTracker)` returns void; the descriptor
  `(Lnet/minecraft/client/DeltaTracker;)V` matches the `update(this.deltaTracker)` argument type.
- **No `require = 0`:** if that call site ever moves, mixin-apply fails loudly at load rather than
  silently dropping the pump (which would reintroduce the crossing-frame fog flash). This matches
  the discipline of the mixin it replaces.
- **Callback signature:** `(boolean advanceGameTime, CallbackInfo ci)` — the `renderFrame` param
  list plus `CallbackInfo`.

Files:
- `common/src/main/java/com/warwa/seamlessportals/mixin/client/MinecraftFramePumpMixin.java` (new host)
- `common/src/main/java/com/warwa/seamlessportals/mixin/client/GameRendererFrameCrossingMixin.java` (deleted)
- `common/src/main/resources/seamlessportals-common.mixins.json`
  (`client.GameRendererFrameCrossingMixin` → `client.MinecraftFramePumpMixin`)

---

## 4. A4 upkeep cargo re-homed + GPU-upload-safety reasoning

**Cargo moved** (out of `StencilPortalRenderer.prepareDestinationRender()`, the block previously at
`StencilPortalRenderer.java:194-215`, hosted by `GameRendererPortalPrepareMixin` at `renderLevel`
HEAD) into the new `StencilPortalRenderer.frameUpkeep()`, called from the relocated pre-render pump:

1. **Staged-upload flush** — `PortalWorldManager.flushDestStagedUploads()` (self-guards on
   `mc.level == null`, `PortalWorldManager.java:1813`).
2. **Adoption prune** — `PortalWorldManager.pruneEntityAdoptions()` (returns when the pending set is
   empty, `:1251`).
3. **Bridge repaint pump** — forces the SOG `needsFrustumUpdate` flag once per frame while
   `PortalContextSwitch.isPromoteBridgeActive()`, so freshly streamed chunks keep painting with a
   stationary camera (null-checks `mainRenderer`).

The FBO phase-1 dest render **stays** in `prepareDestinationRender()` at `renderLevel` HEAD — it must,
because it reads the extracted camera render-state (`gameRenderState().levelRenderState.cameraRenderState`)
that `GameRenderer.extract` populates. Only the upkeep moved. `GameRendererPortalPrepareMixin` is
therefore NOT deleted here (that is S20, with the FBO path) — it retains its valid `renderLevel`-HEAD
`@Inject` and its phase-1 job; only its cargo moved.

**GPU-upload safety — why the new position is outside the framegraph.** 26.2 made world rendering
deferred: the main-world `FrameGraphBuilder` is built and executed inside `gameRenderer.render(...)`
(`Minecraft.java:1302`). The staged-upload flush issues GL uploads; issuing them mid-pass would
resize bound GPU buffers and flash the screen (the hazard the original renderLevel-HEAD comment
called out). The new pump call site is **before `gameRenderer.update` (`:1290`)** — earlier than
`extract` (`:1295`) and earlier than `render` (`:1302`) — i.e. the earliest render-thread point in the
frame, strictly OUTSIDE any in-flight framegraph. The old renderLevel-HEAD site was already
pre-framegraph-execution but post-extract; the new site is pre-framegraph AND pre-extract, so it is
strictly earlier and equally safe. It is still on the render thread with a live GL context (the whole
`renderFrame` body does GPU work), so the flush's GL calls are valid. A4's "GPU-upload-safe outside
the framegraph" caveat is satisfied; the live soak (plan S3(d), items 8/11) is the empirical
confirmation.

Ordering within a frame is preserved: the flush still runs before the phase-1 FBO dest render (flush
at pre-update, FBO render at renderLevel HEAD), so freshly compiled dest meshes are drawable before
they are drawn — identical to the old within-`prepareDestinationRender` ordering.

---

## 5. Behavior-identity vs. anchor-timing (what changed on purpose)

Behavior is identical EXCEPT for the by-design anchor-timing shift:

- **Crossing check** (`SeamlessClientTeleport.checkCameraCrossingPerFrame()`) is called
  **unconditionally**, exactly as the old `GameRenderer.update`-HEAD site did — it self-guards on
  `player/level == null` and resets the crossing tracer (`SeamlessClientTeleport:201-204`). No new
  guard was added around it (the block-era pump has no `advanceGameTime`/`renderWorldIn` guard, and
  none was introduced — that guard is IP's and belongs to the ported IP chain that arrives at S13,
  not to the block-era pump).
- **Upkeep** is gated on `Minecraft.getInstance().level != null`, mirroring IP's pre-render-block
  guard (`MixinGameRenderer.java:78`) and preserving the old renderLevel-HEAD precondition
  (renderLevel only fires with a level present). All three items self-guard anyway, so the gate is
  a faithful precondition, not a behavior change.
- **Ordering** mirrors IP's `MixinGameRenderer.onFarBeforeRendering`: teleport/crossing management
  FIRST, then upload/upkeep — the same relative order as the old two-site arrangement (crossing at
  `update` HEAD ran before upkeep at the later `renderLevel` HEAD).
- **The intended change:** the pump now precedes camera positioning + extract even on the panorama
  boundary; the crossing check no longer fires mid-panorama (the correctness/fidelity improvement).
- Nothing numerically changes client rotation or zeroes velocity at a crossing (memory
  `teleport-hand-glitch-chain`) — the pump body is byte-for-byte the same calls, only relocated.

---

## 6. Shared-host structuring for S13 (D3)

`MinecraftFramePumpMixin` is the MOD-OWNED shared host that becomes the S13 flag-dispatch point.
The injected `seamlessportals$preRenderPump` body keeps the block-era pump as a **single ordered
unit** (crossing check, then gated upkeep) so that at S13 the flag wraps it without restructuring:

```java
if (entityPortals) {
    // ported IP pre-render chain: RenderStates.updatePreRenderInfo -> StableClientTimer.update
    //   -> ClientPortalAnimationManagement.update -> ClientTeleportationManager.manageTeleportation(false)
    //   -> earlyRemoteUpload   (held/ported code; MixinGameRenderer.java:86-96 analog)
} else {
    // the block-era pump exactly as it stands today
    SeamlessClientTeleport.checkCameraCrossingPerFrame();
    if (level != null) StencilPortalRenderer.frameUpkeep();
}
```

Per the D3 shared-host rule: the mod code dispatches on the flag; IP-ported bodies live in their own
branch and are never flag-polluted (zero-deviation preserved). No flag is added now — none exists
until S13. The anchor accumulates real soak from S3 to S17, so the S13 dispatch point is pre-proven.
