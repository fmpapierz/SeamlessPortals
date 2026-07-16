# S13-C — the EMERGENCY WEAVE AUDIT (the first-light flag-ON crash triage)

**Stage S13-C of the entity-portal migration — triggered by the USER's first flag-ON launch CRASH.**
The `entityPortals=true` bring-up crashed at **mixin-weave time** (class-load), not at javac time. Two IP
mixins failed to apply with fatal `InvalidInjectionException` / shadow-not-located errors:

1. `client.MixinAbstractClientPlayer` — `@Shadow` method `setLevel(Lnet/minecraft/world/level/Level;)V`
   **NOT located** in `net.minecraft.client.player.AbstractClientPlayer`.
2. `client.MixinMinecraft` — callback `onSnooperUpdate` injection matched **0 targets** in
   `net.minecraft.client.Minecraft` (fatal under `defaultRequire=1`).

These are **weave-time target mismatches**: the mixin annotations compiled clean (javac never resolves an
`@At`/`@Shadow`/selector string against the target bytecode), but the mixin subsystem could not bind the
anchor to a real 26.2 member at class-load. Because a `required=true` config aborts the whole game on the
first unresolved anchor, the two visible failures were only the FIRST two of their kind — the rest of the
registered IP mixin set had never been weave-exercised (the whole set had been probe-compiled and
shipping-compiled, but never LOADED, since the flag was OFF through S12-B). This audit swept **all 137
registered IP mixins** for the same class of defect BEFORE the user relaunches.

**Citation conventions:** `IP:` = 1.21.3 (`ImmersivePortalsMod/.../qouteall`); `26.2:` = `mc262-ref`
(Mojang mappings); `MOD:` = live `com.warwa.seamlessportals`; `MOD-qouteall:` = the held/registered ports.

---

## 1. ROOT-CAUSE NOTE — weave-time anchors are invisible to javac (this audit is now a MANDATORY gate)

**The defect class.** A mixin's binding surface — `@Mixin` target names, `@Shadow` member
names+descriptors, `@Inject`/`@Redirect`/`@ModifyArg`/`@ModifyVariable`/`@WrapOperation` `method=` selectors,
and `@At` `INVOKE`/`FIELD`/`NEW` target strings — are **strings and annotation values that javac never
resolves against the target class.** A held mixin that names a member the target no longer declares (or
declares under a different name/descriptor/enclosing-method) compiles GREEN, passes the S12-B HARD-GATE
probe GREEN, ships GREEN — and then throws at class-load the instant its config is applied. Every prior
migration gate (shipping build, `-Pip_scc_closed` probe, `:common:test`) is **blind to this entire failure
class.** The only tool that surfaces it is *applying the mixin against the real runtime bytecode* — which,
for a flag-gated set, first happens at first-light flag-ON.

**Why it concentrated at S13-C and not earlier.** The IP mixin set was ported held-UNREGISTERED through
S10–S12 and only *registered* (added to the `mixins.json` `client`/`mixins` arrays) at the S13 flip. S12-B's
verification explicitly recorded FIVE of these as live WATCH items pending "the moment the S13 client set
registers" (S12B-client-mixins.md §9 items 1, 6 and the ⚠L lambda items) — i.e. the risk was known and
flagged, but by construction could only be *discharged* at registration+weave. S13-C is that discharge.

**The five weave-defect sub-classes found (the taxonomy javac cannot catch):**

| # | Sub-class | Why javac/probe misses it | Example (this audit) |
|---|---|---|---|
| A | **Inherited-member `@Shadow`** | mixin binds `@Shadow` only to members **DECLARED IN the target class**; an inherited member satisfies javac's type check but not the weaver | `MixinAbstractClientPlayer` shadows `Entity.setLevel` (declared on `Entity`, not `AbstractClientPlayer`) |
| B | **Covariant-override descriptor drift** | source-level `x.level()` compiles; javac silently binds it to the covariant override, so the emitted invoke descriptor ≠ the IP-era `@At` target string | `MixinServerPlayerGameMode` — `ServerPlayer.level()` now emits `()ServerLevel`, not `()Level` |
| C | **Method-body relocation** | the `@Inject` `method=` names a method that still exists, but the anchored FIELD/INVOKE moved to a *different* method | `MixinMinecraft.onSnooperUpdate` — the `fps` write moved `runTick`→`renderFrame` |
| D | **Synthetic-lambda relocation** | the anchored invoke lives inside a compiler-synthesized lambda whose name (`lambda$<m>$<n>`) is not the enclosing source method and changed from the 1.21.3 obf name | `MixinMultiPlayerGameMode.redirectPlayerLevel1` — invoke is in `lambda$startDestroyBlock$1` |
| E | **Renamed-then-removed member (dead `@Shadow`)** | a getter renamed on 26.2 leaves a dangling `@Shadow abstract` that the weaver can't locate — even when the member is otherwise DEAD | `MixinCamera` — `getEntity()`→`entity()`; the shadow was unused |

**RULING (binding going forward): a weave-apply audit against the real named dev bytecode is a MANDATORY
gate for every future mixin-porting stage.** No mixin set may be declared done on the strength of a green
shipping build + green probe alone; each `@Mixin` target, every `@Shadow`, and every injector selector/`@At`
target string must be proven to resolve against the jar the dev client actually loads (§2), exactly as this
audit did. The S12-B "held-UNREGISTERED, weave-verify at registration" model is sound ONLY if the
registration stage runs this audit as its entry gate — S13-C establishes that gate retroactively and it is
inherited by any later stage that registers or re-anchors a mixin (S18 `CrossPortalEntityRenderer`, the
deferred `LevelRenderer` render-phase hooks, the FrontClipping shader family, any future MC bump).

---

## 2. GROUND TRUTH + per-mixin validation rules

**Named dev bytecode (authoritative).** The Fabric dev client runs the loom **named** (Mojang-mapped) jar
`~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` (the `loom.mappings.26_2.layered+hash.*` subdirs
hold only `mappings.jar` mapping data, not classes; the top-level `minecraft-merged.jar` is the merged
client+server classes with Mojang-official names applied — method names read `entity()`/`renderFrame`, not
`method_*`). Every anchor in this audit was checked with `javap -p -classpath <that jar> <class>` and
cross-read against the `mc262-ref` decompiled sources (same named mappings). Names match the `mc262-ref`
source names throughout.

**Per-mixin validation rules applied (each of the 137):**
- (a) every `@Mixin` target class exists;
- (b) every `@Shadow` method/field exists **DECLARED IN the target class itself** with the exact
  name+descriptor (inherited members do NOT satisfy `@Shadow`; a shadow of a renamed/inherited member is a
  defect → re-anchor to where 26.2 declares it, or re-derive the access);
- (c) every injector `method=` selector resolves to exactly the intended 26.2 method (name+descriptor), and
  every `@At INVOKE/FIELD/NEW` target string exists with its exact descriptor;
- (d) every `@Accessor`/`@Invoker` member exists;
- (e) `@Unique` members are weave-inert (no-op — added, never located).

`require:1` (loud failure) is preserved on every fix — a silent 0-match is never masked with `require=0`.

---

## 3. CENSUS — 137 registered IP mixins across 5 configs

**Scope.** Only `qouteall.*` mixins gated behind the `entityPortals` flag are in scope. Flag-OFF boot is
PROVEN fine (S13 Part 0 passed), so the mod's own `seamlessportals-common.mixins.json` (`com.warwa`, always
active, byte-identical flag-OFF) is OUT of scope and not counted here.

| Config file | mixin package | server/common `mixins[]` | `client[]` | total |
|---|---|---|---|---|
| `seamlessportals-ip-core-common.mixins.json` | `qouteall.imm_ptl.core.mixin` | 73 | — | **73** |
| `seamlessportals-ip-client.mixins.json` | `qouteall.imm_ptl.core.mixin` | — | 57 | **57** |
| `seamlessportals-ip-qmisc.mixins.json` | `qouteall.q_misc_util.mixin` | 3 | 2 | **5** |
| `seamlessportals-ip-fabric.mixins.json` | `qouteall.imm_ptl.core.platform_specific.mixin` | 2 | 0 | **2** |
| `seamlessportals-ip-peripheral.mixins.json` | `qouteall.imm_ptl.peripheral.mixin` | 0 | 0 | **0** |
| **TOTAL** | | | | **137** |

**Audit result:** 137 audited · **5 BROKEN** (re-anchored/neutralized, §4) · 132 clean ·
**0 unaudited.** No batch/tooling failure left any mixin unchecked, so the mandatory re-run list is EMPTY.

**Broken set by config:** `ip-core-common` = 1 (`MixinServerPlayerGameMode`); `ip-client` = 4
(`MixinAbstractClientPlayer`, `MixinMinecraft`, `interaction.MixinMultiPlayerGameMode`,
`render.MixinCamera`); `ip-qmisc`/`ip-fabric`/`ip-peripheral` = 0.

**Unaudited (batch failures — MUST be re-run):** **[] (none).**

---

## 4. THE BROKEN SET — per-mixin verdict + 26.2 re-anchor

Each fix re-derives IP's exact semantics onto a real 26.2 anchor (`javap`-confirmed against the named merged
jar), keeps `require:1`, and carries an in-file `S13-C weave fix` comment. Zero IP-logic deviation.

### 4.1 `common.interaction.MixinServerPlayerGameMode` — sub-class B (covariant descriptor drift)

- **Symptom:** the `@Redirect redirectGetLevel(ServerPlayer)` on
  `method={"incrementDestroyProgress","handleBlockBreakAction"}` targeted
  `@At INVOKE Lnet/minecraft/server/level/ServerPlayer;level()Lnet/minecraft/world/level/Level;` and matched
  **0 INVOKE points** → fatal.
- **Root cause:** 26.2 `ServerPlayer` declares a **covariant override** `public ServerLevel level()`
  (`()Lnet/minecraft/server/level/ServerLevel;`) alongside the synthetic `()Level` bridge (both present in
  the jar). javac binds every receiver-typed-`ServerPlayer` `this.player.level()` to the **covariant**, so
  `incrementDestroyProgress`/`handleBlockBreakAction` contain only the `()ServerLevel` invoke; the IP-era
  `()Level` target string is never emitted.
- **Re-anchor:** `@At` target → `...ServerPlayer;level()Lnet/minecraft/server/level/ServerLevel;` and the
  handler return type `Level`→`ServerLevel` (a `@Redirect` handler must return the redirected call's type;
  `ip_getActualWorld()` already returns `ServerLevel`). The sibling `@Redirect` on the `GETFIELD level`
  path, the `@WrapOperation isWithinBlockInteractionRange`, the 3-arg `UseOnContext` `NEW` redirect, and the
  `PUTFIELD destroyPos`/`delayedDestroyPos` injects were all re-checked CLEAN (already S10-C-correct).
- **javap evidence:** `public net.minecraft.server.level.ServerLevel level();` AND
  `public net.minecraft.world.level.Level level();` both present on `ServerPlayer`.

### 4.2 `client.MixinAbstractClientPlayer` — sub-class A (inherited-member `@Shadow`)

- **Symptom** (one of the two crash lines): `@Shadow` method `setLevel(Level)V` NOT located in
  `AbstractClientPlayer`.
- **Root cause:** IP 1.21.3 mutated the client level via a `@Shadow @Mutable AbstractClientPlayer.clientLevel`
  field. That field is GONE on 26.2; the S12-B port had re-sited the duck onto a `@Shadow` of
  `Entity.setLevel(Level)`. But the mixin subsystem locates `@Shadow` members only among those **DECLARED IN
  the target class** — and `setLevel` is declared on `Entity` (`protected void setLevel(Level)`,
  `Entity.java`), not on `AbstractClientPlayer`/`Player`/`LivingEntity`. The inherited member satisfies javac
  but not the weaver.
- **Re-anchor:** drop the `@Shadow setLevel` entirely; route `ip_setClientLevel(ClientLevel)` through the
  existing `IEEntity.ip_setWorld(Level)` duck. `Entity` implements `IEEntity` via `@Mixin(Entity.class)
  MixinEntity`, which `@Shadow`s the `Entity.level` field and writes it directly (`this.level = world;`) —
  **byte-for-byte the body of `Entity.setLevel`** (a pure putfield, no side effects). Behaviourally identical
  to the api-map's `Entity.setLevel` prescription; IP's role (re-point the client player onto another dim's
  `ClientLevel`) preserved verbatim.
- **javap evidence:** `Entity` has `protected void setLevel(net.minecraft.world.level.Level);` and
  `private net.minecraft.world.level.Level level;`; `AbstractClientPlayer` declares no `clientLevel` and no
  `setLevel`.

### 4.3 `client.MixinMinecraft` — sub-class C (method-body relocation)

- **Symptom** (the second crash line): `onSnooperUpdate` matched 0 targets.
- **Root cause:** IP hooked the once-per-second `fps = frames` FIELD write (in 1.21.3 inside the snooper/run
  loop). On 26.2 the sole `putstatic fps:I` moved OFF `runTick(Z)` into `renderFrame(Z)` (inside the
  `Util.getMillis - lastTime >= 1000ms` block). `runTick(Z)` no longer touches `fps`, so the IP-era method
  selector matched 0 FIELD points → fatal.
- **Re-anchor:** `@Inject method="renderFrame(Z)V"`, `@At FIELD target Minecraft.fps:I shift=AFTER`.
  `renderFrame` also takes a single `boolean`, so the `(boolean tick, CallbackInfo)` handler signature is
  unchanged; the FIELD target matches exactly 1 point there and `shift=AFTER` still reads the freshly-set
  `fps`. IP semantics identical (`ClientPerformanceMonitor.updateEverySecond(fps)`).
- **javap evidence:** `public void renderFrame(boolean);`, `private void runTick(boolean);`,
  `private static int fps;` — all present on `Minecraft`. (The other `MixinMinecraft` anchors — the
  `testMixinExtra` `run()` WrapOp, the `tick` `ClientLevel.tick`/`tickEntities` injects, the 2-arg
  `updateLevelInEngines(ClientLevel;Z)V` HEAD, and the `IEMinecraftClient` ducks routed through
  `GameRendererAccessorMixin`/`gui.screen()`/`Profiler.get()` — were re-checked CLEAN.)

### 4.4 `client.interaction.MixinMultiPlayerGameMode` — sub-class D (synthetic-lambda relocation)

- **Symptom:** `@Redirect redirectPlayerLevel1` on `method="startDestroyBlock"` matched 0 INVOKE points.
- **Root cause:** the only `LocalPlayer.level()` invoke reachable from `startDestroyBlock` lives inside a
  compiler-**synthetic lambda** (`startDestroyBlock`'s own body has zero `level()` invokes). IP's 1.21.3
  anchor `method_41930` is a dead obf name.
- **Re-anchor:** re-derive the lambda name from the compiled named jar and substitute it for the `method=`
  selector: `method="lambda$startDestroyBlock$1"`. The `@At INVOKE` target string
  (`LocalPlayer;level()Lnet/minecraft/world/level/Level;`) is unchanged (the bytecode owner is literally
  `LocalPlayer.level`). The sibling `redirectPlayerLevel2` (`continueDestroyBlock`, DIRECT — not in a
  lambda) resolves normally; the 3-arg→5-arg `UseOnContext` `NEW` redirect via `IEUseOnContext` invoker and
  the three `send(Packet)` `@ModifyArg`s were re-checked CLEAN.
- **javap evidence:** `private ... Packet lambda$startDestroyBlock$1(BlockState, BlockPos, Direction, int);`
  present; enclosing `public boolean startDestroyBlock(BlockPos, Direction);` present. (Ordinal `$1` is
  compiler-assigned; see §6 for the residual-risk note carried from verify.)

### 4.5 `client.render.MixinCamera` — sub-class E (renamed-then-removed member → NEUTRALIZED)

- **Symptom:** `@Shadow public abstract Entity getEntity();` NOT located in `Camera`.
- **Root cause:** 26.2 renamed the getter `Camera.getEntity()` → `Camera.entity()`; `getEntity()` no longer
  exists on `Camera` and is not inherited (`Camera extends Object`). The `@Shadow abstract` therefore failed
  apply-time validation.
- **Disposition — NEUTRALIZE (remove the dead member), not re-anchor.** The shadowed getter was **DEAD**:
  nothing in the mod consumes it, and `IECamera` never declared it (the focused-entity is reached via the
  `@Shadow Entity entity` field + `portal_setFocusedEntity`). Per the documented target-gone precedent
  (S10 `MixinAbstractMinecartEntity`, S12 `MixinCompiledShader`), a genuinely dead/gone member is removed
  with a documenting comment rather than silently deleted or force-re-anchored. Here the removal is
  **member-scoped, not mixin-scoped** — only the one dead `@Shadow` line is dropped; the mixin stays
  REGISTERED and its live anchors (the `update`→`alignWithEntity` `AFTER` inject, the `getFluidInCamera`
  HEAD-cancel, the `isDetached` HEAD-cancel, and the `IECamera` ducks over `position`/`level`/`entity`/
  `eyeHeight(Old)`) all resolve. No config unregistration was needed.
- **javap evidence:** `public net.minecraft.world.entity.Entity entity();` present; no `getEntity()`;
  `private net.minecraft.world.entity.Entity entity;` field present (the live shadow).

**Neutralizations this stage:** ONE, member-scoped — the dead `Camera.getEntity()` `@Shadow` (§4.5). No
whole-mixin neutralization; no `mixins.json` unregistration; no `IpHeldPaths`/AW/AT edit.

---

## 5. TWO FIDELITY ITEMS surfaced by verify — resolved (non-weave-blocking)

The adversarial verify pass (§6) surfaced two items that were NOT weave-blocking (the relaunch was already
safe after §4) but were genuine zero-deviation fidelity gaps. Both were then fixed (the "+ final fix"
round). Neither is a mixin-weave anchor; both are recorded here for the census completeness.

1. **`client.MixinGui` — stale deferral, now ENABLED.** IP's ⑦ `addInitialScreens` config-help splash
   handler was DEFERRED at S12-B with the justification "`IPortalInitialScreen` NOT YET PORTED." That class
   has since landed (`common/src/main/java/qouteall/imm_ptl/core/miscellaneous/IPortalInitialScreen.java`,
   committed, compiles), so the deferral's blocking condition is met and per zero-deviation the handler is
   re-enabled: `@Inject(method="addInitialScreens", at=@At("RETURN"))` appending `IPortalInitialScreen::new`
   to the captured `List<Function<Runnable,Screen>>` when `!IPConfig.getConfig().initialScreenShown`.
   **Weave-checked:** `Gui.addInitialScreens(List<Function<Runnable,Screen>>)` is `private` and returns
   `boolean` (javap-confirmed), so the handler correctly uses `CallbackInfoReturnable<Boolean>` and a
   `List` capture at `RETURN` (the list is populated before `buildInitialScreens` consumes it via
   `Lists.reverse`). IP's initial-screen behavior is no longer silently absent.

2. **`ClientTeleportationManager` — dropped call site restored + port-note.** IP
   (`ClientTeleportationManager:483`) calls `((IEAbstractClientPlayer) player).ip_setClientLevel(toWorld)`
   immediately after `toWorld.addEntity(player)`; the S12-B port had omitted it (leaving the
   `IEAbstractClientPlayer` duck + `MixinAbstractClientPlayer` caller-less). On 26.2 the omission is
   provably behavior-neutral (`Entity.level` is the single pointer, already written by `ip_setWorld` earlier
   in the method; `ip_setClientLevel` now re-sites onto that same pointer — §4.2), but per strict fidelity
   the call is **restored** (a harmless duplicate write preserving IP's exact call order) with an in-place
   port-note documenting the two-field→one-field collapse. Keeps the duck IP-faithful and non-caller-less.

---

## 6. VERIFICATION TIER

**Tier: dual adversarial verify (Fable) + fix, per the model-tier policy for hard stages (S13 is a hard,
live-checkpoint stage).**

- **Fix round 1** re-anchored/neutralized the 5 weave-broken mixins (§4), each `javap`-confirmed against the
  named merged jar, each carrying an in-file `S13-C weave fix` comment, `require:1` preserved.
- **Verify pass** (two independent verifiers):
  - Verifier-1: confirmed all four weave-gate criteria pass for the broken set; surfaced **two non-weave
    fidelity items** (§5, both then fixed in the final round) — the stale `MixinGui` deferral and the
    dropped `ClientTeleportationManager.ip_setClientLevel` call. Neither was weave-blocking; the relaunch
    was safe after fix round 1.
  - Verifier-2: **none requiring action** — all gate criteria pass. **Observational note (no action):**
    `MixinMultiPlayerGameMode` (§4.4) now anchors on the compiler-assigned synthetic name
    `lambda$startDestroyBlock$1`; this is the documented R13c prescription and `defaultRequire=1` guarantees
    a **loud** weave failure if a future recompile/remap shifts the lambda ordinal (the residual risk is
    caught, not silent).
- **Final fix round** landed the two §5 fidelity items (enable `MixinGui` ⑦; restore
  `ClientTeleportationManager` call), both dependency-verified (`IPortalInitialScreen` present; `Gui`
  anchor javap-confirmed).
- **Gate discipline (constraints honored):** ZERO IP-logic deviation — every fix re-derives IP semantics
  onto a real 26.2 anchor; `require:1` retained on all injectors (loud failure preferred); shipping build +
  `-Pip_scc_closed` probe + `:common:test` remain green after the fixes (no new signature errors introduced;
  all edits are inside already-held/registered files); the game was NOT run by this audit; no git commit.

**Working-tree touch (S13-C, all rounds):** 7 files, all in-scope held/registered `qouteall.*` sources —
`mixin/common/interaction/MixinServerPlayerGameMode.java`, `mixin/client/MixinAbstractClientPlayer.java`,
`mixin/client/MixinMinecraft.java`, `mixin/client/interaction/MixinMultiPlayerGameMode.java`,
`mixin/client/render/MixinCamera.java` (the 5 weave fixes), plus `mixin/client/MixinGui.java` and
`teleportation/ClientTeleportationManager.java` (the 2 §5 fidelity items). No `mixins.json` edit, no build
wiring, no AW/AT, no `com.warwa` change.

---

## 7. HANDOFF — the user relaunch

The registered IP mixin set is weave-clean against the named 26.2 dev bytecode: all 137 audited, 5
re-anchored/neutralized zero-deviation, 0 unaudited. First-light flag-ON (`S13-FIRST-LIGHT-TEST.md` Part 1)
can be relaunched. Because `required=true` logs **every** failed mixin apply before it aborts, a single run
surfaces the complete set — the user should report the FULL set of any remaining `Mixin apply ... failed`
lines from `latest.log` (they are all logged before the crash). If the log is clean of those lines, the
weave gate held and Part 1 proceeds to its render/crossing checks.

**S13-C carried forward (weave-audit is now a standing gate):** any future stage that registers a new mixin
or re-anchors an existing one (S18 `CrossPortalEntityRenderer`; the deferred `LevelRenderer` render-phase
hooks; the FrontClipping shader family; the `ShaderCodeTransformation` shell; any MC version bump) MUST run
this weave-apply audit (§2) against the named dev jar as its entry gate — a green shipping build + green
probe do NOT cover the weave-anchor failure class (§1).

## STATUS: S13-C WEAVE AUDIT COMPLETE — relaunch weave-clean.

---
---

# S13-D — the FLAG-ON INIT AUDIT (the runtime bring-up trace)

**Stage S13-D of the entity-portal migration — the static trace of the whole `entityPortals=true` BOOT
SEQUENCE, run to clear the runtime landmines that S13-C's weave audit is structurally blind to.** S13-C
proved the mixin set *applies* against the 26.2 bytecode; it says nothing about what the applied code *does*
when the flag-ON `init()` chain actually runs. The user's first-light attempts detonated one runtime
landmine per launch — attempt 1 = weave anchors (S13-C / S13.7); attempt 2 = `IPFeatureControl`
`getModContainer("iportal")` self-identity throw inside `IPConfig.<init>` (S13.8). This audit's mandate:
find **the rest of that class in ONE static pass** — trace the flag-ON boot end-to-end
(`SeamlessPortalsModFabric.onInitialize`/`onInitializeClient` → `IPModMain.init` / `IPModMainClient.init` →
world create → server start → login → command registration → first frames → `/portal make_portal`) and
surface every remaining self-identity lookup, missing shipped resource, dev-env/version assumption,
init-order/registry-timing hazard, datapack/dynamic-registry reference, and null-env gap **before** the user
relaunches.

**Why this is a distinct gate from S13-C.** The weave audit resolves *strings-vs-bytecode* at class-load.
This audit resolves *what the ported init code assumes about its own host identity, its shipped assets, and
the loader/registry lifecycle* — a failure class that a green weave, a green shipping build, and a green
`:common:test` are all blind to, because it only manifests when the flag-ON entrypoints execute against a
real `seamlessportals`-identity loader runtime. S13.8 fixed the first such throw reactively (one landmine);
S13-D sweeps the whole chain proactively.

**Citation conventions:** as §S13-C — `IP:` = 1.21.3 upstream; `26.2:` = `mc262-ref`; `MOD:` = live
`com.warwa.seamlessportals`; `MOD-qouteall:` = the held/registered `qouteall.*` ports. Line numbers are the
current on-disk files.

---

## D.1 — THE BOOT SEQUENCE TRACED (and where it detonated)

The flag-ON entrypoints, in execution order, with the crash chain S13.8 broke:

```
SeamlessPortalsModFabric.onInitialize
  → q_misc_util.{ImplRemoteProcedureCall,MiscNetworking}.init + DimensionIntId.init
  → IPModMain.init()                                   [IPModMain.java:62]
       → loadConfig()                                  [:63 → :130]
            → AutoConfig.register(IPConfig.class, …)   [:146]  ── instantiates IPConfig
                 → IPConfig.<init> field initializers  [IPConfig.java:100/:105/:109]
                      → IPFeatureControl.enableVanillaBehaviorChangingByDefault()
                           → isProvidedByJarInJar()
                                → getModContainer("iportal").orElseThrow()   ✖ THREW (attempt 2)
       → ImmPtlNetworking.init / ImmPtlNetworkConfig.init()   [:67/:68]
            → ImmPtlNetworkConfig:212 → O_O.getImmPtlVersion()
                 → getModContainer("iportal")…               ✖ WOULD THROW (attempt 3, pre-empted)
       → …shapes / chunk-tracking / GlobalPortalStorage / EntitySync / teleport / collision /
         GcMonitor.initCommon / CommandRegistrationCallback.register(PortalCommand) /
         CustomPortalGenManager.init / animation-type registration…

SeamlessPortalsClientFabric.onInitializeClient
  → registerPortalEntityRenderers()   [UNCONDITIONAL — selfTest/validateRegistrations gate, S13 step 5]
  → if entityPortals:  q_misc_util.*.initClient → IPModMainClient.init()
```

Two host-registration facts confirmed on the boot path (both kept-verbatim, correct): `IPModMain` registers
**1 block** (`immersive_portals:nether_portal_block`, :157) and **10 entity types**
(`immersive_portals:portal`…`loading_indicator`, :164-212) under the `immersive_portals:` namespace — these
are registry/save-compat keys (protocol), NOT self-identity loader lookups, and stay verbatim. No registry
is *queried* before its registration; the init order matches IP's entrypoint order recorded in
`S13B-flip.md`.

---

## D.2 — FINDING CENSUS BY HUNT-CLASS + SEVERITY (11 findings)

Severity key: **CRIT** = first-light crash · **HIGH** = init-reachable crash · **MED** = reachable-but-
shielded crash *or* broken visual · **LOW** = cosmetic / dormant-path defect · **INFO** = verified-safe (no
defect, dispositioned).

| # | Hunt class | Finding | Sev | Disposition |
|---|---|---|---|---|
| F1 | 1 self-identity | `IPFeatureControl.isProvidedByJarInJar` `getModContainer("iportal")` — the attempt-2 crash root, on the `IPConfig.<init>` chain | **CRIT** | FIXED S13.8 (committed) → `"seamlessportals"` |
| F2 | 1 self-identity | `O_O.getImmPtlVersion` `getModContainer("iportal")` — init-reachable via `ImmPtlNetworkConfig:212 ← .init() ← IPModMain:68` (would have been attempt 3) | **HIGH** | FIXED S13.8 (committed) → `"seamlessportals"` |
| F3 | 1 self-identity | `O_O.getImmPtlVersionStr` `getModContainer("iportal")` — reachable via `IPModInfoChecking` preview-version path | **MED** | FIXED S13.8 (committed) → `"seamlessportals"` |
| F4 | 1 self-identity | `O_O.shouldUpdateImmPtl` `getModContainer("iportal").get()` — the last residual literal | **LOW** | FIXED S13-D (working tree) → `"seamlessportals".orElseThrow()` |
| F5 | 2 missing asset | `immersive_portals:icon.png` hard-referenced by `IPortalInitialScreen:57` (`ImageWidget.texture`); the config-help splash was RE-ENABLED at S13.7 (`MixinGui` ⑦) — port shipped zero `immersive_portals` assets | **MED** | FIXED S13-D → `icon.png` shipped (byte-identical to IP) |
| F6 | 2 missing asset | the entire `immersive_portals`-namespace lang table was absent (286 keys: `imm_ptl.*`, `entity.immersive_portals.*`, `dimension.immersive_portals.*`, `text.autoconfig.immersive_portals.*`, `iportal.initial_screen.*`) → raw-key display everywhere | **LOW** | FIXED S13-D → full IP `en_us.json` shipped verbatim |
| F7 | 3 dev-env/version | `IPModInfoChecking.fetchImmPtlInfoFromInternet` HTTP GET to `qouteall.fun` at init | **INFO** | verified-safe: off-thread (`Util.backgroundExecutor`), full `try/catch(Throwable)→null` (:142), gated `checkModInfoFromInternet`, AND currently unreachable (F10) |
| F8 | 3 dev-env/version | mod-version parse in dev env yields non-semantic `"${version}"` | **INFO** | verified-safe: graceful `→ ModVersion.OTHER` (`O_O:148-151`), no throw |
| F9 | 3 dev-env/version | config path/name assumption — `loadConfig` renames `immersive_portals_fabric.json`→`immersive_portals.json`, `@Config(name="immersive_portals")` | **INFO** | verified-safe: config **file name** kept verbatim (not an identity lookup); no path breaks |
| F10 | 4 init-order/registry | `IPModInfoChecking.initClient`/`initDedicatedServer` have **zero call sites** (only `checkShaderpack` is wired, `PortalRenderer:420`) → the update-notification + incompat-warning feature is INERT in this port | **LOW** | disposition: dormant/deferred, non-blocking (see D.6) |
| F11 | 5 datapack / 6 null-env | `CustomPortalGenManager.init` (server start) + `GlobalPortalStorage` SavedData (world load) + `GcMonitor`/`IPGlobal` statics | **INFO** | verified-safe: no `data/immersive_portals/**` referenced by the core flag-ON path; IP ships no built-in `custom_portal_generation` datapack (empty = non-crashing); SavedData is on-demand; init-order guarantees non-null first use |

**Tally:** 11 findings · **6 actionable** (F1-F6) — F1-F3 already resolved at S13.8 and **confirmed present +
correct** by this audit, F4-F6 landed in the **S13-D final-fix round** · **5 verified-safe / deferred
dispositions** (F7-F11), zero code change.

---

## D.3 — THE FIXES (every actionable finding)

**Already committed at S13.8 (HEAD `c0e6bf6`) — confirmed by this audit, not re-touched:**
- `IPFeatureControl.java:16-18` (F1) — `getModContainer("seamlessportals").orElseThrow(…)`, with the
  self-identity re-host comment. Resolves in the loom runtime because `gradle.properties mod_id=seamlessportals`
  and `fabric.mod.json id=${mod_id}`.
- `O_O.java:144-145` (F2) `getImmPtlVersion` and `O_O.java:169-170` (F3) `getImmPtlVersionStr` —
  `getModContainer("seamlessportals").orElseThrow()`.

**Landed in the S13-D final-fix round (on disk, uncommitted):**
- **F4 — `O_O.java:180-181` `shouldUpdateImmPtl`** — the last residual literal
  `getModContainer("iportal").get()` → `getModContainer("seamlessportals").orElseThrow()` (+ re-host
  comment). This is the sole working-tree edit to a tracked source. Zero-deviation: string re-host +
  `.get()`→`.orElseThrow()` shape only (no signature change).
- **F5 — `common/src/main/resources/assets/immersive_portals/icon.png`** (new) — copied byte-for-byte from
  IP's `src/main/resources/assets/immersive_portals/icon.png` (licensing-internal to this port). Satisfies
  the `IPortalInitialScreen:57` `ImageWidget.texture(Identifier(immersive_portals, icon.png))` reference.
- **F6 — `common/src/main/resources/assets/immersive_portals/lang/en_us.json`** (new) — the full IP
  `en_us.json` shipped verbatim (286 keys, identical count to IP). Covers the `iportal.initial_screen.*`
  splash strings AND the whole previously-missing `imm_ptl.*` / `entity.immersive_portals.*` /
  `dimension.immersive_portals.*` / `text.autoconfig.immersive_portals.*` table.

All three re-host call sites (F1-F4) keep IP's exact semantics (query the host mod's own container for its
version / jar-in-jar status); only the mod **id** was re-pointed from IP's `"iportal"` to this port's
`"seamlessportals"`.

---

## D.4 — ASSET CENSUS VERDICT

**Before S13-D the port shipped ZERO `immersive_portals`-namespace assets** — only its own
`assets/seamlessportals/lang/en_us.json`. Cross-reading every asset the flag-ON init/first-frame path loads
against what is shipped:

- **`immersive_portals:icon.png`** — the ONLY init-path asset a live code path *hard-references*
  (`IPortalInitialScreen`, re-enabled S13.7). Missing → 26.2 renders the missing-texture (magenta/black)
  sprite on the once-only config-help splash (a broken visual, **not** a crash — 26.2 substitutes the
  missing-texture sprite and logs a warning). **NOW SHIPPED (F5), byte-identical to IP.**
- **`immersive_portals` lang table (286 keys)** — absent → every `imm_ptl.*` command message, the 10
  `entity.immersive_portals.*` display names for the registered portal entity family (`IPModMain:164-212`),
  the AutoConfig `text.autoconfig.immersive_portals.*` GUI labels, and the splash `iportal.initial_screen.*`
  strings all rendered as raw keys (cosmetic, but broad). **NOW SHIPPED (F6), the full IP file verbatim.**
- **Peripheral item/block models + textures** (`command_stick`, `portal_wand`, `portal_helper`, dimension
  textures) — **NOT needed at first light**: the port ships no `PeripheralModMain`, and
  `CommandStickItem`/`PortalWandItem` are **not registered** in the flag-ON boot path (the peripheral is
  compile-shells this stage; `ip-peripheral.mixins.json` = 0 mixins). No registered item/block lacks a model
  on the flag-ON path except the entity-family, which uses code renderers (`PortalEntityRenderer`), not
  models. Disposition: **out of scope for S13-D**, deferred with the peripheral.
- **`data/immersive_portals/**` (datapack)** — the port ships **none**; IP itself ships only two
  `dimension_type` jsons (`surface_type`, `surface_type_bright`) that belong to the **deferred
  `alternate_dimension`** peripheral, and **no built-in `custom_portal_generation` datapack** at all. So
  `CustomPortalGenManager.init` finds no built-in entries (empty = non-crashing) and nothing on the core
  flag-ON path references `data/immersive_portals/**`. Disposition: **verified-safe / deferred**.

**Verdict: the flag-ON boot + first-frame asset surface is now complete.** The one hard-referenced asset
(`icon.png`) and the full lang table are shipped verbatim from IP; every other `immersive_portals` asset is
either code-serviced (entity renderers), un-registered this stage (peripheral items), or genuinely
unreferenced (datapack) — all dispositioned, none crash-blocking.

---

## D.5 — SELF-IDENTITY SWEEP — COMPLETENESS STATEMENT

Independent full-tree sweep of **every** `getModContainer(` / `isModLoaded(` call site under
`common/src/main/java/qouteall` (the ported tree):

- **Self-identity literals** (`"iportal"` / `"immersive_portals"` / `"imm_ptl"` as a *loader* id): **4 total,
  all now re-hosted to `"seamlessportals"`** — `IPFeatureControl:17` (F1), `O_O:145` (F2), `O_O:170` (F3),
  `O_O:181` (F4). **Zero residual self-identity loader lookups remain on disk.** (The earlier Fable verdict's
  one open item — `O_O:180` — is the F4 fix, now landed.)
- **Legitimate other-mod lookups** (correctly left verbatim): `isModLoaded("porting_lib")`
  (`IPMixinPlugin:24`, `IPPortingLibCompat:19`), `"flywheel"` (`IPFlywheelCompat:14`), `"requiem"`
  (`RequiemCompat:32`), `"pehkui"` (`O_O:93`), `"quilted_fabric_api"` (`O_O:241`), and the **parameterized**
  `getModContainer(modId)`/`getModContainer(modid)` helpers (`O_O:111,206,234`, driven by external mod ids).
- **Kept-verbatim protocol/registry/data/lang namespaces** (deliberately NOT re-hosted, per the flip
  contract): the `immersive_portals:` entity-type + block registry keys (`IPModMain:157-212`), the
  `ImplRemoteProcedureCall`/`ImmPtlNetworkConfig` payload + config-phase channel ids, the
  `custom_portal_generation` registry key, `@Config(name="immersive_portals")` (config **file** name), and
  the `immersive_portals`/`imm_ptl`/`iportal` **lang keys** — these are wire protocol / save-compat /
  resource identifiers, not host-identity loader lookups, and re-hosting them would break protocol or
  save-compat. **Sweep is COMPLETE: no self-identity loader lookup is left unresolved or mis-hosted.**

---

## D.6 — REMAINING NON-BLOCKING / COSMETIC DISPOSITIONS

Carried forward, none first-light-blocking:

1. **F10 — dormant update-check wiring (`IPModInfoChecking.initClient`/`initDedicatedServer` unwired).** IP
   calls these from its client/dedicated entrypoints; this port does not (grep: zero call sites; only
   `checkShaderpack` is wired at `PortalRenderer:420`). Effect: no online update notification, no
   incompatible-mod warning chat, no preview-version warning — a **feature dormancy, not a defect or a
   crash**. Left as-is for S13-D (the whole online-check path pulls the `qouteall.fun` fetch + the version
   compare F3/F4 into a live path; wiring it is a deliberate future decision, not a bring-up blocker). Note:
   because it is unwired, F3/F4's version lookups are currently unreachable anyway — their fix is defensive.
2. **Cosmetic-only IP screen fidelity (already dispositioned in code).** `IPortalInitialScreen` drops the
   GONE `StringWidget.alignCenter/alignLeft/alignRight` (26.2 removed them; header/footer `LinearLayout`
   provides centering) — documented in-file (:61-65, :124), cosmetic-only on the peripheral info screen.
3. **The `O_O.java` production-safety note.** F4's `shouldUpdateImmPtl` is dev-shielded
   (`isDevelopmentEnvironment()` early-return at `O_O:175-177` runs before the lookup, so `runClient` never
   reached it), which is why it survived to S13-D; the fix makes it correct for a **production** build too,
   where the dev early-return does not fire.

No lang-key gaps remain after F6 (the full IP table ships); no other cosmetic asset gap is on the flag-ON
path (D.4).

---

## D.7 — VERIFICATION TIER

**Tier: Fable adversarial re-audit (verify-only) of the tracer census, THEN final-fix landing —
independently re-verified against ground truth.** Per the model-tier policy, S13 is a hard live-checkpoint
stage.

- **Every tracer claim re-verified** against the on-disk ported tree + `mc262-ref` + IP's
  `src/main/resources`: the self-identity sweep (D.5, full-tree grep), the crash chain (D.1, read through
  `IPModMain`/`IPConfig`/`IPFeatureControl`/`O_O`), the asset census (D.4, `find` over both resource trees +
  byte-compare of `icon.png` and key-count of `en_us.json` vs IP), and the reachability of each dev-env /
  datapack / null-env disposition (F7-F11).
- **Fix state on disk confirmed:** F1-F3 present + correct at HEAD `c0e6bf6` (S13.8); F4-F6 present in the
  working tree (the `O_O.java` edit + the two new `assets/immersive_portals/*` files). `git status`/`git
  diff` cross-checked — the sole tracked-source working-tree change is `O_O.java` (2 insertions / 1
  deletion, the F4 re-host).
- **Build-safety reasoning (gradle NOT run, per the S13-D task constraint):** the F4 change is a
  string-literal + call-shape edit with no signature change; F5/F6 are pure resource files (no Java). Neither
  can affect compilation, mixin signatures, or `:common:test` — so the green shipping×3 + `:common:test`
  state established at `c0e6bf6` is preserved by construction. This audit did **not** run the game, did
  **not** run gradle, and did **not** commit.

**Working-tree touch (S13-D):** 1 tracked source — `common/src/main/java/qouteall/imm_ptl/core/platform_specific/O_O.java`
(F4) — plus 2 new resource files —
`common/src/main/resources/assets/immersive_portals/icon.png` (F5) and
`.../immersive_portals/lang/en_us.json` (F6). No mixin, no build wiring, no `com.warwa` change, no
`mixins.json` edit.

---

## STATUS: S13-D INIT AUDIT COMPLETE

Flag-ON boot chain is self-identity-clean, asset-complete, and dispositioned end-to-end; first-light can
proceed to a live world. **Standing gate inherited:** any future stage that adds a flag-ON init call, a
self-identity loader lookup, or an `immersive_portals`-asset reference re-runs this init trace (D.1-D.5)
alongside the S13-C weave gate.
