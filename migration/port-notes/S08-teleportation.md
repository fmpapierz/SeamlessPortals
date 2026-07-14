# S8 — Teleportation + collision (U6) — port-note

**Stage:** S8 (EXECUTION_PLAN §3 S8). **Effort:** L — the crossing seam (client-first flow +
server authority) plus four owned design rounds (R8 packet dim-stamp, R12 vanilla-copy
re-derivations, R13a ender-pearl intercept, R13b obsolescence audit).
**Unit:** U6 — `imm_ptl/core/teleportation/{TeleportationUtil, CrossPortalSound,
ClientTeleportationManager, ServerTeleportationManager}`. The collision trio
(`CollisionHelper`, `PortalCollisionHandler`, `PortalCollisionEntry`) was **co-ported at S6**
(cycle 15) and is only REVIEWED alongside this unit per the U6 row note — it is not re-ported here.
**Discipline:** D2 verbatim `qouteall.*` paths · D4.2 probe-ledger triage (the probe, not the paper
ledger, is authoritative — the S5/S6 lesson) · D4.3 source-diff gate · **R12/F17 rule: every
`@IPVanillaCopy` in the collision/teleport path is RE-DERIVED LINE-BY-LINE from 26.2 source, never
patched from the 1.21.3 copy** · §S8(a) contents · §S8(b) forward-ref debt union. All **4 U6 files
land HELD** (no carve-in change — `IpHeldPaths` already covers `imm_ptl/core/**`), unregistered
until S13. No mixins are written this stage — R8/R12/R13a/R13b are DESIGN rounds whose mixin
IMPLEMENTATIONS land at S10 (R8 lock-step pair, R12 vanilla-copy cohort, R13a ender-pearl).
**IP source root (1.21.3, Mojang mappings):** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`
**26.2 evidence root (authoritative):** `C:/Users/warwa/ModDev/mc262-ref`
**Memory lessons honoured:** `backward-crossing-motion-keyed-exit` (§3), `teleport-hand-glitch-chain`
(§3), `post-crossing-stutter-entity-move-flood` (§4), `entity-vanish-cooldown-mirror-gate` (§3.5),
`respawn-mislabel-phantom-blocks` (§6 R8 ordering).
**API-maps amended by this stage:** `migration/api-map/teleportation-collision.md` — two GONE-section
amendments, applied to the file and recorded in §11: (a) NEW `ServerLevel.getSharedSpawnPos()` row;
(b) profiler row extended to `Minecraft.getProfiler()` + the two managers' sites. Tally bumped
7→8 GONE.

This note is the **S8 commit-3 deliverable**. It consolidates the three working fragments
(`fragments/S08-server.md` + `migration/fragments/S08-{client,util}.md`, deleted at stage end) plus
the probe/verify evidence measured this pass.

---

## 0. Stage result (build + probe + test evidence)

| Gate | Command | Result |
|---|---|---|
| Shipping build (D4.1) | `:common:compileJava :fabric:compileJava` (`ip_scc_closed=false`) | **BUILD SUCCESSFUL** — all 4 U6 files held (path `imm_ptl/core/teleportation/**`, no carve-in), invisible to javac; `:fabric` uses the identical MAIN held-list wiring, stays green |
| Compile probe (D4.2) | `:common:compileJava -Pip_scc_closed=true` (`-Xmaxerrs 10000`) | **BUILD FAILED (expected)** — 520 whole-tree errors. Per-U6-file: **TeleportationUtil 0**, CrossPortalSound 8, ClientTeleportationManager 29, ServerTeleportationManager 6. Every U6 error reduces to the documented S8(b) forward-ref union + established loader-facade/DimLib external debt (§12). **Zero translation slips on the U6 slice** (no `method location()`, no `incompatible types`, no `cannot be applied`, no bad-arity on any U6 file). |
| Math harness (D4.5) | `:common:test` | **BUILD SUCCESSFUL** — the S2 authored DQuaternion/Plane invariant tests stay green; S8 adds no test payload and the carried Mesh2DTest/HelperTest remain held via the test-task list until S13. |

The probe error list is the AUTHORITATIVE ledger (D4.2). It was diffed against the paper S8(b)
ledger this pass; the two external-dependency categories the paper "cross-unit forward-ref" wording
did not enumerate (fabric-api `ServerTickEvents`, DimLib `DimensionAPI`) are **established debt**
carried verbatim from IP and already present in this file's S4/S6/S7-committed siblings — recorded
as governed ledger entries in §12, not translation slips.

---

## 1. Files ported (4) + per-file diff-gate summary

`git diff --no-index` vs the IP original, byte-fidelity method (IP file copied byte-for-byte —
CRLF/LF and trailing-whitespace-on-blank-lines preserved to match the S6/S7 held convention — then
only the enumerated hunks applied by literal string replacement):

| File | Diff | Character |
|---|---|---|
| `TeleportationUtil.java` | **ZERO diff (byte-identical, exit 0)** | No 26.2 translation of any kind — every vanilla API it touches is SAME in 26.2. The strongest fidelity outcome; the diff-gate itself is the proof. IP's own typo `otherSideOffse` (unused `PortalPointOffset` record, IP:87) preserved verbatim. |
| `CrossPortalSound.java` | 3 hunks (`+3 −2`) | One translation only: `ClientLevel.getProfiler()`→`Profiler.get()` (import + 2 push/pop sites). §2. |
| `ClientTeleportationManager.java` | 15 hunks (`+20 −24`) | Imports (5) + body (10): profiler ×4, C2S send seam, two duck deletes, particle setLevel, startRiding 3-arg, `.location()`→`.identifier()` ×3, getNormal→getUnitVec3i, updateEntityPos re-derivation. §2, §4. |
| `ServerTeleportationManager.java` | ~18 hunks (`+28 −26`) | `getServer()`→`level().getServer()`, `.location()`→`.identifier()` (9×), profiler, `canChangeDimensions`→`canTeleport`, `startRiding` 3-arg, `create(Level)`→`create(Level,SpawnReason)`, `moveTo`→`snapTo`, `getSharedSpawnPos()`→`getRespawnData().pos()`. §2. |

**Note — ClientTeleportationManager stat correction:** the S08-client working fragment quoted
"17 insertions, 21 deletions". The authoritative `git diff --no-index` count is **20 insertions,
24 deletions** (the H10 `updateEntityPos` re-derivation alone is 6 insertions / 7 deletions, larger
than the fragment's naive tally). Every one of the 20+24 changed lines maps to an enumerated hunk
below — no undocumented hunk; the fragment's summary integer was simply stale.

---

## 2. Diff-gate translation record (every hunk → owning api-map row)

Citations are `migration/api-map/teleportation-collision.md`. 26.2 line numbers re-verified against
`mc262-ref` this pass. IP line numbers are the IP original's.

### 2.1 `CrossPortalSound.java` (3 hunks — GONE profiler row)

| IP | Port | Basis |
|---|---|---|
| import | `+import net.minecraft.util.profiling.Profiler;` (sorted after `RandomSource`) | GONE profiler row |
| :46 | `soundWorld.getProfiler().push("cross_portal_sound")` → `Profiler.get().push(...)` | GONE profiler row (S6 `CollisionHelper` made the identical edit) |
| :89 | `soundWorld.getProfiler().pop()` → `Profiler.get().pop()` | GONE profiler row |

`Profiler.get()` (Profiler.java:47) → `ProfilerFiller` with `push`/`pop` (ProfilerFiller.java:13,17).
Thread-local — discards IP's `soundWorld` receiver (only a path to `getProfiler()`); on the
client render/main thread it returns the active profiler vanilla is pushing into. Everything else in
the file is SAME: the 8-arg `SimpleSoundInstance` ctor (SimpleSoundInstance.java:92 — inventory's
"9-arg" label was wrong, verify R3), `RandomSource.create(long)`, `ClientLevel.dimension()`, `Vec3`.
`@Environment(EnvType.CLIENT)` stays verbatim (loader-facade debt).

### 2.2 `ClientTeleportationManager.java` (15 hunks)

Imports: `−ClientPlayNetworking` (F3 send seam), `+ServerboundCustomPayloadPacket` (F3 send seam),
`+Profiler` (GONE profiler row), `+InterpolationHandler` (R12 interp-kill), `−IEAbstractClientPlayer`
(GONE `clientLevel` row), `−IEParticleManager` (CHANGED particle row). `@Environment` +
`ClientboundRespawnPacket` (javadoc-only) KEPT verbatim.

| IP:line | Port | Basis |
|---|---|---|
| :142,:202,:294,:296 | `client.getProfiler().push/pop` → `Profiler.get().push/pop` ×4 | GONE profiler row — `Minecraft.getProfiler()` is the same thread-local mechanism and is likewise gone (§11 amend b) |
| :372 | `player.connection.send(ClientPlayNetworking.createC2SPacket(new TeleportPacket(...)))` → `send(new ServerboundCustomPayloadPacket(new TeleportPacket(...)))` | FABRIC-API §4 + F3 (`createC2SPacket` GONE); `TeleportPacket implements CustomPacketPayload`; matches S7 `ImplRemoteProcedureCall` C2S convention |
| :433 | `toDimension.location()` → `.identifier()` | recurring rename (probe-caught on first pass, then fixed) |
| :483 | DELETE `((IEAbstractClientPlayer) player).ip_setClientLevel(toWorld);` | GONE `AbstractClientPlayer.clientLevel` row — field does not exist; `ip_setWorld` (Entity-level) covers it |
| :496 | `((IEParticleManager) client.particleEngine).ip_setWorld(toWorld)` → `client.particleEngine.setLevel(toWorld)` (null-guard kept) | CHANGED IEParticleManager row — public `ParticleEngine.setLevel(@Nullable ClientLevel)` (:131); particles-stay-alive preserved |
| :499 | DELETE `client.getBlockEntityRenderDispatcher().setLevel(toWorld);` | GONE row — `BlockEntityRenderDispatcher` has no `setLevel`; mandatory (real vanilla-symbol error otherwise) |
| :513 | `player.startRiding(vehicle, true)` → `startRiding(vehicle, true, false)` | CHANGED startRiding row — 2-arg overload GONE; `(true,false)` = force + no events, matching vanilla dim-travel re-seat (Entity.java:3093) |
| :515-516 | `from/toDimension.location()` → `.identifier()` ×2 | recurring rename |
| :637 | `Vec3.atLowerCornerOf(levitationDir.getNormal())` → `...getUnitVec3i()` | CHANGED Direction.getNormal row (Direction.java:375). NB `portal.getNormal()` (:406) is a **Portal** method — kept verbatim |
| :713-717 | `updateEntityPos` body: `setPos + lerpTo(pos,yRot,xRot,0) + setPos` → `getPositionCodec().setBase + snapTo + getInterpolation().cancel()` | GONE lerpTo row + CHANGED updateEntityPos row — full re-derivation §4 |

KEPT VERBATIM despite touching GONE rows (design-deferred to S10, §5): the two render-swap duck
calls `gameRenderer.ip_setLightmapTextureManager(...)` (:485-487) and
`((IEMinecraftClient) client).ip_setWorldRenderer(...)` (:490-492). `client.level = toWorld` (:489)
and `client.getConnection()` (:471) kept — SAME.

### 2.3 `ServerTeleportationManager.java`

Imports: `+Profiler` (GONE profiler row), `+EntitySpawnReason` (CHANGED create row).

| IP:line(s) | IP → port | Basis |
|---|---|---|
| :72,:137,:626,:755 | `portal/entity.getServer()` → `...level().getServer()` | recurring rename (`Entity.getServer()` GONE) |
| :121 | `canChangeDimensions(from,to)` → `canTeleport(from,to)` | CHANGED row (Entity.java:3194) |
| :171,:334,:380,:729,:793 | `player.server`/`serverPlayer.server` → `...level().getServer()` | CHANGED row (`ServerPlayer.server` now private final, :232) |
| :176,:217,:235,:375,:385,:470,:472,:635,:841 | `<ResourceKey<Level>>.location()` (9×) | `.identifier()` recurring rename (all sites are `ResourceKey<Level>`, grep-confirmed) |
| :335,:357 | `server.getProfiler().push/pop` → `Profiler.get().push/pop` | GONE profiler row (`MinecraftServer.getProfiler()`; local `server` var retained for `getLevel`) |
| :557 | `e.startRiding(newEntity, true)` → `startRiding(newEntity, true, false)` | CHANGED startRiding row |
| :645,:693 | `entity.getType().create(toWorld)` → `create(toWorld, EntitySpawnReason.DIMENSION_TRAVEL)` | CHANGED create row (matches vanilla recreate, Entity.java:3081) |
| :744 | `entity.moveTo(x,y,z,yRot,xRot)` → `entity.snapTo(...)` | CHANGED/rename `moveTo`→`snapTo` (5-arg :1794). `chaser.getNavigation().moveTo` at :818 is `PathNavigation.moveTo` — SAME, untouched |
| :834 | `overWorld.getSharedSpawnPos()` → `overWorld.getRespawnData().pos()` | NEW GONE row (§11 amend a), re-derived from 26.2 |

The exit-posture method `getRegularEntityTeleportedEyePos` (IP:585-607) has **zero diff** — it is
`Vec3`-only and byte-identical to IP (see §3.3). The RPC address string
`"qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos"`
(ported :572, IP :570) is **byte-exact** — wire protocol, D2 verbatim retention.

---

## 3. Sign / posture derivations (D4.4 + memory lessons)

`ClientTeleportationManager`/`ServerTeleportationManager` do NOT compute the crossing transforms —
they consume the `Teleportation` record whose transform signs are derived in `TeleportationUtil`.
The upstream held classes (`PortalState`, `UnilateralPortalState`) are JOML **column-form**
(`orientationMatrix.transform(v) = M·v`; the `IP_DEVIATIONS` row-vector note is WRONG — memory
`migration-research-corpus`); these files only CALL them, so column-form is correct upstream.

### 3.1 TeleportationUtil geometry (byte-identical — re-derived so nothing was silently flipped)

Two transform families kept strictly distinct (a swap here is the classic depth-sign bug):
- **POINT** carries the origin translation: `transformPoint(p) = toPos + scaling·R·(p − fromPos)`
  (+mirror branch); used for POSITIONS.
- **VECTOR** drops the translation: `transformVec = scaling·R·v` (+mirror); used for NORMALS and
  VELOCITIES. `worldSurfaceNormal` uses `transformVecLocalToGlobal` — correct (a normal has no
  position).

- **`getPortalPointVelocity`**: this-side / other-side velocity = `current − last` (forward-time
  displacement, blocks/tick); no negation.
- **`transformEntityVelocity`**: Galilean frame change through a MOVING portal —
  `v_rel = worldVel − thisSidePointVel` → transform → `+ otherSidePointVel`. **Subtract this-side,
  add other-side** — the exit-velocity recompute the motion-keyed-exit lesson requires; never keyed
  to yaw. Portal transform = `transformVelocityRelativeToPortal` (`transformLocalVec`, clamp 15,
  minecart result²<0.5 doubled).
- **`checkStaticTeleportation`**: crossing detected in PORTAL-LOCAL coords (local Z = signed distance
  along the normal); `worldHitPos`/`worldSurfaceNormal` via POINT/VECTOR local-to-global; exit tick
  eyes = `portal.transformPoint({last,this}TickEyePos)` (POINT, current state); checkpoint =
  `portal.transformPoint(worldHitPos)`; `PortalPointVelocity.ZERO` (static = no point vel).
- **`checkDynamicTeleportation`** (the moving-portal exit-posture recompute): each endpoint in its
  OWN frame's local coords; `worldPosToPortalLocalPos` sets Z = `offset · (axisW × axisH)` —
  **cross-product order W×H** fixes the "which side" sign; H×W would invert crossing detection.
  Camera-continuity correction offset = `collisionPortalState.transformPoint(currentFrameEyePos) −
  lerp(newLastTick,newThisTick,partialTicks)`, applied to BOTH endpoints (preserves inter-tick
  velocity).

### 3.2 The planar exit guard (SIGN CRITICAL — the entity-vanish landing defect)

Three "not behind destination" corrections push the exit off the dest BACK-side onto the FRONT:
`getContentDirection() = rotation.rotate(getNormal().scale(-1))` = the **NEGATED** normal rotated →
points INTO the destination. For each of {end-tick, end-frame, last-frame}:
`dot = (pos − state.toPos) · getContentDirection()`; if `dot < 0.00001` push by
`contentDirection · (max(−dot,0) + ε)` with ε = `0.00001, 0.00001, 0.001` (last-frame larger because
"the portal destination may move backwards"). **Dropping the `.scale(-1)` in `getContentDirection`,
or using `+getNormal()` in the test, would push exits BEHIND the frame wall** — reproducing the
`entity-vanish-cooldown-mirror-gate` landing defect (~59/124 items burned). The exit side is keyed to
the destination CONTENT DIRECTION (a depth sign), never to yaw — the motion-keyed-exit invariant.

### 3.3 Regular-entity server exit posture is motion-keyed, not yaw-keyed (`backward-crossing-motion-keyed-exit`)

`getRegularEntityTeleportedEyePos` (IP:585-607, ported byte-identical) recomputes the exit point from
the entity's OWN eye-motion segment:
- `deltaMovement = eyePosThisTick.subtract(eyePosLastTick)` → travel vector (this − last).
- `deltaMovementDirection = deltaMovement.normalize()` → unit travel direction (keys the exit, NOT
  facing/yaw).
- ray-trace from `eyePosThisTick − dir·5` (UPSTREAM back-cast start) to `eyePosThisTick + dir·1`
  (DOWNSTREAM end) → the portal-plane crossing point; `null` ⇒ fall back to `eyePosLastTick`.
- `result = portal.transformPoint(collidingPoint) + dir·0.05` (DOWNSTREAM, just past the exit plane).
- Velocity carried by `TeleportationUtil.transformEntityVelocity(portal, entity, ZERO, oldPos)`
  (portal-relative), never a yaw-derived value.
Signs byte-identical: back-cast `−dir·5`, forward end `+dir·1`, exit nudge `+dir·0.05`.

### 3.4 Player crossing writes no rotation and zeroes no velocity (`teleport-hand-glitch-chain`)

- `teleportPlayer` (:356-357): `McHelper.setEyePos(player, newThisTickEyePos, newLastTickEyePos)`
  sets BOTH endpoints from the transformed pair → **preserves the transformed inter-tick delta**
  (destination-frame motion continuous across the crossing); no velocity numerically zeroed; never
  writes `yRot`/`xRot`.
- Rotation is velocity-NEUTRAL by capture-restore (:359-361): `oldRealVelocity =
  getWorldVelocity(player)` → `TransformationManager.managePlayerRotationAndChangeGravity(portal)`
  (S11 fwd-ref — the ONLY rotation authority on the path) → `setWorldVelocity(player,
  oldRealVelocity)`. The rotation/gravity change alone can NEVER alter world velocity. The real
  velocity transform is the SINGLE explicit `transformEntityVelocity`.
- Combo-reject eject (:175-183): both signs `−(worldSurfaceNormal)` — push 0.001 back onto the SOURCE
  side + `−0.1` velocity AWAY from the portal (the fractal-scale-box escape guard). Verbatim.
- Static-crossing checkpoint nudge (:301-307): `adjustment = respectParallelOrientedPortal() ?
  −0.001 : 0.001` along `newThisTickEyePos − newLastTickEyePos`. Verbatim.
- `forceTeleportPlayer` re-sends with the CURRENT (unchanged) `player.getYRot()/getXRot()`, never a
  recomputed/wrapped one. No `setDeltaMovement(ZERO)` anywhere on the player path.

**Invariant check:** every position/velocity write in the manager slice is (a) a consumed rigid
transform from `TeleportationUtil`, (b) a capture-restore, or (c) a signed guard-nudge along the
transformed delta / source normal. No write is keyed to yaw; no crossing zeroes velocity.

---

## 4. R12 interpolation-kill re-derivation — `RemoteCallables.updateEntityPos` (F17)

IP 1.21.3 body (comment: *"living entities do position interpolation; it may interpolate into
unloaded chunks and stuck; avoid position interpolation"*):
```java
entity.setPos(pos);
entity.lerpTo(pos.x, pos.y, pos.z, entity.getYRot(), entity.getXRot(), 0);  // steps=0 = snap + kill interp
entity.setPos(pos);
```
`Entity.lerpTo` is GONE (mc262-ref: zero hits). **Re-derived line-by-line from the vanilla
authoritative-position apply `ClientPacketListener.handleEntityPositionSync` (:640-664)** — 26.2's
own `lerpTo(...,0)` decomposition (NOT patched from the 1.21.3 copy):
```java
// both of them are important for Minecart
entity.getPositionCodec().setBase(pos);
entity.snapTo(pos, entity.getYRot(), entity.getXRot());
InterpolationHandler interpolation = entity.getInterpolation();
if (interpolation != null) {
    interpolation.cancel();
}
```
Primitive-by-primitive (all mc262-ref-verified):
- `getPositionCodec().setBase(pos)` — `Entity.getPositionCodec()` :359 → `VecDeltaCodec.setBase(Vec3)`
  :47. **Required, not optional:** without re-basing the codec the mod's ServerEntity-style movement
  dedup (`post-crossing-stutter-entity-move-flood`) and vanilla delta packets drift after the
  force-position. Vanilla's own `handleEntityPositionSync` calls `setBase` explicitly BEFORE `snapTo`
  (:645), proving the decomposition; the IP "important for Minecart" comment now reads onto `setBase`
  (codec/dedup) + `snapTo` (hard position).
- `snapTo(pos, getYRot(), getXRot())` — `Entity.snapTo(Vec3,float,float)` :1790; keeps CURRENT
  rotation (position-only update, as IP's `lerpTo(...,getYRot(),getXRot(),...)`).
- `getInterpolation().cancel()` (null-guarded) — `getInterpolation()` @Nullable :2550 →
  `InterpolationHandler.cancel()` :111 (the interpolation-kill, the whole point of `steps=0`).

**Fidelity decision — NO `isLocalInstanceAuthoritative()` gate (behavior-review flag for S10/verify).**
Vanilla's `handleEntityPositionSync` gates `snapTo` on `!isLocalInstanceAuthoritative()` (:646). IP's
`updateEntityPos` is UNCONDITIONAL — it deliberately force-positions remote cross-portal entities to
keep them out of unloaded chunks. Zero-deviation ⇒ port IP's unconditional force-position (no gate).
`setBase` is applied unconditionally (vanilla also `setBase`s unconditionally, and it is required for
dedup). FLAG (CHANGED updateEntityPos row): for a client-authoritative vehicle unconditional `snapTo`
could fight client authority — near-moot here (`updateEntityPos` targets REMOTE entities resolved via
`ClientWorldLoader.getWorld(dim).getEntity(entityId)`, never the local player) but recorded for the
S10/S17 verify pass. NB the fragment §4 "synthesized vanilla body" omitted vanilla's own gate /
`moveOrInterpolateTo` branch — it is a primitive-decomposition (each of setBase/snapTo/cancel is
api-map-backed), **not** a verbatim copy of `:640-664`; the three primitives individually verify.

**S10 R12 lock:** the S10 entity-sync mixin cohort (`EntitySync`, the ServerEntity-style dedup
broadcast, `MixinServerGamePacketListenerImpl` position sync, and the `setPosRaw` `@IPVanillaCopy`
re-copy — now omitting THREE callbacks: `levelCallback.onMove()` + both waypoint branches) must
re-derive the interp-kill against THIS decomposition and share the identical `VecDeltaCodec` base
assumption, so a portal force-position and the dedup agree on the codec base.

---

## 5. `changePlayerDimension` world-swap — the OWNED partition (definitive-now vs S10-deferred)

The client world/renderer swap is api-map top-risk and architecturally an S10 `ClientWorldLoader`
concern (R1 render-split re-derived INTO `ClientWorldLoader` from `PortalContextSwitch`). This file
CONTAINS the swap; S8 lands it compile-honest without pre-empting the S10 renderer-model decision.
Partition rule: **apply the api-map rows with a DEFINITIVE 26.2 replacement and no design choice;
keep verbatim the rows the api-map itself DEFERS to the render slice.**

| IP step (:line) | 26.2 fact | Treatment | Why |
|---|---|---|---|
| `ip_setClientLevel` (483) | `AbstractClientPlayer.clientLevel` field DOES NOT EXIST; redundant with `ip_setWorld` | **DELETE (H6)** | GONE row definitive — no field, not `@Mutable`-able; zero design choice |
| `particleEngine.ip_setWorld` (496) | public `ParticleEngine.setLevel` exists, design-free | **→ `setLevel` (H7)** | CHANGED row definitive |
| `getBlockEntityRenderDispatcher().setLevel` (499) | method GONE; dispatcher tracks nothing | **DELETE (H8)** | GONE row definitive (+ mandatory — real compile error) |
| `ip_setLightmapTextureManager` (485-487) | GameRenderer owns ONE lightmap; no per-dim lightmap | **VERBATIM (defer)** | GONE row: *"render-slice design question"* — per-dim secondary lightmaps is S10/S11's call |
| `ip_setWorldRenderer` (490-492) | `Minecraft.levelRenderer` is `public final`; IP per-dim reassign has no direct form | **VERBATIM (defer)** | GONE row: *"UNKNOWN-NEEDS-DESIGN"* — `@Mutable` the final field OR single-renderer + extractor-swap (`nether-block-freeze-orphaned-extractor`); S10 reconciles |
| `client.level = toWorld` (489) | `Minecraft.level` mutable | VERBATIM | SAME |

Keeping the two duck calls verbatim = maximal IP fidelity AND it lets S10 decide the mixin BODY
without an S8-file edit (if S10 keeps IP's model it `@Mutable`-writes `levelRenderer`; if single-
renderer it makes `ip_setWorldRenderer` a no-op / extractor re-point — either way the CALL stays).
The two DELETEs reference members 26.2 removed outright, so no S10 rework can want them back. **S12
should drop the now-dead `ip_setClientLevel` from the `IEAbstractClientPlayer` duck** (only call site
gone).

---

## 6. R8 DESIGN DECISION (F9) — position-packet dimension stamp (owning stage; impl S10 lock-step)

**What R8 is:** IP appends the player's dimension (`ResourceKey<Level>`) to the wire form of the S2C
position packet so the client's authoritative-position apply knows WHICH dimension the position
belongs to during the no-respawn cross-dim crossing. **26.2 reality — BOTH IP anchors are GONE:**
`ClientboundPlayerPositionPacket` is now a record `(int id, PositionMoveRotation change, Set<Relative>
relatives)` serialized ENTIRELY by a composite `STREAM_CODEC` built once in `<clinit>`
(ClientboundPlayerPositionPacket.java:13) — there is no `write(FriendlyByteBuf)` method and no
`<init>(FriendlyByteBuf)` ctor. `FriendlyByteBuf.writeResourceKey`/`readResourceKey` still exist.

### Option (a) — CODEC-WRAP ✅ RECOMMENDED

`@ModifyExpressionValue` (MixinExtras) on the sole `StreamCodec.composite(...)` call in `<clinit>`
(exactly ONE, unambiguous). Wrap the returned composite in a delegating `StreamCodec`:
- `encode(buf, pkt)` → `original.encode(buf, pkt); buf.writeResourceKey(((IE)pkt).ip_getDim());`
- `decode(buf)` → `var pkt = original.decode(buf); pkt.ip_setDim(buf.readResourceKey(Registries.DIMENSION)); return pkt;`
- Per-instance dim: `@Unique ResourceKey<Level> ip_dim` + an `IEClientboundPlayerPositionPacket` duck.
  Server stamps `ip_setDim(player.level().dimension())` at the send site (the S10
  `MixinServerGamePacketListenerImpl.teleport` re-copy, right after `ClientboundPlayerPositionPacket.of(...)`).

Why (a): **faithful** (IP extends THIS packet's own serialization; codec-wrap is the only 26.2 way);
**atomic + race-free** (dim rides the same byte stream as the position — no id-correlation table, no
arrival-order race, no per-teleport double packet; does NOT reintroduce the netty pre-pass / re-queue
hazards of `respawn-mislabel-phantom-blocks` / SPIKE-R7); **26.2 symmetry bonus** (`STREAM_CODEC` is
ONE static field for BOTH encode+decode, so a SINGLE common-side `<clinit>` mixin covers both wire
directions — simpler than IP's split); **minimal surface** (one `@ModifyExpressionValue` + one
`@Unique` + one duck; MixinExtras already on classpath via `PortalContextSwitch`). Caveats for S10:
delegate to the original composite (do NOT rebuild it), append/consume only the trailing key; preserve
IP's defensive `doesServerHaveImmPtl()` gate on DECODE (a vanilla-server packet has no trailing bytes
→ underflow otherwise); the same pattern extends to the C2S move-player packets (sync-mixin
companions, land with U8/U10).

### Option (b) — paired ImmPtl packet keyed by teleport id ❌ NOT recommended

A separate `imm_ptl:position_dim_tag(int teleportId, ResourceKey<Level> dim)` correlated by the
position packet's `id`. Rejected: **protocol deviation** (a shape IP never had); **ordering/
correlation hazard** (two packets, either arrival order → buffer+correlate → re-queue/isSameThread/
netty pre-pass hazards); **cost+plumbing** (doubles teleport packet count, new payload registration);
**fragile key** (vanilla can emit position packets the mod didn't originate → null-tag fallback needed,
defeating the "authoritative dim" purpose (a) satisfies cleanly).

### LOCK-STEP RULE (pinned — S8 plan §3, S10 owns impl)

BOTH halves implement (a) in ONE commit at S10 — a deliberate exception to mixins-with-their-unit,
justified by protocol integrity: **server-write half** (`<clinit>` codec-wrap ENCODE + `@Unique
ip_dim` + duck + send-site stamp in `MixinServerGamePacketListenerImpl.teleport`) + **client-read
half** (codec-wrap DECODE consumption + the `handleMovePlayer(ClientboundPlayerPositionPacket)`
routing that USES `ip_getDim()` to select the destination `ClientLevel`). Encode without decode →
client leaves trailing bytes unconsumed → corrupts subsequent reads; decode without encode →
`ip_getDim()` null → NPE/misroute. A half-landed R8 is a live protocol bug.

---

## 7. R12 vanilla-copy re-derivation PLAN — the full `@IPVanillaCopy` collision/teleport cohort (owning stage; F17; impl S10)

**R12/F17 rule restated (owning stage):** every `@IPVanillaCopy` body on the collision/teleport path
is RE-DERIVED LINE-BY-LINE from 26.2 source when its mixin/class lands, NEVER patched from the 1.21.3
copy. This section is the S8-owned PLAN — each copy → its 26.2 re-derivation anchor + the shape of the
rework + the behavior-review flag it carries into S10. Two of the cohort are detailed in their own
sections: the **interpolation-kill** copy (§4 — the ONE `@IPVanillaCopy` body S8 actually ports, since
it is `ClientTeleportationManager.RemoteCallables.updateEntityPos`) and **removeVehicle** (§7.4 below).
The three COLLISION-path copies live in the S6-co-ported `CollisionHelper`/collision-invoker plus the
S10 collision mixins; they are PLANNED here. The collision trio classes (`CollisionHelper`,
`PortalCollisionHandler`, `PortalCollisionEntry`) were co-ported at S6 and are REVIEWED — not re-ported
— this stage; the `@IPVanillaCopy` BODIES they and the S10 mixins carry are exactly what R12 governs,
and the S6 diff-gate already locked `CollisionHelper`'s current form (S6 §5), so the collide-family
re-derivation below is the S10/verify RE-REVIEW checklist against 26.2, not a fresh S8 edit.

| `@IPVanillaCopy` | IP site | 26.2 re-derivation anchor | Body lands |
|---|---|---|---|
| `collide(Vec3)` gravity-generalized copy | `CollisionHelper.handleCollisionWithShapeProcessor` (:250-325) | `Entity.collide(Vec3)` (Entity.java:1138-1167) + `collectCandidateStepUpHeights` (:1169-1188) | S6 class (re-review §7.1) |
| `collideBoundingBox(...)` static copy | `CollisionHelper` (:340-378) | `Entity.collideBoundingBox` (Entity.java:1190-1193) | S6 class (§7.1) |
| `collideWithShapes` `@Invoker` | `IEEntity_Collision` (:14-17) | `Entity.collideWithShapes` (Entity.java:1235-1252, dynamic `axisStepOrder`) | S6 invoker (§7.1) |
| `checkInsideBlocks` portal-clip | `MixinEntity` (:154-179) | `Entity.checkInsideBlocks(List<Movement>,…)` (Entity.java:1269-1297) → `(Vec3,Vec3,…)` (:1299+) | S10 mixin (§7.2) |
| anticheat `isEntityCollidingWithAnythingNew` | `MixinServerGamePacketListenerImpl` (:227-272) | `SGPLI.isEntityCollidingWithAnythingNew` (ServerGamePacketListenerImpl.java:1243-1254) | S10 mixin (§7.3) |
| `setPosRaw` copy (omit 3 callbacks) | `MixinEntity` (:315-340) | `Entity.setPosRaw` (Entity.java:3786-3809) | S10 mixin (§4 S10 lock) |
| interp-kill (`updateEntityPos` RPC body) | `ClientTeleportationManager` (:697-719) | `ClientPacketListener.handleEntityPositionSync` (:640-664) | **THIS stage — §4** |
| `stopRiding` bypass (removeVehicle retarget) | `MixinServerPlayer` (:33-35) | `Entity.removeVehicle` (Entity.java:2464-2474) | S10 mixin (§7.4) |

### 7.1 `collide` / `collideBoundingBox` / `collideWithShapes` — dynamic `axisStepOrder` (top-risk 4; CHANGED rows 8-10)

The three collision bodies changed INTERNALLY; the gravity-generalized copies must be re-derived from
the 26.2 bodies line-by-line at S10/verify, NOT patched from the 1.21.3 copy.

- **`collide(Vec3)`** (`CollisionHelper.handleCollisionWithShapeProcessor`, IP :250-325 → re-derive from
  `Entity.collide` Entity.java:1138-1167). Two 26.2 deltas the copy must absorb: (i) world-border +
  block colliders are now gathered by `collectCollidersIgnoringWorldBorder(...)` using
  `WorldBorder.isInsideCloseToBorder(source, box)` (Entity.java:1207-1224; WorldBorder.java:114-117) —
  the 1.21.3 inline border math (`isWithinBounds` + `getDistanceToBorder < 32`) is GONE from the body;
  (ii) a NEW `expandTowards(0, −1e-5, 0)` tweak on the not-on-ground step-up box (Entity.java:1150-1151).
  The step-up candidate list comes from `collectCandidateStepUpHeights` (:1169-1188).
- **`collideBoundingBox(Entity, Vec3, AABB, Level, List<VoxelShape>)`** (`CollisionHelper` IP :340-378 →
  Entity.java:1190-1193). Signature identical, still `public static`; body now just
  `collectCollidersIgnoringWorldBorder` + `collideWithShapes`, so the shape-filter insertion points
  (entity shapes, border shape, block shapes) all move INSIDE `collectCollidersIgnoringWorldBorder`
  (Entity.java:1207-1224). NB a new sibling overload takes `CollisionContext` (Entity.java:1195) — the
  copy targets the `List<VoxelShape>` form.
- **`collideWithShapes(Vec3, AABB, List<VoxelShape>)`** (`@Invoker` `IEEntity_Collision` :14-17 →
  Entity.java:1235-1252). Same name + descriptor + `private static` → **the `@Invoker` compiles
  UNCHANGED**. But the body's axis-resolution order is now DYNAMIC — `Direction.axisStepOrder(movement)`
  (Entity.java:1242; Direction.java:371) — replacing 1.21.3's FIXED Y-then-swap-X/Z. **Sign note
  (D4.4):** `axisStepOrder` is derived from the MOVEMENT vector, not a hard-coded geometric constant;
  the gravity generalization must feed it the transformed movement, never a fixed axis sequence — and
  the final sweep is always delegated to the real vanilla `collideWithShapes`, which keeps the order
  honest. Fidelity target unchanged: the same swept collision, generalized to the entity's gravity
  direction.

### 7.2 `checkInsideBlocks` portal-clip — movement-path redesign (top-risk 2; CHANGED row 6)

IP (`MixinEntity.java:154-179`) `@Redirect`s the bounding box inside `Entity.checkInsideBlocks`,
replacing it with `ip_getActiveCollisionBox` and CANCELLING when that box is null (the fidelity target:
no block-inside effects from the ghost half of a cross-portal box). **26.2 removed the bb anchor.**
`checkInsideBlocks` is now movement-path based: `private checkInsideBlocks(List<Entity.Movement>,
InsideBlockEffectApplier.StepBasedCollector)` (Entity.java:1269-1297) iterates per-axis path segments
and delegates to `private int checkInsideBlocks(Vec3 from, Vec3 to, …)` (Entity.java:1299+), which walks
`BlockGetter.forEachBlockIntersectedBetween(from, to, this.makeBoundingBox(to).deflate(1.0E-5F), …)`
(Entity.java:1302-1310). Movements are recorded in `move()` via
`addMovementThisTick(new Entity.Movement(pos, newPosition, delta))` (Entity.java:753-755) and consumed
by `applyEffectsFromBlocks(List<Movement>)` (Entity.java:948-975). **S10 redesign (design needed — the
bb-redirect has no anchor):** the box is now derived per-segment from `makeBoundingBox(to)` at
Entity.java:1302 — REDIRECT that call to `ip_getActiveCollisionBox` — and the NULL-active-box cancel
moves to the HEAD of the movement-list overload (Entity.java:1269). The clip must apply to the
per-segment box/path, not the whole-tick box. Behavior-review flag: confirm the per-segment redirect
preserves IP's "ghost part suppressed" invariant across a multi-segment tick (a portal crossing can
span segments).

### 7.3 anticheat `isEntityCollidingWithAnythingNew` retarget — vehicle-guard flag (top-risk 7; CHANGED row 13)

IP re-implements the server anticheat `isPlayerCollidingWithAnythingNew` on `ip_getActiveCollisionBox`
(`MixinServerGamePacketListenerImpl.java:227-272`) so a legitimately-cross-portal player is not flagged.
**26.2 RENAMED + generalized** it to `private boolean isEntityCollidingWithAnythingNew(LevelReader,
Entity, AABB oldAABB, double newX, double newY, double newZ)` (ServerGamePacketListenerImpl.java:1243-1254),
and it is now called from BOTH `handleMovePlayer` and `handleMoveVehicle` (:497). The body swapped
`getCollisions` for `level.getPreMoveCollisions(entity, newAABB.deflate(1e-5), oldAABB.getBottomCenter())`
(CollisionGetter.java:90-95). **S10 re-implementation:** mirror the new body (`getPreMoveCollisions` +
bottom-center `CollisionContext`) and map the moved box through `ip_getActiveCollisionBox`.
**Behavior-review flag (carried to S10/S15 verify):** because the check now also guards VEHICLES
(`handleMoveVehicle`), decide whether IP's cross-portal exemption should extend to the vehicle path —
IP 1.21.3 had NO vehicle version to patch, so this is a genuinely new decision, not a port. Fidelity
default: extend the exemption to vehicles (a cross-portal minecart is the same false-positive class),
with the extension flagged for the live anticheat test.

### 7.4 `MixinServerPlayer.ip_stopRidingWithoutTeleportRequest` retarget (removeVehicle) — CHANGED row / verify R4

IP (`mixin/common/entity_sync/MixinServerPlayer.java:33-35`):
`ip_stopRidingWithoutTeleportRequest() { super.stopRiding(); }` — intent: bypass `ServerPlayer`'s
1.21.3 `stopRiding` override (which sent a dismount teleport packet).

**26.2 breaks the bypass.** `ServerPlayer` no longer overrides `stopRiding`; the packet-sending
override moved to `ServerPlayer.removeVehicle()` (ServerPlayer.java:2138-2150), which sends the
`ClientboundRemoveMobEffectPacket`s (:2141-2145) AND `ClientboundSetPassengersPacket(oldVehicle)`
(:2147-2149). Chain: `Entity.stopRiding()` → `this.removeVehicle()` (virtual, Entity.java:2476) →
`ServerPlayer.removeVehicle()`. So `super.stopRiding()` STILL fires those packets — **the bypass as
written is INEFFECTIVE** (CHANGED row).

**Retarget (S10):** `ip_stopRidingWithoutTeleportRequest()` must call `super.removeVehicle()`
(`Entity.removeVehicle`, Entity.java:2464-2474) directly, skipping `ServerPlayer.removeVehicle`'s
packets. **Behavior-review flag:** `super.removeVehicle()` skips BOTH the effect-removal packets AND
the `ClientboundSetPassengersPacket(oldVehicle)` passenger-list sync (verify R4 — the api-map's
earlier "skipping only the effect-removal packets" parenthetical was incomplete; the row is now
corrected to name both). IP's teleport path does its own vehicle re-seat
(`teleportVehicleAcrossDimensions` + `adjustVehicle`) but leaves the OLD vehicle's client passenger
list unsynced; verify at S10/S15 whether a manual `SetPassengers` resync is needed (IP 1.21.3 never
had to — its `stopRiding` bypass did not send it either — likely a no-op regression, but confirm).
Companion `ip_startRidingWithoutTeleportRequest` gets the `super.startRiding(v, true, false)` 3-arg
translation. This slice's calls to both ducks (`changePlayerDimension` 431-434, 462) are verbatim
duck-interface invocations — no change here.

---

## 8. R13a — `MixinThrownEnderPearl` intercept/replace (owning stage; impl S10)

IP (`mixin/common/collision/MixinThrownEnderPearl.java`): `@Inject` at the `discard()` INVOKE in
`onHit`; if `owner instanceof ServerPlayer` AND cross-dim AND accepting-messages AND not sleeping →
`ServerTeleportationManager.teleportEntityGeneral(serverPlayer, this_.position(), (ServerLevel)
this_.level())`. In 1.21.3 vanilla did NOT teleport the owner cross-dimension, so IP's mixin ADDED
the missing case.

**26.2 handles cross-dim natively.** Class moved to
`net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl` (`@Mixin` import path
changes). `onHit` now gates on `isAllowedToTeleportOwner(owner, level)` = `owner.canUsePortal(true)`
when dimensions differ; player branch calls `player.teleport(new TeleportTransition(level, teleportPos,
…))`, non-player `owner.teleport(new TeleportTransition(…))`, then `discard()` (mc262-ref
`ThrownEnderpearl.java` ~:85-149). This is the loading-screen respawn flow — NOT IP's seamless
teleport. Rename `changeDimension(DimensionTransition)`→`teleport(TeleportTransition)`.

**Redesign (S10):** the mixin must INTERCEPT/REPLACE vanilla's own cross-dim branch, not
inject-before-`discard` adding a case (CHANGED row / risk 6). Recommended shape: gate on `owner.level()
!= this_.level()` and, for that cross-dim case only, CANCEL vanilla's owner-teleport block and
substitute `ServerTeleportationManager.teleportEntityGeneral(owner, this_.oldPosition() [or
position()], (ServerLevel) this_.level())`, still `discard()`ing — i.e. an `@Inject(cancellable)` at
the head of the `owner != null && isAllowedToTeleportOwner` block guarded by dimension mismatch, or a
`@WrapOperation`/`@Redirect` on the two `teleport(TeleportTransition)` INVOKEs. Rationale for canceling
the whole block (not just swapping the teleport call): vanilla's post-teleport follow-ups
(`resetFallDistance`, `hurtServer(enderPearl, 5.0F)`, endermite spawn, `playSound`) run AFTER the
`teleport(...)` and consume its `newOwner` return; IP's 1.21.3 behavior ran NONE of these for the
cross-dim case (pure `teleportEntityGeneral`). SAME-dim pearl teleport stays 100% vanilla (IP never
touched it). Decide the exact injection anchor + whether to preserve the enderpearl self-damage at S10
against the live flow.

---

## 9. R13b — obsolescence-by-vanilla audit (owning stage; NEVER silently dropped)

26.2 has native cross-dim UUID resolution: `Level.getEntityInAnyDimension(UUID)` (Level.java:785),
`ServerLevel.getEntityInAnyDimension(UUID)` (ServerLevel.java:1364-1378, scans
`getServer().getAllLevels()`), `EntityReference`. Full sweep of IP's `imm_ptl/core/mixin/` for
cross-dim UUID/owner patches — the ONLY one:

- **`MixinProjectile.redirectGetEntityFromUuid`** (`collision/MixinProjectile.java:15-34`):
  `@Redirect` of `ServerLevel.getEntity(UUID)` INSIDE `Projectile.getOwner`, scanning
  `server.getAllLevels()` for a cross-dimension owner. **VERDICT: OBSOLETE-BY-VANILLA →
  DROP-CANDIDATE.** In 26.2 `Projectile.getOwner()` = `EntityReference.getEntity(this.owner,
  this.level())` (Projectile.java:61-62) — there is NO `ServerLevel.getEntity(UUID)` call left in
  `getOwner` (the `@Redirect` target vanished; the mixin would find no injection point).
  `EntityReference.getEntity(Level,…)` routes server-side through `level::getEntityInAnyDimension`
  (EntityReference.java:78-81), and `ServerLevel.getEntityInAnyDimension` performs EXACTLY IP's
  all-levels scan. So vanilla natively resolves projectile owners across dimensions on the server.
  **Recommendation: DROP this redirect at S10** (do not port). The commented-out
  `PortalPlaceholderBlock` `onHit` block in the same file is dead code, out of U6 scope.

- Named-but-absent in the R13b brief: `lastHurtByMob`/`thrower` cross-dim shadows. Grep found NO IP
  mixin patching `lastHurtByMob` for cross-dim resolution; `thrower` appears only in `MixinItemEntity_P`
  (a `@Unique` field for item-portal ignition, `portal_generation` slice — out of U6 scope).
  `MixinClientPacketListener.redirectGetEntityById` is a client int-id redirect, not cross-dim UUID.
  **Audit conclusion:** `MixinProjectile` is the SOLE cross-dim UUID patch and it is fully
  obsolete-by-vanilla. Recorded here (never silently dropped, per §S8(a)).

---

## 10. `SeamlessClientTeleport` (verify) carriage (constraint 5) — two re-tests on the ported flow

The KEEP/PORT-FORWARD disposition of the mod's `SeamlessClientTeleport` carries TWO `(verify)`
sub-items — "vanilla-26.2 interactions that may persist; re-test on the ported flow before deleting."
`SeamlessClientTeleport` MAY NOT be deleted at S20 until BOTH are on record.

**(i) Lagged-rotation-field shift** (`yRotO`/`xRotO`/`yBob`+`xBob`/`yBobO`/`xBobO` — hand-glitch chain,
`teleport-hand-glitch-chain`). All rotation change at a crossing routes through
`TransformationManager.managePlayerRotationAndChangeGravity(portal)` (:360, HELD S11 fwd-ref), which
writes `yRotO`/`xRotO` + the public bob fields (LocalPlayer.java:144-147) ABSOLUTELY to the
post-crossing rotation. `ClientTeleportationManager` itself writes NO rotation numerically (§3.4 — it
only capture-restores velocity around the TransformationManager call). **Re-test status: UNPROVEN
until S11** (TransformationManager is a forward-ref this stage). This slice's contribution: it proves
`ClientTeleportationManager` introduces NO independent rotation write (the only rotation authority is
TransformationManager), so the S11/S13 re-test is scoped to TransformationManager alone.

**(ii) Sprint-modifier keeper** (`player-reuse-self-copy-traps`: the sprint theft / FOV pulse came
from attribute-modifier wipes clearing `minecraft:sprinting`). `changePlayerDimension` REUSES the SAME
`LocalPlayer` instance across the swap — `unRide` → `ip_setWorld(toWorld)` →
`removeEntity(CHANGED_DIMENSION)` → `addEntity` → re-`startRiding` — with NO player re-creation and NO
`assignAllValues`/`replaceFrom`/attribute transfer. The player object (and its `AttributeMap`,
including `minecraft:sprinting`) is CARRIED, not copied — structurally immune to the self-copy wipe.
**Re-test status: provable at S13 rung-1 / S17 item-2** — sprint through a crossing, confirm no FOV
pulse / speed loss. Expected green (the wipe's precondition is absent by construction).

---

## 11. Category-(c) resolutions + api-map amendments applied this stage (`teleportation-collision.md`)

**Category-(c) is the D4.3 residual class:** a hunk or forward-ref that is NOT a single clean
pre-existing api-map row nor an S0 loader-seam substitution, so it must be RESOLVED with a written-up
entry here rather than pointed at a slice-map row. S8's category-(c) items, each resolved and
cross-referenced:

- **(c-1) `updateEntityPos` interp-kill primitive-decomposition (§4).** The RPC body is NOT a verbatim
  copy of vanilla `handleEntityPositionSync(:640-664)` — it is a re-derivation into three api-map-backed
  PRIMITIVES (`getPositionCodec().setBase` + `snapTo` + `getInterpolation().cancel()`), with the
  fidelity DECISION to OMIT vanilla's `!isLocalInstanceAuthoritative()` gate (port IP's unconditional
  force-position) carried as an S10/verify behavior-review flag. The decision, not any one row, is the
  category-(c) resolution.
- **(c-2) two established-external-dependency ledger amendments (§12).** The probe shows
  `ServerTickEvents` (fabric-api) and `DimensionAPI` (DimLib F11 stub) on `ServerTeleportationManager` —
  outside the paper S8(b) "cross-unit forward-ref" wording, but import-graph-justified and already
  present in committed S4/S6/S7 siblings. Recorded as governed ledger drift (D4.2), not translation
  slips.
- **(c-3) two NEW api-map GONE rows this stage adds** — the api-map AMENDMENT half, below.

The api-map amendments below were specified by the working fragments but not yet propagated to the
api-map file; applied this pass (tally 7→8 GONE), verified against mc262-ref:

**(a) NEW GONE row — `ServerLevel.getSharedSpawnPos()`** (ServerTeleportationManager.java:834,
`evacuatePlayersFromDimension`). REMOVED — grep: zero hits in all of mc262-ref; absent from
`Level.java`/`ServerLevel.java`. Spawn is now `LevelData.RespawnData` (`GlobalPos`-based,
LevelData.java:33). Replacement `world.getRespawnData().pos()`: `Level.getRespawnData()` abstract
(Level.java:671); `ServerLevel.getRespawnData()` = `this.getServer().getRespawnData()`
(ServerLevel.java:1465-1466); `RespawnData.pos()` (LevelData.java:62) = the `BlockPos` from its
`GlobalPos`; `RespawnData.DEFAULT = GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO)` (LevelData.java:34)
= overworld spawn. Faithful: IP fetches `getOverWorldOnServer().getSharedSpawnPos()` and evacuates to
`Level.OVERWORLD` + `Vec3.atCenterOf(spawnPos)`; server respawn data defaults to overworld spawn, so
behaviour is preserved.

**(b) Profiler GONE row extended** to `Minecraft.getProfiler()` + the two managers' sites. Now cites
`CrossPortalSound.java:46,89` (`ClientLevel.getProfiler()`), `ServerTeleportationManager.java:335,357`
(`MinecraftServer.getProfiler()`), `ClientTeleportationManager.java:142,202,294,296`
(`Minecraft.getProfiler()`). Verified: no `getProfiler()` method on `MinecraftServer`, `Level`, or
`Minecraft` — the two `getProfiler` tokens in `Minecraft.java` are `metricsRecorder.getProfiler()`
(:1395) and `getDebugOverlay().getProfilerPieChart()` (:1406), neither a `Minecraft` method; Minecraft
itself uses `Profiler.get()` (:1166, :2938). All three receivers reduce to the thread-local
`Profiler.get()`.

---

## 12. Forward-ref debt ledger (S8(b)) — probe-authoritative

The `-Pip_scc_closed=true` probe error list IS the ledger (D4.2). Per-U6-file, every error classified:

| File | Errors | Categories (all documented) |
|---|---|---|
| `TeleportationUtil` | **0** | byte-identical, zero forward-ref (every import resolves against landed U3/S5, U4/S6, q_misc_util/S2) |
| `CrossPortalSound` | 8 | loader-facade `@Environment` (net.fabricmc.api) ×2 + **S8(b):** `render.context_management.RenderStates` (U9/S11) — the sole cross-unit ref — + its member uses |
| `ClientTeleportationManager` | 29 | loader-facade `@Environment` ×2 + **S8(b):** `ClientWorldLoader` (U8/S10), `render.FrontClipping`, `render.MyGameRenderer`, `render.TransformationManager`, `render.context_management.{FogRendererContext, RenderStates, WorldRenderInfo}` (all U9/S11) + their member uses |
| `ServerTeleportationManager` | 6 | loader-facade fabric `ServerTickEvents` (event.lifecycle.v1) + DimLib `qouteall.dimlib.api.DimensionAPI` (F11 stub) + **S8(b):** `chunk_loading.ImmPtlChunkTracking` (U7/S9) + one member use |

**Governed ledger amendments (D4.2) — established external-dependency debt, NOT translation slips:**
the paper S8(b) "cross-unit forward-ref" wording enumerated only qouteall `imm_ptl` refs; the probe
also shows two EXTERNAL-dep categories on `ServerTeleportationManager`, both **verbatim IP imports**
(IP:4 `ServerTickEvents`, IP:23 `DimensionAPI` → ported :4/:25) and both **already established** in
this file's committed siblings: fabric `ServerTickEvents` appears in `CollisionHelper` (S6),
`GlobalPortalStorage` (S7), `IPGlobal`/`O_O`/`ServerTaskList` (S4); DimLib `DimensionAPI` appears in
`DimensionIntId` (S4) and `GlobalPortalStorage` (S7). Both resolve when the loader module recompiles
common source WITH fabric-api + the DimLib F11 event-wiring stub at S13 closure (see §S8(a)). These
are import-graph-justified misses of the paper list → recorded here as ledger entries, per the
governed-drift process. **Zero probe error outside this union + established loader/DimLib debt; zero
translation slips.**

**Note on the fabric-api classpath phrasing** (correcting the S08-server working fragment): `common/
build.gradle` carries **no** fabric dependency, so the `:common` probe genuinely errors on
`net.fabricmc.*` — the fabric imports (`ServerTickEvents` in `init()`, `@Environment`) are **loader-
facade debt**, not "on common's compile classpath". The keep-verbatim DECISION is nonetheless correct
and matches the committed S6 `CollisionHelper` / S7 `GlobalPortalStorage` precedent: the loader module
recompiles the raw common source WITH fabric-api at S13 (`multiloader-loader.gradle`
`source(configurations.commonJava)`), so `ServerTickEvents.END_SERVER_TICK.register(...)` in `init()`
stays byte-identical to IP and resolves there.

---

## 13. Verification tier (adversarial teleport/collision + sign/protocol verify) — Opus, Fable re-verify QUEUED

Per the migration model-tier policy (memory `migration-model-tier-policy`), S8 is one of the five hard
stages (S6, S8, S11, S12, S13) **scheduled to escalate its adversarial-verify + design pass to
`model:'fable'`**. Following the S6 precedent (Fable rate-limited at the scheduled time), the S8
adversarial pass **ran on Opus** (`claude-opus-4-8`) — the design rounds (R8 §6, R12 §4/§7,
R13a/R13b §8/§9) and the sign/posture derivations (§3) were reviewed on Opus. Safety for S8 comes from
the workflow gates (probe-authoritative ledger §0/§12, byte-fidelity diff gate §1/§2, D4.4 sign notes
§3, R12/F17 line-by-line re-derivation §7, `:common:test` green), **not** the tier
(migration-model-tier-policy: "safety comes from the workflow gates, not the tier"), so the Opus run
satisfies the stage's fidelity instruments.

**Doc-level adversarial verification already on record:** `migration/verify/teleportation-collision.md`
(the standalone adversarial pass over the inventory + api-map for this slice — 40 claims checked, **36
confirmed, 4 refuted**, verdict MINOR: "none misdirects architecture, geometry transforms, or API
fates"). All four refutations are reconciled in THIS note:
- **verify R1** (combo-rejection restores to the LAST-checkpoint-derived position, not the "pre-combo
  position"; loop is `i <= teleportLimitPerFrame` = up to 4 `tryTeleport` calls) → §3.4 states the eject
  signs (`worldSurfaceNormal · −0.001` position, `normal · −0.1` velocity) and that only the DIMENSION
  is pre-combo; the port carries IP's exact `:175-183` body.
- **verify R2** (inventory's "after 10 consecutive wrong-dimension packets" is off-by-one) — an
  INVENTORY-only prose miscount; it does not touch any ported U6 line (the counter lives in the S10
  packet mixins), noted here for completeness, no port impact.
- **verify R3** (`SimpleSoundInstance` is the **8-arg** ctor, not 9-arg) → folded into §2.1
  (`SimpleSoundInstance.java:92`).
- **verify R4** (removeVehicle bypass skips BOTH the effect-removal packets AND the
  `ClientboundSetPassengersPacket(oldVehicle)`, not "only the effect-removal packets") → folded into
  §7.4 with the SetPassengers-resync behavior-review flag.

**QUEUED before the S13 flip / S10 impl — a Fable re-verify scoped to the three highest-signal S8
surfaces** (where a second adversarial tier adds real signal before anything renders/executes live):
1. **The exit-posture SIGN derivations (§3)** — the motion-keyed-exit depth signs (§3.3), the planar
   exit guard `getContentDirection() = rotation.rotate(getNormal().scale(−1))` and the three
   not-behind-destination pushes (§3.2 — the `entity-vanish` landing-defect sign class), and the
   `worldPosToPortalLocalPos` cross-product order W×H (§3.1). Sign surface = the migration's
   highest-risk class (API_RISKS R5 reversed-Z; geometry-sign lesson bitten twice — briefing §6).
2. **The R8 protocol design (§6)** — the codec-wrap `@ModifyExpressionValue` idiom and the LOCK-STEP
   both-halves-in-one-commit correctness argument (a half-landed R8 is a live protocol bug), before the
   S10 pair is written.
3. **The R12 collision-path re-derivations (§7.1-7.3)** — the dynamic `axisStepOrder` feed, the
   `checkInsideBlocks` per-segment clip redesign, and the anticheat vehicle-guard fidelity default,
   before the S10 collision mixins copy the 26.2 bodies.

Logged as an **S10/S13 pre-impl gate item**. No other S8 deliverable is blocked on it — the port is
diff-gate-clean (§1/§2, `TeleportationUtil` byte-identical), probe-clean against the S8(b) union
(§12), and the design rounds are complete now; the Fable pass is a confirmation tier over the signs and
the protocol, not a dependency for the S8 close.

---

## 14. Handoffs

- **S10 (`ClientWorldLoader` + world-swap render mixins):** implement the two kept-verbatim render-swap
  ducks per §5 — `ip_setWorldRenderer` (`@Mutable` final `levelRenderer` OR single-renderer + extractor
  re-point, `nether-block-freeze-orphaned-extractor`) and `ip_setLightmapTextureManager` (per-dim
  lightmap decision). Do NOT edit the U6 files — the calls are stable regardless of chosen mixin body.
  Also DROP the dead `ip_setClientLevel` from `IEAbstractClientPlayer` (its only call site was deleted, §2.2 H6).
- **S10 (R8 lock-step pair):** implement Option (a) per §6 — server-write + client-read in ONE commit.
- **S10 (R12 cohort):** entity-sync/dedup/`setPosRaw` mixins re-derive the interp-kill as `setBase +
  snapTo + getInterpolation().cancel()` (§4), sharing this RPC's `VecDeltaCodec` base assumption; carry
  the `isLocalInstanceAuthoritative` behavior-review flag. Retarget `ip_stopRidingWithoutTeleportRequest`
  → `super.removeVehicle()` (§7) with the SetPassengers-resync verify flag.
- **S10 (R13a):** ender-pearl mixin intercepts/replaces vanilla's native cross-dim branch (§8). **R13b:**
  DROP `MixinProjectile.redirectGetEntityFromUuid` (obsolete-by-vanilla, §9) — documented, not silently dropped.
- **S11 (render family):** landing `RenderStates`/`WorldRenderInfo`/`FrontClipping`/`MyGameRenderer`/
  `TransformationManager`/`FogRendererContext` resolves the §12 debt. TransformationManager landing is
  the trigger for the §10(i) hand-glitch re-test.
- **S13 (dispatch host):** `manageTeleportation(false)` is the render-phase entry consumed by the
  S3-relocated pre-render anchor host under the `entityPortals` flag (D3 shared-host dispatch) — the
  call site is mod-owned; the U6 files only DEFINE the entry.
- **S13 rung-1 / S17:** run the §10 (i)+(ii) `SeamlessClientTeleport` (verify) re-tests; record before
  the S20 deletion of `SeamlessClientTeleport`.
