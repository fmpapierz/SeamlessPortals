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

---
---

# S13-H — THE DRIVER CORE LANDING (the last inert link on command→pixels)

**Stage S13-H of the entity-portal migration — the DEST-RENDER DRIVER CORE landed.** Every other link on
the command→pixels chain was already live-proven (S13-G / attempt-6): portals spawn + sync to the client,
the flag-ON dispatch fires (`AFTER_TRANSLUCENT_TERRAIN` → `PortalRenderer` lifecycle with the correct
`viewRotationMatrix`), `RendererUsingStencil` runs its full R5 choreography. The one missing link was the
INVOKE BODY: `MyGameRenderer.switchAndRenderTheWorld`'s dest render was a bare
`client.gameRenderer.renderLevel(getDeltaTracker())` that re-rendered the ALREADY-EXTRACTED MAIN-world
state — `WorldRenderInfo.cameraPos`/`cameraTransformation` consumed by NOTHING, so the portal window
showed the player's own view (= visually no portal). S13-H replaces that invoke with the driver core.

**Contract pointer.** This is the LANDING record for the design contract in
`port-notes/S13H-driver-core-design.md` (the ARCHITECTURE VERDICT §0, the exact invoke sequence §1, the
state-ownership map §2, the P3 lifecycle-tail homes §3, the invariant checklist §4, recursion/nesting §5,
the spec-decision register §6). Every §-reference below is to that contract. Ground truth also:
`CUTOVER_SPEC.md` §5 (the pairing invariants) + §6 (the atomic-cutover set),
`MOD:render/PortalContextSwitch.java:1533-2057` (the runtime-proven live block-era driver the core
re-expresses).

**Citation conventions:** as §S13-C/§S13-D — `IP:` = 1.21.3; `26.2:` = `mc262-ref`; `MOD:` = live
`com.warwa.seamlessportals`; `MOD-qouteall:` = the held/registered `qouteall.*` ports. Line numbers are
the current on-disk files.

---

## H.1 — ARCHITECTURE (as landed): the invoke is a stencil-direct DECOMPOSITION, not a recursive renderLevel

The contract's §0 ruled out "re-point state + recurse `gameRenderer.renderLevel`" on three source-grounded
disqualifiers (nested-framegraph blanking from `AFTER_TRANSLUCENT_TERRAIN`; the single main `FogRenderer`
WORLD-slot + depth-clear corruption inside `renderLevel`; `GameRenderState.levelRenderState` being
`public final`). The landed core is the RE-EXPRESSION of IP's SEMANTICS — the same pass list (sky + clip +
opaque + entities + translucent + clouds, masked by the live stencil, from the transformed camera, with
vanilla terrain-visibility replaced by `VisibleSectionDiscovery`) — onto 26.2's decomposed mechanics
(extract → SOG delta feed → compileSections drain → armed discovery → `prepareChunkRenders` →
`renderGroup`), WITHOUT nesting a framegraph.

**Where it lives (the registered forced deviation, S11-B §1 / §0):** one NEW additive class,
`MOD-qouteall:render/SecondaryWorldRenderCore.java` (~700 lines), owning the §1 sequence + the §2.2
driver-core state. It has NO 1.21.3 analog (26.2 split extract from render, render-core G1) and is a
REGISTERED additive deviation like `VisibleSectionDiscovery`'s armed fold / `endFramePooled`, so the S20
diff-gate does not flag it. **DISCIPLINE BOUNDARY honored (I10 / S11-B §1):** no `com.warwa` TYPE appears
in any signature or field of the class — it reaches private vanilla members ONLY through the pre-existing
public `com.warwa` accessor-mixin INTERFACES (`GameRendererAccessorMixin`, `LevelRendererAccessorMixin`,
`LevelExtractorAccessor`, `CameraInvokerMixin`), exactly as the shell already does for the lightmap. The
live block-era driver (`PortalWorldManager`/`PortalContextSwitch`) is untouched and stays suppressed
flag-ON. **Zero `com.warwa` source files were edited this stage** — every edit is in the qouteall render
tree.

---

## H.2 — PER-STEP LANDING RECORD (§1 step → landed code)

The virtual-camera CONFIG (§1 Step 1) lands in the SHELL (`MyGameRenderer.switchAndRenderTheWorld`), right
after `new Camera()`, BEFORE the first-visit lightmap prime consumes the camera. Steps 2-10 land in
`SecondaryWorldRenderCore.renderDestWorld`, invoked in place of the bare `renderLevel`.

| §1 Step | What landed | Site |
|---|---|---|
| **Shell wiring** | invoke body `client.gameRenderer.renderLevel(...)` → `SecondaryWorldRenderCore.renderDestWorld(newWorld, worldRenderer, newCamera, renderDistance, oldWorld, oldCamera)` (IP's invoke SHAPE / `invokeWrapper` kept) | `MyGameRenderer.java:335-337` |
| **1 — camera CONFIG** | `ip_resetState(WorldRenderInfo.cameraPos, destLevel)` (cameraPos consumption #1) + `portal_setFocusedEntity` + `invokeSetRotation(originalCamera yRot/xRot)` + `tick()` (primes the camera's OWN `EnvironmentAttributeProbe`) + `setInitialized(true)` | `MyGameRenderer.java:215-229` |
| **1.6 — sampler capture** | `captureMainChunkSampler(client.levelRenderer)` at the OUTERMOST layer (`getPortalLayer()==1`) while `mc.levelRenderer` is still the TRUE main renderer | `MyGameRenderer.java:235-237` |
| **2 — per-dim substrate** | EXTRACTOR-IDENTITY router (DEFECT-1 fix, §H.4): `destDim==RenderStates.originalPlayerDimension ? mc.levelExtractor : WORLD_EXTRACTOR_MAP.get(destDim)`; renderer-state coherence assert (no-op by construction, defensive re-point kept); `sharedState` object-identity mode selector | `SecondaryWorldRenderCore.java:198-232` |
| **3 — dest view matrix + frustum** | `getViewRotationMatrix` → `TransformationManager.processTransformation` (**cameraTransformation consumption**, JOML column-form M·v, I6); dest projection = the UNBOBBED main extract projection; discovery frustum from the CONVENTIONAL-Z `buildCullingProjection` (I7 / §2.3, never the reversed-Z render projection); `setCullFrustum`+`setCapturedFrustum` (skips extract's `applyFrustum`) | `SecondaryWorldRenderCore.java:234-255` |
| **4 — dispatcher cam + cam state** | `dispatcher.setCameraPosition(destCameraPos)`; **[cross-dim]** `destCameraState = destLRS.cameraRenderState`; **[same-dim]** a core-owned scratch `CameraRenderState` REASSIGNED onto `destLRS.cameraRenderState` for the pass (the load-bearing "LevelRenderState re-point") + restored; `extractRenderState` then `viewRotationMatrix.set(destViewMatrix)` AFTER extract (R13k analog); bob/hurt zeroed | `SecondaryWorldRenderCore.java:257-306` |
| **5 — EXTRACT + SOG feed + drain [cross-dim]** | `destExtractor.extract(...)` in a `try`; in the `finally` the **SOG delta feed** (I2 §5.2, set-object identity window guard, memory `distant-chunk-vanish-sog-desync`) + the **`compileSections` drain** (I1 §5.1 pairing invariant, memory `ow-holes-consumed-compile-queue`) — never lost on throw. **[same-dim]** skipped (the main frame already extracted+drained; re-running would reset main state, I9) | `SecondaryWorldRenderCore.java:322-352` |
| **6 — dest FOG (R9)** | compute-only `fr.setupFog(...)` (never writes the WORLD ring-buffer slot, I8) → core-owned standalone `GpuBuffer` fog UBO (`writeFogSlice`, 48-byte std140; never `fr.updateBuffer`) | `SecondaryWorldRenderCore.java:354-366, 684-701` |
| **7 — dest PROJECTION** | `RenderSystem.setProjectionMatrix(writeProjectionSlice(destProjection), PERSPECTIVE)` (fresh 64-byte UBO per call, old never closed); the shell brackets it with per-invocation locals (V2-DEFECT-2 fix) | `SecondaryWorldRenderCore.java:368-373, 671-681` |
| **8 — Globals UBO** | `globalSettingsUniform.update(w,h,glint, destGameTime, dt, blur, destCameraPos, RGSS-flag)` (V1-M1 fix: mirrors vanilla's RGSS texture-filtering flag, not a hardcoded `false`); restored in the finally with the SOURCE game time + camera pos (V1-M2 fix: the IMMEDIATE OUTER context) | `SecondaryWorldRenderCore.java:375-387, 490-503` |
| **9 — ARMED discovery** | `VisibleSectionDiscovery.armCompileScheduling(destLevel, sut, cache, schedSet, 3ms)` then `discoverVisibleSections(..., new Frustum(destFrustum).offsetToFullyIncludeCameraCube(8), resultList)` — IP-verbatim call shape; result = the current renderer's live `visibleSections`; auto-disarms in its finally (P2 fix) | `SecondaryWorldRenderCore.java:389-406` |
| **10 — DRAW sequence** | `prepareChunkRenders(destViewMatrix)` (never bail on `maxIndices==0`); `setShaderFog(destFogBuffer)`; Row-16 `replaceFrameBufferClearing`; dest sky (gated on `doRenderSky`); inner-clip bracket (`FrontClipping.setupInnerClipping` + mirror cull + depth-clamp); `renderGroup(OPAQUE, mainChunkSampler)`; **[cross-dim]** dest diffuse lighting + entities; `renderGroup(TRANSLUCENT)`; nested `onBeforeTranslucentRendering(destViewMatrix)`; dest clouds; finally: clip/cull/clamp/fog restore + defensive stencil re-assert | `SecondaryWorldRenderCore.java:408-488` |
| **10 finally** | Globals-UBO restore (source context), source-dim diffuse restore, **[same-dim]** `cameraRenderState` reference + dispatcher-position restore / **[cross-dim]** `fogData`/`fogType` restore | `SecondaryWorldRenderCore.java:489-523` |
| **Helpers** | `renderPortalSky` (lazy size-tracked `SkyRenderer`), `renderPortalClouds`, `renderPortalEntities` (`invokeSubmitFeatures`+`renderAllFeatures`), `buildCullingProjection` (conventional-Z), `writeProjectionSlice`/`writeFogSlice`; `init()` registers cleanup on `CLIENT_CLEANUP_EVENT` + `CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT` | `SecondaryWorldRenderCore.java:527-701, 134-137`; registered `IPModMainClient.java:118-120` |

**SIGN discipline (D4.4, verified):** the core writes ZERO raw depth-compare / depth-range / stencil-op
constants — all R5 reversed-Z lives in the `RendererUsingStencil` choreography. The one depth-sensitive
surface (the discovery frustum) is conventional-Z (I7). No per-frame `LOGGER` on the render thread (I5,
memory `render-thread-logging-log4j-stall`).

**Same-dim (rung-1) entity gap (§6.1, ACCEPTED option (a)):** a SAME-DIM portal view renders
terrain+sky+clouds but NO entities at first light (the main pass already consumed+cleared
`entityRenderStates`; re-running `extract` on the main extractor is prohibited by I9). Cross-dim portals
render entities fully. Rung 1's purpose is the terrain window; entities-through-portals are the S18/C4
surface. Revisit after first light.

---

## H.3 — P3 LIFECYCLE-TAIL HOMES (each vs IP's `MixinGameRenderer` anchor)

| P3 | 26.2 flag-ON home | Status this stage |
|---|---|---|
| (a) `RenderStates.frameIndex++` (rotates `PortalRenderInfo.updateQuerySet`'s occlusion-query buffers; else synchronous occlusion-query stalls) | `MOD:MinecraftFramePumpMixin.java:82-86`, after the ported pre-render chain, level-guarded | **ALREADY LANDED** (verified present) |
| (b) `MyGameRenderer.endFramePooled()` (pooled `RenderBuffers` that never `endFrame()` = multi-second GL stalls, memory `gpu-buffer-leak-endframe`) | `MOD:GameRendererMixin.java:57-59`, `GameRenderer.render` TAIL after vanilla's own `renderBuffers.endFrame()`, flag-gated | **ALREADY LANDED** (verified present) |
| (c) `IPGlobal.PRE_TOTAL_RENDER_TASK_LIST.processTasks()` (drains `PortalRenderInfo` GC-disposal one-shots; else they accumulate unboundedly) | `MOD:MinecraftFramePumpMixin.java:62-66`, render pre-`update` pump ABOVE the level guard (IP order) | **ALREADY LANDED** (verified present) |
| (d) `RenderStates.onTotalRenderEnd()` → `GuiPortalRendering._onGameRenderEnd()` → `MyRenderHelper.lateUpdateLight()` (gated `IPCGlobal.lateClientLightUpdate`) | **NEW THIS STAGE:** `MOD-qouteall:MixinGameRenderer.seamlessportals$onAfterRenderingCenter` — `@Inject(method="render", at=@At(INVOKE `renderLevel(DeltaTracker)V`, shift=AFTER))` (`26.2:GameRenderer.render:425`; reached only when a level rendered, matching IP `MixinGameRenderer:127-142`), verbatim IP order | **LANDED** — `MixinGameRenderer.java:177-193` |

**Why the after-`renderLevel` anchor for (d):** `_onGameRenderEnd` prepares the GUI-portal framebuffers
the GUI pass consumes, so it must precede `guiRenderer.render` (`:443`); `onTotalRenderEnd` restores the
current dim's lightmap identity before GUI/next-extract; `lateUpdateLight` is frame-render-END per memory
`portalview-light-engine-half-port`, iterating `ClientWorldLoader`'s worlds — disjoint from the block-era
TAIL substrate's `PortalWorldManager` map (empty flag-ON, so no double-drive). `finishRendering()` STAYS
in the flag-ON dispatch callback (`SeamlessPortalsClientFabric:114`) — a no-op on `RendererUsingStencil`;
it MUST move to this anchor when the A1 FBO renderer is exercised (carried note, §6.6). The decomposition
no longer nests a recursive `renderLevel`, so this INVOKE matches ONLY the main call → fires ONCE per
frame (the S13-C weave gate applies: the `renderLevel(DeltaTracker)V` INVOKE was confirmed present).

---

## H.4 — W1 RESOLUTION (parent RULING 1): the Row-11/12 ALWAYS_PASS depth-compare variant

The contract's §4-W1 / §6.2 flagged the Row-12 exact-projected-depth restore
(`RendererUsingStencil.restoreDepthOfPortalViewArea`, IP op #12): its `glDepthFunc(GL_ALWAYS)` bracket
(Row 11) is clobbered by the GEQUAL portal-area pipeline that `applyPipelineState` installs, so the
restore depth-write was GEQUAL-gated against the content depth — near-equivalent in the common case but
wrong wherever dest terrain sits in FRONT of the portal plane, and NOT IP's contract. **Landed via the
4th-bit approach (the parent-approved, additive form):**

- `MyRenderHelper.java` — `PORTAL_AREA_TYPES` grown 8→16; `portalAreaKey` gained a 4th `alwaysPassDepth`
  bit (value 8); the register loop (now `key<16`) and the catch-fallback loop derive
  `alwaysPassDepth=(key&8)!=0` and pick `CompareOp.ALWAYS_PASS` vs `GREATER_THAN_OR_EQUAL` for the depth
  COMPARE (write is still `writeDepth`); a new 4-arg `getPortalAreaRenderType` overload
  (`MyRenderHelper.java:135, 144-149, 160-165, 181, 224-227, 258-262`).
- `ViewAreaRenderer.java` — a new 9-arg `renderPortalArea` overload carrying `alwaysPassDepth`; the 8-arg
  overload delegates with `false` (all existing callers unchanged) (`ViewAreaRenderer.java:45-70, 110-114`).
- `RendererUsingStencil.java` — `restoreDepthOfPortalViewArea` now passes `alwaysPassDepth=true` for THAT
  draw ONLY, so IP's op #12 exact-projected-depth lands unconditionally within the stencil region; the
  S11-B §8 WATCH-ITEM comment is updated to RESOLVED (`RendererUsingStencil.java:363-370`).

**Additive only:** every other consumer (Row-3/4 stencil-write, the Iris shells, `RendererDebug`,
`RendererUsingFrameBuffer`) routes through the 3-arg overload → `alwaysPassDepth=false` → the UNCHANGED
GEQUAL pipeline. Only the Row-11/12 restore passes `true`.

---

## H.5 — VERIFICATION TIER (dual adversarial verify + fix round; S13 is a hard live-checkpoint stage)

Two independent verify passes ran against the landed core; all findings were fixed in the fix round. NO
finding required a design change — the contract's §0 verdict, §1 sequence, and §2 ownership map held;
every fix is a wiring/fidelity correction WITHIN the designed shape (no dropped/reordered step, no
invariant breach, no IP-semantic displacement).

**Verifier 1 — TWO MINOR (non-blocking) defects, NO MAJOR:**
- **V1-M1 (Globals-UBO texture-filtering fidelity):** `renderDestWorld` passed a hardcoded `false` as the
  final `globalSettingsUniform.update` arg (`texFiltering`); the restore then cleared the flag for the
  rest of the frame. **FIXED:** mirror vanilla's RGSS flag
  (`optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS`, `26.2:GameRenderer.render:420`)
  at BOTH the dest-pass update and the source-context restore.
- **V1-M2 (nesting source-context restore):** the Globals-UBO game-time/camera-pos + diffuse-lighting
  restore captured the layer-0 `RenderStates.originalCamera`/`originalPlayerDimension`, which is correct
  at layer 1 but wrong under nesting (an inner pass would restore the layer-0 originals, not the immediate
  OUTER dest layer). **FIXED:** thread the shell's PRE-SWAP `oldWorld`/`oldCamera` (the immediate outer
  layer's world+camera) into the core as `sourceLevel`/`sourceCamera`; restore targets them. Identical at
  rung 1 (no change); correct under nesting. (Matches the proven core, which captured
  `mainCamera.position()`/`mc.level.getGameTime()` before the swap.)

**Verifier 2 — one MAJOR + one nesting-safety defect:**
- **DEFECT-1 (MAJOR — mis-wired link, would break cross-dim first light):** the Step-2 extractor-identity
  router resolved the WRONG extractor for every cross-dim portal. It called
  `ClientWorldLoader.getWorldExtractor(destDim)` from INSIDE the invoke, but the shell had already swapped
  `client.level` to the DEST (`MyGameRenderer.java:264`), so `getWorldExtractor`'s
  `CLIENT.level.dimension()==dimension` short-circuit (`ClientWorldLoader.java:378`) was ALWAYS true and
  returned `CLIENT.levelExtractor` (the MAIN global extractor bound to the MAIN `LevelRenderState`) for
  EVERY portal — collapsing cross-dim onto the same-dim path (`destLRS==main LRS ⇒ sharedState=true`) and
  silently skipping the entire dest extract + SOG delta feed + `compileSections` drain the core exists to
  add. **FIXED:** route the main-dim short-circuit by the TRUE main dim
  (`destDim == RenderStates.originalPlayerDimension`, invariant across nesting depth) → `mc.levelExtractor`;
  every other dim uses its construction-bound `WORLD_EXTRACTOR_MAP` instance (memory
  `nether-block-freeze-orphaned-extractor`). With correct routing the coherence re-point is a genuine
  no-op by construction (`SecondaryWorldRenderCore.java:198-210`).
- **V2-DEFECT-2 (projection save/restore nesting-safety):** the shell used
  `RenderSystem.backup/restoreProjectionMatrix()`, a SINGLE-SLOT static save — but the driver core now
  makes portal nesting LIVE (Step 10.10 `onBeforeTranslucentRendering` → nested
  `switchAndRenderTheWorld`), so an inner backup would overwrite the shared slot with the outer layer's
  projection and the outer restore would reinstate the wrong buffer for the main frame's tail. **FIXED:**
  per-invocation LOCALS (`getProjectionMatrixBuffer()`/`getProjectionType()` saved,
  `setProjectionMatrix(...)` restored) — recursion-safe, restoring IP's 1.21.3 local-save property
  (`MyGameRenderer.java:314-318, 345-347`).

**Parent RULING 1 (W1)** landed alongside (§H.4).

**Gate discipline (constraints honored):** ZERO IP-semantic deviation (re-express the MECHANICS not the
classes); AW/AT unchanged (no new access-widener/access-transformer — the core consumes only pre-existing
`com.warwa` accessor interfaces); flag-OFF surface byte-identical (the live block-era driver keeps running
flag-OFF, no `com.warwa` behavior change, I10 exclusivity); the game was NOT run; no git commit; logs to
scratchpad.

**Shipping gate GREEN after every edit phase:** `:common:compileJava :fabric:compileJava
:neoforge:compileJava :common:test` all BUILD SUCCESSFUL under the committed `ip_scc_closed=true` (the
closure is on-classpath, so the edited qouteall/render files were actually compiled — a bad
import/signature would have failed).

**Working-tree touch (S13-H):** 7 files, ALL in the qouteall render tree, zero `com.warwa`:
- **NEW:** `render/SecondaryWorldRenderCore.java` (the driver core).
- **EDITED:** `render/MyGameRenderer.java` (Step-1 camera config + sampler capture + invoke body + V2-DEFECT-2
  projection locals); `mixin/client/render/MixinGameRenderer.java` (P3(d) after-`renderLevel` handler);
  `render/MyRenderHelper.java` (W1 4th-bit family + 4-arg overload); `render/ViewAreaRenderer.java` (W1
  9-arg overload); `render/renderer/RendererUsingStencil.java` (W1 restore passes `true`);
  `IPModMainClient.java` (`SecondaryWorldRenderCore.init()` registration).

---

## H.6 — CARRIED FORWARD (the §6 spec-decision register, post-landing)

1. **Same-dim entity gap (§6.1)** — ACCEPTED option (a) for rung 1; cross-dim renders entities fully.
   Revisit after first light; the per-entity R3 bracket (`PerEntityClipBracket`/`CrossPortalEntityRenderer`)
   is the S18/C4 surface with the BOTH-mechanisms A/B switch.
2. **R9 FORM override (§6.4)** — the landed fog is the core-owned standalone buffer (CUTOVER_SPEC §3.2's
   proven FALLBACK), not the spec-preferred instance-per-dim `FogRenderer`. The §3.2 S13/S14 fog-flicker
   watch stays live; the per-dim-instance form is the escape hatch if it trips.
3. **`earlyRemoteUpload` required BY CONSTRUCTION (§6.5)** — the decomposition never runs `render()`'s
   upload tail, so the pre-frame pump is mandatory (already wired flag-ON). CUTOVER_SPEC §1.4's
   "land iff needed" clause is superseded → "landed, required by driver-core form" at the next spec touch.
4. **Weather + world border in portal views (§6.3)** — omitted (parity with the proven core). Trailing;
   flag if the S17 12-point regression list needs in-portal weather earlier.
5. **`finishRendering()` placement (§6.6)** — stays in the dispatch callback for the stencil renderer
   (no-op); MUST move to the §H.3(d) anchor when the A1 `RendererUsingFrameBuffer` is exercised.

## STATUS: S13-H DRIVER CORE LANDED — command→pixels chain complete; awaiting the USER runClient first-light gate

The last inert link is closed. The next action is the LIVE checkpoint: the user runs
`S13-FIRST-LIGHT-TEST.md` Part 1 (flag-ON, same-dim command portals) — the portal window should now show
the TRANSFORMED DESTINATION, not the player's own view. Carry the C4 A/B clip-switch note (§1.6) forward
to the entity-through-portal rungs.

---
---

# S13-I — FIRST PHOTONS (the eye-level black sliver: root cause + black-seam fix + the latent nested-layer deviation)

**Stage S13-I of the entity-portal migration — the FIRST TIME the ported engine drew the destination
world.** Attempt 7 (flag-ON, run against the S13-H driver core at HEAD `e817b57` / S13.15) is the
first-photons milestone: after six attempts that each detonated one inert-link landmine (weave S13-C,
self-identity S13-D, `@Redirect` collision S13.10, render-frame NPE S13.12, in-world crashes S13.13, the
still-inert invoke pre-S13-H), the portal window finally rendered SOMETHING. It was not right yet — but
"something instead of the player's own view / instead of a crash" is the command→pixels chain proving
end-to-end alive for the first time.

**USER SYMPTOM (verbatim):** *"something appeared, but its a very thin tiny line, a couple pixels tall at
eye level that disappears [at the] horizon if i fly up and only visible if i look at it straight on and its
just black."* Portal: a 3×3 same-dim command portal ~10-20 blocks away on superflat.

**Two-tracer triage.** Tracer A took the mission's leading hypothesis (the "couple-pixels black sliver at
eye level = the view-area quad reaching the GPU untransformed → collapse to a screen-center sliver") and
**REFUTED it for rung 1** (the transform feed is correct, §I.2) — but in doing so found the ONE genuine IP
deviation of exactly that genre, latent at rung 1 and live at S18 recursion (§I.5). The root-cause tracer
then **pinned the OBSERVED sliver to a different mechanism entirely**: the R5 Row-16 backdrop fill painting
the dest horizon seam BLACK (§I.3). Both halves are now fixed (§I.4, §I.5).

**Citation conventions:** as §S13-C/§S13-D/§S13-H — `IP:` = 1.21.3
(`ImmersivePortalsMod/.../qouteall`); `26.2:` = `mc262-ref` (Mojang mappings); `MOD-qouteall:` = the
held/registered `qouteall.*` ports (the live driver). Line numbers are the current on-disk files.

---

## I.1 — THE FIRST-PHOTONS MILESTONE (what attempt 7 proved)

Ground truth from the user's run (`fabric/runs/client/logs/debug.log`, `latest.log`):

- **The render chain ran to the dest draw.** 147 `SEAMLESS`-timer lines across the run — the S13-H
  decomposition (`switchAndRenderTheWorld` → `SecondaryWorldRenderCore.renderDestWorld` → the Step-10 draw
  sequence) executed each portal frame; no crash, no `PreparedFrame already in use` throw, no per-frame
  render-thread `LOGGER` spam (I5 discipline held — the core carries zero `LOGGER`, so its execution is
  visible only through the timer channel, exactly as designed).
- **The portal is same-dim, shift-20, superflat** (`debug.log:3483`, three invocations logged):
  `portal make_portal 3 3 minecraft:overworld shift 20` — overworld→overworld, a 3×3 opening whose dest
  viewpoint is offset 20 blocks. (The `Syntax exception for client-sided command` line is the FABRIC
  CLIENT command dispatcher declining it and passing through to the server `/portal` handler — normal, not
  a failure; the server-side command built the portal, which the 147 dest draws confirm.)
- **Why same-dim makes the symptom a single thin artifact.** With an overworld→overworld shift, the dest
  view is near-IDENTICAL to the surrounding world (a genuinely SEAMLESS window). Dest sky + dest terrain
  overpaint essentially the whole 3×3 opening, so the ONLY place any artifact can show is the razor-thin
  band where dest sky meets dest terrain and neither fully covers the backdrop — i.e. the horizon seam.
  That is the "couple pixels tall, at eye level, only head-on, gone when you fly the horizon off the small
  window" the user reported.

This milestone retires the S13-H STATUS ("the window should now show the TRANSFORMED DESTINATION"): it
DOES render the dest, and the remaining defect is a fill-color bug, not a missing-view bug.

---

## I.2 — TRACER A: the transform feed is CORRECT (the "untransformed-vertices sliver" hypothesis, REFUTED for rung 1)

The mission's interpretation guide named the classic signature (camera-relative/world vertices interpreted
in clip/NDC space collapse to a tiny sliver at screen center). Tracer A verified the whole feed and found
the outer portal stencil-write draw receives the CORRECT model-view and projection, and the mesh is a
valid full 3×3 camera-relative quad. Not assumed — grounded:

- **How the portal-area draw gets its matrices on 26.2.** The `POSITION_COLOR` pipeline the view-area quad
  uses has NO explicit uniform set (the GONE `portalAreaShader`, G9/G6). Its `ModelViewMat` is snapshotted
  from `RenderSystem.getModelViewMatrixCopy()` inside `RenderType.prepare()`
  (`26.2:RenderType.java:64` → the `DynamicTransforms` UBO), and its projection is bound by
  `PreparedRenderType.drawFromBuffer` (`26.2:PreparedRenderType.java:45-46` →
  `RenderSystem.getProjectionMatrixBuffer`) — both read the AMBIENT `RenderSystem` state at draw time.
- **What the ambient state IS at the dispatch.** The flag-ON `AFTER_TRANSLUCENT_TERRAIN` callback fires
  inside vanilla `LevelRenderer.frame.execute` (`26.2:LevelRenderer.java:239`), which sits INSIDE the
  `modelViewStack.mul(viewRotationMatrix)` bracket (`26.2:LevelRenderer.java:170-172` push, `:252` pop). So
  the ambient model-view = the camera `viewRotationMatrix` and the ambient projection = the world
  reversed-Z projection — precisely the matrices the S13.14 dispatch passes
  (`modelView = cameraRenderState.viewRotationMatrix`, the Fable-caught P1 fix from `019c52c`).
- **Proven precedent.** The block-era `PortalShapeRenderer.drawMesh` uses the identical camera-relative +
  `drawMesh` mechanism on the identical event and renders correct stencil masks. The mechanism is sound;
  the new outer path inherits it.

**VERDICT (Tracer A):** the sliver is NOT an untransformed outer view-area quad. The outer stencil write is
a full, correctly-placed 3×3 quad — which is exactly why the dest actually rendered. The first wrong link
is elsewhere.

---

## I.3 — ROOT CAUSE (pinned): the black sliver is the dest HORIZON SEAM, blackened by the R5 Row-16 backdrop fill

The visible black comes from the **backdrop the opening is sealed with before the dest world draws over
it**, not from any content:

- **The fill.** `RendererUsingStencil.replaceFrameBufferClearing`
  (`MOD-qouteall:render/renderer/RendererUsingStencil.java:82-102`) seals the WHOLE stencil opening with
  `FogRendererContext.getCurrentFogColor.get()` (`:95`), depth-OFF (`COLOR_FILL` →
  `portalCompositeBlit`), stencil-gated, full-screen, gated on `doRenderSky`. It is the R5 Row-16 step, and
  the driver core invokes it at **Step 10.3** (`SecondaryWorldRenderCore.java:432`) — BEFORE dest sky
  (Step 10.4, `:436-438`) and dest opaque terrain (Step 10.6, `:457`) paint over it.
- **SMOKING GUN.** The S12-B `MixinFogRenderer` stubbed `FogRendererContext.getCurrentFogColor = () ->
  Vec3.ZERO` (pure black), because 26.2's per-dim fog COLOR statics are GONE (R9) and the S11-A note
  declared `getCurrentFogColor` "superseded." But that supersession was incomplete: the Row-16 fill is the
  ONE surviving consumer of `getCurrentFogColor` (grep: sole call site
  `RendererUsingStencil.java:95`). So the opening's backdrop was literally `(0,0,0)`.
- **Why it presents as a thin eye-level line.** On the seamless same-dim view, dest sky + dest terrain
  overpaint the opening almost completely; the black backdrop survives only in the hairline horizon band
  where dest sky meets dest terrain and neither fully covers → a couple-pixels-tall black line at eye
  level, visible only near head-on (the plane's grazing profile), gone when flying the horizon off the
  small 3×3 window. Symptom fully explained.
- **The IP semantics that were lost.** On 1.21.3 IP had ALREADY set up the DEST fog before the portal dest
  render, so `getCurrentFogColor` returned the dest ATMOSPHERE color and the Row-16 fill was a genuinely
  SEAMLESS atmospheric backdrop (any seam blended into the dest sky/fog). The port kept the fill but lost
  its color source — a half-ported IP call (same lesson-shape as memory
  `portalview-light-engine-half-port`).

---

## I.4 — THE FIX (black-seam): publish the live dest fog color for the Row-16 fill

Re-express IP's "read the fog color the current world is being drawn with" onto the driver core as the
authority for the dest fog color (the statics IP read no longer exist). Three coordinated edits, zero IP
semantic change:

- **`FogRendererContext.java:62-83`** — a live per-layer published color: a `volatile Vec3
  currentRenderedFogColor` (defaults `Vec3.ZERO`) with `setCurrentRenderedFogColor` /
  `getCurrentRenderedFogColor`. This is the 26.2 re-expression of IP's `fogRed/fogGreen/fogBlue` statics
  that `getCurrentFogColor` read on 1.21.3.
- **`SecondaryWorldRenderCore.java:365-374` (Step 6)** — right after the core computes the per-layer
  `destFogData`, it PUBLISHES `destFogData.color` (the exact source `getFogColorOf` returns) via
  `setCurrentRenderedFogColor`. Step 6 runs well before Step 10.3's fill (same `renderDestWorld` body), so
  the fill always reads a fresh dest color — never the `Vec3.ZERO` default (which is consumed by nothing,
  since the fill only runs mid-dest-render, after a publish).
- **`MixinFogRenderer.java:41-48`** — `getCurrentFogColor` re-pointed from the `() -> Vec3.ZERO` stub to
  `FogRendererContext::getCurrentRenderedFogColor`. The Row-16 fill now seals the opening with the dest
  atmosphere color = IP's seamless backdrop; the horizon seam blends into it.

**Result:** the fill matches the dest sky/fog it sits behind, so the seam is no longer a black line — it
disappears into the dest atmosphere exactly as IP intended.

---

## I.5 — THE LATENT NESTED-LAYER DEVIATION (Tracer A's genuine find) — the sliver genre, fixed pre-emptively

The mission's "untransformed-vertices sliver" hypothesis was the RIGHT genre for the WRONG rung. Tracer A
found the one real IP deviation in the view-area draw:

- **The deviation.** IP's `ViewAreaRenderer.renderPortalArea` set BOTH matrices explicitly per call —
  `shader.MODEL_VIEW_MATRIX.set(modelViewMatrix)` (`IP:ViewAreaRenderer.java:87`) and
  `shader.PROJECTION_MATRIX.set(projectionMatrix)` (`IP:ViewAreaRenderer.java:88`) — so the quad
  rasterized with the PORTAL-PASS matrices REGARDLESS of what the surrounding passes left ambient. The
  26.2 port (portalAreaShader GONE) had dropped that per-call set and relied on the ambient `RenderSystem`
  matrices.
- **Why it is a NO-OP at rung 1** (hence NOT the observed sliver): at the outer layer the ambient already
  equals the passed matrices — the dispatch reads `cameraRenderState.viewRotationMatrix` (the same value
  the render pass left on the model-view stack) and `getCurrentProjectionMatrix` returns the ambient main
  projection (§I.2).
- **Why it WOULD reproduce the sliver at nesting (>=2):** `SecondaryWorldRenderCore` Step 10.10
  (`:478`) runs `onBeforeTranslucentRendering(destViewMatrix)` INSIDE
  `MyGameRenderer.switchAndRenderTheWorld`'s IDENTITY model-view bracket
  (`MyGameRenderer.java:316-318` — `pushMatrix()` + `identity()`). A nested view-area quad drawn there
  would snapshot IDENTITY into its `DynamicTransforms` UBO and rasterize untransformed = the eye-level
  sliver genre, live at S18 recursion. So the mission's hypothesis describes a REAL bug — just one the
  user could not have seen yet at rung-1 single-portal.
- **The fix** (`ViewAreaRenderer.java:115-203`) re-expresses `IP:87-88` verbatim: install the PASSED
  matrices on `RenderSystem` around the draw and restore both after, with RECURSION-SAFE per-call locals
  (mirroring `MyGameRenderer`'s projection bracket, V2-DEFECT-2). `modelViewStack.pushMatrix()` +
  `.set(modelViewMatrix)`; `setProjectionMatrix(writeProjectionSlice(projectionMatrix), ambientType)`;
  draw in `try`; `finally` `popMatrix()` + restore the saved projection slice/type. Only the matrix VALUE
  is overridden (IP's shader set only the matrix); the ambient `ProjectionType` is kept. A new
  standalone-UBO writer `writeProjectionSlice` (`ViewAreaRenderer.java:182-201`) mirrors the proven
  `SecondaryWorldRenderCore.writeProjectionSlice` idiom (64-byte std140 `USAGE_UNIFORM`, fresh core-owned
  `GpuBuffer` per call, old never closed — GPU may still read it — retained until the next call), kept in
  ITS OWN static field (`viewAreaProjGpuBuffer`) so a nested draw never disturbs the ambient
  dest-projection buffer this same draw saves+restores.
- **Family doc-sync** (comment-only, no logic): the two other `renderPortalArea` family members —
  `RendererDebug.java` and `RendererUsingFrameBuffer.java` — had comments asserting "renderPortalArea
  ignores the projection param (the pass reads the uploaded buffer)"; updated to "installs the passed
  projection onto RenderSystem's projection buffer around its draw (S13-I nested-layer fix)."

**Disposition:** fixed pre-emptively (the fix is inert at rung 1 by construction; its first LIVE exercise
is S18 recursion, unverified until then — carried §I.7).

---

## I.6 — VERIFICATION TIER + GATE DISCIPLINE

**Tier: dual-tracer triage (Tracer A transform-feed refutation + the root-cause pinning) + fix.** Every
load-bearing claim in §I.2-§I.5 was re-read this stage against ground truth: the 26.2 draw-time matrix
snapshot path (`RenderType.java:64`, `PreparedRenderType.java:45-46`), the `AFTER_TRANSLUCENT_TERRAIN`
model-view bracket (`LevelRenderer.java:170-172,239,252`), the sole `getCurrentFogColor` consumer
(`RendererUsingStencil.java:95`), the Step 10.3/10.4/10.6/10.10 sequence
(`SecondaryWorldRenderCore.java:432,436-438,457,478`), the identity bracket
(`MyGameRenderer.java:316-318`), and IP's per-call shader set (`IP:ViewAreaRenderer.java:87-88`). The
first-photons evidence is user-run ground truth (`debug.log:3483`, 147 `SEAMLESS` lines).

**Gate discipline (constraints honored):** ZERO IP-semantic deviation — the black-seam fix re-expresses
the fog COLOR source IP read from its statics; the nested-layer fix re-expresses `IP:87-88` verbatim onto
26.2 `RenderSystem` mechanics. Flag-OFF surface byte-identical (all six edits are inside held/registered
`qouteall.*` render files; the live block-era driver is untouched; no `com.warwa` change; no `mixins.json`,
AW/AT, or build wiring). **This record-append task did NOT run gradle and did NOT commit** (per the S13-I
task constraint). Build-safety reasoning: every edit is additive or comment-only with NO signature change —
`FogRendererContext` gains a field + two static methods; `SecondaryWorldRenderCore` gains an import + a
publish call; `MixinFogRenderer` reassigns a static field target; `ViewAreaRenderer` wraps its existing
draw + adds a private method/field (`renderPortalArea` signature unchanged); `RendererDebug`/
`RendererUsingFrameBuffer` are comments only — so the committed `ip_scc_closed=true` green shipping×3 +
`:common:test` state established at `e817b57` is preserved by construction (a bad import/signature would
have surfaced when the closure compiled the edited render files).

**Working-tree touch (S13-I):** 6 files, ALL in the qouteall render tree, zero `com.warwa`:
- `render/context_management/FogRendererContext.java` — live `currentRenderedFogColor` + setter/getter.
- `render/SecondaryWorldRenderCore.java` — Step-6 publish of `destFogData.color`.
- `mixin/client/multiworld_awareness/MixinFogRenderer.java` — `getCurrentFogColor` → the live publish.
- `render/ViewAreaRenderer.java` — per-call MODEL_VIEW/PROJECTION install (`IP:87-88`) + `writeProjectionSlice`.
- `render/renderer/RendererDebug.java`, `render/renderer/RendererUsingFrameBuffer.java` — doc-sync (comment-only).

---

## I.7 — CARRIED FORWARD

1. **Attempt 8 relaunch (the LIVE confirmation).** The user reruns `S13-FIRST-LIGHT-TEST.md` Part 1
   (flag-ON, same-dim `/portal make_portal 3 3 minecraft:overworld shift 20`). EXPECTED: a SEAMLESS dest
   window with NO black horizon seam. If a seam persists, suspect the fog-color publish timing (Step 6 must
   precede Step 10.3 — it does, same `renderDestWorld` body) or a dest-fog-color mismatch vs the dest sky
   (an R9 `setupFog` fidelity item, not a fill bug). Map any NEW symptom to the §1.3 R5 sign-flip table in
   `S13-FIRST-LIGHT-TEST.md`.
2. **The nested-layer matrix bracket (§I.5) is unverified LIVE.** It is inert at rung-1 single-portal; its
   first real exercise is S18 recursion (nested portals). Carry it as an S18 watch item: the recursive
   view-area draw is the first code to depend on the per-call MODEL_VIEW/PROJECTION install, and the
   sliver genre is exactly what a regression there would look like.
3. **Same-dim entity gap (§H.6.1) unchanged.** Rung-1 same-dim views render terrain+sky+clouds but no
   entities; S13-I touched neither the entity path nor that disposition.
4. **`getCurrentFogColor` is now single-consumer + single-producer.** Any future consumer of
   `FogRendererContext.getCurrentFogColor` (or a second Row-16-style fill) must ensure a dest-fog publish
   precedes it, or it reads the `Vec3.ZERO` default — record the invariant so the coupling is not
   re-severed.

## STATUS: S13-I FIRST PHOTONS — the eye-level black sliver is root-caused (Row-16 backdrop fill blackened by the `getCurrentFogColor` = `Vec3.ZERO` stub) and FIXED (live dest-fog-color publish); the latent nested-layer matrix deviation is fixed pre-emptively. Awaiting the USER attempt-8 relaunch to confirm the seamless dest view.

---

# S13-J — FIRST LIGHT CONFIRMED + rung-1 live-test triage (attempt 8: the working portal, the scaled-crossing physics verdict, the clouds fence-crash skip)

**Stage S13-J of the entity-portal migration — FIRST LIGHT IS CONFIRMED.** Attempt 8 (flag-ON at HEAD
`9870606` / S13.16, with the S13-I black-seam + nested-layer fixes) is the milestone the whole S13 arc was
built toward: **the user sees the destination view with correct parallax through a same-dim portal, and
the core interactions work.** The rung-1 checklist (`S13-FIRST-LIGHT-TEST.md` §1.2) passed on its load-
bearing steps, and the remaining findings are a triage list, not a broken engine.

**USER RESULT (verbatim, the passes):** the 3×3 same-dim window shows the transformed dest with correct
parallax from the front; **walk-through crossings WORK**; **live `set_portal_scale` / rotation / destination
updates WORK** (the view re-renders per change); **break/place THROUGH the window WORKS**. The S13-I black
horizon seam is **gone** (the dest-fog-color publish landed as designed — no black sliver reported).

This retires the S13-I STATUS ("awaiting the attempt-8 relaunch to confirm the seamless dest view"): it is
confirmed seamless. What follows is the rung-1 findings census (the checklist's remaining rows), each with
its verdict, and the two code fixes this stage put on disk.

**Citation conventions:** as §S13-C…§S13-I — `IP:` = 1.21.3 (`ImmersivePortalsMod/.../qouteall`); `26.2:` =
`mc262-ref` (Mojang mappings); `MOD-qouteall:` = the held/registered `qouteall.*` ports. Line numbers are
the current on-disk files. Run ground truth: `fabric/runs/client/logs/{latest,debug}.log` +
`fabric/runs/client/crash-reports/crash-2026-07-16_11.50.22-client.txt` and `…_11.58.54-client.txt`.

---

## J.0 — THE RUNG-1 FINDINGS CENSUS (map)

| # | Finding (user symptom) | Verdict | Disposition |
|---|---|---|---|
| — | Dest view + parallax + walk-through + live scale/rot/dest + break/place | **PASS** | FIRST LIGHT CONFIRMED |
| 1+2 | "circle portal doesn't work, only front view works"; "no dest portal to walk back through" | **EXPECTED IP behavior** (§J.1) | verified vs IP, documented in the test script |
| 3a | scale-2 crossing "forced me upwards, cannot return down, floating in air" | **NO CODE DEFECT — faithful IP scale-crossing physics** (§J.2) | documented; re-test after `set_portal_scale 1` un-scale |
| 3b | "white bar on bottom of the scaled portal" | **OPEN static diagnosis — NO fix on disk** (§J.3) | dest-content-coverage-vs-scaled-opening; not-live-confirmed |
| 6 | deterministic crash ×2: "Cannot wait on a fence for the current submit" (clouds) | **FIXED — dest clouds skipped** (§J.4) | documented deviation-until-S18 (weather/world-border precedent) |
| 7 | full log sweep for unreported ERROR/WARN/exception | **1 new item censused** (§J.5) | GL_INVALID_OPERATION at the stencil-FBO bind (the code's own deferred runtime-verify item) |

Two files changed on disk this stage: `render/SecondaryWorldRenderCore.java` (the §J.4 clouds skip) and a
U1 blockstate asset (`common/src/main/resources/assets/immersive_portals/blockstates/`, untracked) that
silences the §J.5 `Missing model for variant: immersive_portals:nether_portal_block` warnings. **No gradle
run, no commit** (per the S13-J task constraint).

---

## J.1 — FINDINGS 1+2: one-sided + one-way is EXPECTED (`make_portal` semantics, verified vs IP)

Both reports are the CORRECT behavior of a single `make_portal` portal; neither is a defect.

- **One-WAY (no return portal): CONFIRMED vs IP.** `/portal make_portal … shift 20` routes to
  `IP:PortalCommand.placePortalShift` (`PortalCommand.java:2317`) → `PortalManipulation.placePortal(width,
  height, player)` then a **single** `McHelper.spawnServerEntity(portal)` (`:2334`). Exactly ONE `Portal`
  entity is created; NO reverse portal. The absolute-dest form (`placePortalAbsolute`, `:2288`) is
  identical. The return portal is created ONLY by `/portal complete_bi_way_portal`
  (`IP:PortalCommand.java:530` → `PortalManipulation.completeBiWayPortal`, `PortalManipulation.java:79` →
  `createReversePortal`, `:88`). So "no destination portal to walk back through" after a bare `make_portal`
  is the designed one-way state.
- **One-SIDED (front-visible only): CONFIRMED vs IP, and OUR port matches exactly.** A flat portal is
  visible/renderable only from its front half-space. The gate is `Portal.isRoughlyVisibleTo(cameraPos)`
  (`IP:Portal.java:1379`) → `RectangularPortalShape.roughTestVisibility` (`IP:…/shape/RectangularPortalShape
  .java:161`), whose whole body is **`return localPos.z() > 0`** — a pure front-half-space test. Our port
  calls the identical `portal.isRoughlyVisibleTo(cameraPos)` in the render cull
  (`MOD-qouteall:render/renderer/PortalRenderer.java:216`). **Critically, this is NOT an angular/frustum
  cull:** any camera in the +z (front) half-space passes, at ANY oblique angle. So "only front view works"
  = invisible from BEHIND (correct), and there is **no oblique-front bug** — the mission's frustum-cull
  hypothesis (`earlyFrustumCullingPortal` over-culling grazing front views) is REFUTED: `roughTestVisibility`
  is a half-space dot, not a view-cone test, and `earlyFrustumCullingPortal` only culls on the portal's own
  thin bounding box (`PortalRenderer.java:233-240`), which a front-facing camera looking at the portal never
  fails. To see a portal from BOTH sides you use `/portal complete_bi_way_bi_faced_portal`
  (`IP:PortalCommand.java:548` → `completeBiFacedPortal`, `PortalManipulation.java:124`).

**Disposition:** no code change. Documented as EXPECTED in `S13-FIRST-LIGHT-TEST.md` (this stage's §2
amendment) with the IP citations, plus the `complete_bi_way_portal` return-trip step and the exact
`/portal global …` persistence syntax.

---

## J.2 — FINDING 3a: the scale-2 crossing is FAITHFUL IP PHYSICS, not a defect (dual-tracer verdict)

**PINNED (both tracers): NO PORT DIVERGENCE, NO CODE DEFECT in the scale-crossing chain.** The entire chain
was re-read 1:1 against IP this stage — `Portal.transformPoint` (scales the eye-offset-from-portal),
`ScaleUtils.onServerEntityTeleported`/`onClientEntityTeleported` (applies the PERMANENT 2× `SCALE` attribute,
drops the feet, scales the camera), `ServerTeleportationManager.onPlayerTeleportedInClient`,
`ClientTeleportationManager.teleportPlayer`, and the `McHelper` eye/bbox helpers — is a faithful port, and
26.2's `LivingEntity.getDimensions` correctly scales eye-height by the `SCALE` attribute, so the feet math
is right on both sides.

**The reported symptom IS the IP-correct result of a scale-2 crossing:**
- **(a) transformPoint scales the eye-offset.** IP's `transformPoint` scales the player's eye-offset-from-
  portal by the portal scale (2×). Crossing while flying ~1.3 blocks off the portal centre flings the eye up
  ~1.3 blocks — the "forced me upwards" jump.
- **(b) the player is now permanently a 2× giant.** The crossing correctly applies a permanent 2× `SCALE`
  attribute, so the camera now sits **~3.24 blocks** above the feet (vs the normal ~1.62). Even standing
  flat on the ground the camera is ~1.62 blocks higher than a normal player, and **nothing lowers it short
  of un-scaling**. "Cannot return back down / stay on a higher Y / floating in air" is the giant's eye
  height, not a stuck position: the player IS on/near the ground as a 2× entity; "descend" would only lower
  the camera to its permanent giant height.

**STATE IS BOTH-SIDES-CONSISTENT, NOT A DESYNC (§3a suspect iii REFUTED).** Client and server both applied
the 2× scale to the SAME transformed eye position — this is NOT the R12 anticheat/`moveTo` re-derivation
snapping the client, nor an R8 position-stamp mismatch, nor an R13 gravity/flying/noGravity flag corruption.
Proof: the client "Client Teleported Statically" line (`latest.log:4550`) is logged AFTER
`ScaleUtils.onClientPlayerTeleported` runs (the scale is applied client-side within the same crossing, so
the client's own downward-move rejections are just the giant already being grounded). Suspect (iv) (a scaled
`PortalCollisionHandler` box acting as an invisible floor) is also not implicated — the symptom is fully
explained by (a)+(b), and no collision-wall report accompanied it.

**Disposition:** NO code change (any "fix" would be an IP deviation — scaling portals are SUPPOSED to make
you a giant). This is a **UX-surprise, not a bug**. Re-test note added to the script: run
`/portal set_portal_scale 1` (or cross a scale-1 return portal) to un-scale; the giant eye height is the
expected state of a scale-2 crossing until then.

---

## J.3 — FINDING 3b: the "white bar on the scaled portal" — OPEN static diagnosis, NO fix on disk

After `set_portal_scale 2`, the user saw a white band at the bottom of the ENLARGED opening. With the S13-I
fix the opening backdrop is now the dest SKY/fog colour (white-ish on a superflat day), so a white band =
the **dest CONTENT failing to cover the bottom of the scaled opening**, with the S13-I atmosphere backdrop
showing through where it isn't covered.

**Static diagnosis (leading suspect):** the `ViewAreaRenderer` mesh scales WITH the portal (so the stencil
opening correctly enlarges to 2×), but the dest-view frustum / terrain discovery does NOT account for the
scaled opening extent — either `SecondaryWorldRenderCore`'s dest-view `CullingProjection`/frustum or the
`VisibleSectionDiscovery` radius seeded from the portal under-covers the enlarged opening's bottom band, so
the enlarged opening out-runs the dest content drawn behind it.

**IMPORTANT — this has NO FIX ON DISK.** The working tree contains ONLY the §J.4 clouds skip and the U1
blockstate asset; there is no white-bar change to re-derive. This remains an **OPEN, not-live-confirmed
static diagnosis**, carried to the re-test list (§J.7). It is lower-severity than 3a/6 (cosmetic, only on
scaled portals) and is deferred pending a live repro that pins mesh-extent vs frustum-extent vs discovery-
radius as the covering gap.

---

## J.4 — FINDING 6: the clouds fence crash — FIXED (dest clouds skipped; documented deviation-until-S18)

**Deterministic crash ×2, verbatim identical, FIXED on disk.** Both crash reports are the same stack:
`IllegalStateException: Cannot wait on a fence for the current submit` at
`GlCommandEncoder.awaitSubmit` ← `GlFence.awaitCompletion` ← `MappableRingBuffer.currentBuffer` ←
`CloudRenderer.render` ← `LevelRenderer.lambda$addCloudsPass$0` ← the MAIN frame `LevelRenderer.render:240`
(verified: `crash-2026-07-16_11.50.22-client.txt:7-14` and `…_11.58.54-client.txt`, the latter also a
`ReportedException: Render Frame` at `latest.log 11:58:54`). Both runs are same-dim overworld (Player315/468).

**Mechanism (confirmed from the crash frames + `mc262-ref`).** `MappableRingBuffer.currentBuffer()`
(`crash frame :42`) `awaitCompletion`s a `GlFence` tied to the IN-FLIGHT submit, and `rotate()` advances the
slot. On a **same-dim** portal `destRenderer == mc.levelRenderer`, so `destRenderer.cloudRenderer()` is the
**SAME `CloudRenderer`** whose `utb`/`ubo` `MappableRingBuffer`s the MAIN pass's `LevelRenderer.addCloudsPass`
draws into LATER **in the same framegraph submit**. Drawing dest clouds mid-submit (old Step 10.11) rotates/
fences those ring-buffer slots inside the current submit, so the main pass's `currentBuffer()`
`awaitCompletion` sees a fence for the in-flight submit → the throw. **This is the same shared-WORLD-ring-
buffer hazard `CUTOVER_SPEC §3.2` warned about for FOG** (solved there with a core-owned standalone buffer),
now manifesting in CLOUDS. Multiple portals multiply the mid-frame rotations — matching the user's "crash
correlates with multiple portals."

**The fix (on disk, `SecondaryWorldRenderCore.java`).** Step 10.11 **no longer calls `renderPortalClouds`**;
it is replaced with a loud, cited skip note (`:480-497`). The faithful re-expression method is RETAINED as
the S18 restoration reference — marked "INTENTIONALLY NOT CALLED (S13-J documented deviation)" +
`@SuppressWarnings("unused")` with a do-NOT-re-add-at-rung-1 warning (`:619-625`).

**Why SKIP is the right rung-1 disposition (fidelity order honored).** IP isolates per-dim cloud geometry
via `CloudContext` (ported at S11-A), but that class's own header defers reconciling its per-dim cache
against 26.2's single-`CloudRenderer` ring buffer to U10/S12 (still inert). Building that isolation now — the
IP-faithful option (1) — is disproportionate at rung-1 triage. So dest clouds are OMITTED **exactly like the
already-accepted weather + world-border omission** (`S13H-driver-core-design.md §6.3`), a documented
**deviation-until-S18**. The crash was UNACCEPTABLE either way; SKIP removes it with zero risk to the proven
first-light path. **Sky (Step 10.4) is unaffected** — it uses the core-owned `portalSkyRenderer`, not the
shared main renderer's ring buffers.

---

## J.5 — FINDING 7: full log sweep — one new item censused (the stencil-FBO GL_INVALID_OPERATION)

Swept `latest.log` (714 KB) + `debug.log` (1.7 MB) across the attempt-8 runs for every ERROR/WARN/exception,
excluding the known-benign set (`[-3,0]`/`[-2,1]` `ImmPtlChunkTickets` chunk-loading-failure noise, Realms/
`401 profile key pair`/`fetch user properties` offline-dev auth, modmenu icon). Census:

- **NEW (open, non-fatal): a HIGH-severity `GL_INVALID_OPERATION` at the stencil-FBO bind, ~100×/session.**
  `OpenGL debug message: … 'Framebuffer name must be generated before being bound.'` fired from
  `GL30.glBindFramebuffer` at `RendererUsingStencil.prepareRendering` (`RendererUsingStencil.java:173`,
  `latest.log:698-705` + ~94 repeats). Line 173 binds `StencilState.gameFboId` (guarded non-zero), and the
  GL error means that ID is not a validly-generated framebuffer NAME in this context. **This is the code's
  OWN deferred item surfacing:** the `:170-171` comment already flags "the precise render-time active-FBO
  selection … is an S13 rung-1 driver-core runtime-verify item; inert until then." It is now LIVE and
  under-verified. It is **non-fatal** (GL debug-callback log only; first light rendered correctly over it),
  but it is a real render-substrate item: the stencil ops should target the render-time active FBO (the live
  `StencilPortalRenderer` discovers it via `StencilState.lastBoundFbo`, per `:170`), not a cached
  `gameFboId` that GL does not recognize. Carried to §J.7 as an S13 rung-1 substrate follow-up.
- **`Missing model for variant: immersive_portals:nether_portal_block[axis=x/y/z]`** (`latest.log 11:50:52`,
  3×). Addressed on disk this stage by the untracked **U1 blockstate asset**
  (`common/src/main/resources/assets/immersive_portals/blockstates/`) — a resource-only add; no behavior
  change. (The block-era portal block's blockstate JSON was absent under the `immersive_portals` namespace.)
- **Everything else = known-benign** (auth 401s offline, Realms unreachable, `ImmPtlChunkTickets` chunk
  `[-2,1]`/`[-3,0]` load-failure noise). No new packet floods, no per-frame render-thread `LOGGER` spam
  (I5 discipline held), no `PreparedFrame already in use`, no R11 saved-data signatures.

---

## J.6 — VERIFICATION TIER + GATE DISCIPLINE

**Tier: live-test triage over user ground truth + dual-tracer physics verdict + one code fix.** The §J.2
scale verdict re-read the full crossing chain 1:1 vs IP (`Portal.transformPoint`, `ScaleUtils`, the two
`TeleportationManager`s, `McHelper`) and grounded the both-sides-consistency on `latest.log:4550`. The §J.1
one-sided/one-way verdict is grounded on the exact IP call sites (`PortalCommand.java:2317/2334/530`,
`PortalManipulation.java:79/88/124`, `RectangularPortalShape.java:161 = return localPos.z() > 0`) matched to
our identical `PortalRenderer.java:216` cull. The §J.4 clouds mechanism is grounded on both crash reports'
frames (`MappableRingBuffer.currentBuffer:42` → `CloudRenderer.render` → `addCloudsPass`) + `CUTOVER_SPEC
§3.2` + the `CloudContext` header's own U10/S12 defer. The §J.5 GL item is grounded on `latest.log:698-705`
+ the `RendererUsingStencil.java:170-173` self-flag.

**Gate discipline (constraints honored):** the ONE code fix is a documented deviation (dest clouds skipped,
weather/world-border precedent) — zero IP-SEMANTIC change to any live path; the faithful re-expression is
retained for the S18 restore. Flag-OFF surface byte-identical (the edit is inside the held/registered
`qouteall.*` render core; the live block-era driver is untouched; no `com.warwa` change; no `mixins.json`,
AW/AT, or build wiring). **This stage did NOT run gradle and did NOT commit** (per the S13-J task
constraint). Build-safety reasoning: the `SecondaryWorldRenderCore` edit deletes one method CALL and adds
comments + a `@SuppressWarnings` (no signature change — `renderPortalClouds` is now unused-but-present, which
the annotation silences); the U1 asset is resource-only. So the committed `ip_scc_closed=true` shipping×3 +
`:common:test` green state at `9870606` is preserved by construction.

**Working-tree touch (S13-J):** 1 tracked source + 1 untracked asset dir, zero `com.warwa`:
- `render/SecondaryWorldRenderCore.java` — Step 10.11 dest-clouds call removed (documented deviation);
  `renderPortalClouds` retained as the S18 restore reference (`@SuppressWarnings("unused")` + do-not-re-add
  note).
- `common/src/main/resources/assets/immersive_portals/blockstates/` (untracked, U1) — nether_portal_block
  blockstate asset; silences the §J.5 missing-model warnings.

---

## J.7 — CARRIED FORWARD

1. **Re-test list for the user (added to `S13-FIRST-LIGHT-TEST.md`):**
   - **Scale crossing (3a):** after a scale-2 crossing you ARE a 2× giant — run `/portal set_portal_scale 1`
     (or cross a scale-1 return portal) to un-scale before judging Y-position. The high camera is EXPECTED.
   - **Multi-portal stability (6):** re-run with MULTIPLE portals in view — the clouds fence crash must be
     GONE (no dest clouds is the accepted rung-1 look; clouds return at S18).
   - **Scaled-portal coverage (3b):** on a `set_portal_scale 2` portal, watch the BOTTOM band of the enlarged
     opening for the white bar — capture a screenshot if it persists (needed to pin mesh-extent vs frustum vs
     discovery-radius; §J.3).
2. **3b (white bar) is an OPEN static diagnosis with NO fix on disk.** First live repro + screenshot pins the
   covering gap; then decide the fix (dest frustum/discovery extent vs the scaled opening). Do not assume it
   is fixed.
3. **The stencil-FBO `GL_INVALID_OPERATION` (§J.5) is an S13 rung-1 substrate follow-up.** `RendererUsing
   StencilState.gameFboId` binds a name GL does not recognize (~100×/session, non-fatal). Resolve per the
   code's own `:170` note — target the render-time active FBO (`StencilState.lastBoundFbo`) rather than a
   cached `gameFboId`. It did not block first light but should not persist.
4. **Dest clouds are a documented deviation-until-S18** (§J.4), joining the weather + world-border omissions.
   The S18 restore requires per-dim `CloudContext` cloud-buffer isolation reconciled against 26.2's single
   `CloudRenderer` ring buffer (U10/S12 defer) — the retained `renderPortalClouds` is the reference; do NOT
   re-add the call at rung 1.
5. **Same-dim entity gap (§H.6.1) unchanged**, and the S13-I nested-layer matrix bracket (§I.5) remains
   unverified-LIVE until S18 recursion — S13-J touched neither.

## STATUS: S13-J FIRST LIGHT CONFIRMED — attempt 8 shows the transformed dest view with correct parallax through a same-dim portal; walk-through, live scale/rotation/destination updates, and break/place-through-the-window all WORK, and the S13-I black seam is gone. Rung-1 census: Findings 1+2 (one-sided/one-way) = EXPECTED IP behavior (documented); 3a (scale-2 "floating") = faithful IP giant-scale physics, NO defect (documented); 6 (clouds "Cannot wait on a fence" crash ×2) = FIXED by skipping dest clouds (documented deviation-until-S18); 7 sweep = 1 new non-fatal item (stencil-FBO GL_INVALID_OPERATION, the code's own deferred runtime-verify item). OPEN with no fix on disk: 3b (white bar on scaled portals — static diagnosis, awaiting live repro). No gradle, no commit.

## S13-K — re-test triage record (2026-07-16, no code changes; tree = 9870606/a50e87e)

**Round results:** multi-portal stability PASS (clouds fix live-confirmed); bi-way scale grow/shrink PASS;
R11 persistence PASS at save-file level (exactly one portal converted → `data/minecraft/global_portal.dat`
619B/1 portal in overworld, empty baselines nether/end; 5 entity portals via `entities/*.mca`; relog reload
confirmed in logs). Verdicts: **set_portal_scale geometry = byte-identical IP** (scaling transforms the view
+ crossing-scale only; grows NEITHER rectangle — the "buried portal" perception was real source-side grass in
front of the bottom band: same blocks = jump-over + render occlusion). **Giant speed = IP-faithful** (only
`minecraft:scale` applied; `computeMotionScale()` dead code in IP too). **Silent convert = IP-designed**
(no sendSuccess in IP; user-approved chat-feedback enhancement queued for S19/S20 polish).

**OPEN (the one rung-1 survivor): the white bar at scaling>1** — a stencil-direct dest-render depth/fill
artifact: the Row-16 backdrop fill survives in the bottom band where scaled dest terrain loses the reversed-Z
GEQUAL depth competition against residual source depth (user break-test: removing the source blocks removed
the bar). Three candidate causes (runtime stencil-write band rejection / FrontClipping clipping dest terrain
under scaling / DEPTH_CLEAR-vs-fill coverage mismatch) — **localization protocol = live A/B with
`enableClippingMechanism=false`** (tests the FrontClipping suspect directly) + screenshots. Deliberately NOT
blind-patched (3 unlocalized suspects, regression risk to the working multi-portal render).

**Minor fidelity glance queued:** reciprocal entity portal's `reversePortalId` left undefined after global
conversion (bookkeeping only; persistence/teleport intact).

---
---

# S13-L — RUNG-1 CLOSE-OUT: the white-bar CONVICTION + the S11-B scaled-clip refinement landed, the camera verdict, the buried-portal record

**Stage S13-L of the entity-portal migration — rung 1 is CLOSED.** The one rung-1 survivor S13-K carried
open (the "white bar" at the bottom of a scaled-portal opening, `§J.3`/S13-K) is now root-caused, fixed,
and the fix's math is derived sign-by-sign. The trigger was the **user's live A/B** that S13-K prescribed
(`localization protocol = live A/B with enableClippingMechanism=false`): **with `enableClippingMechanism=false`
the white bar is GONE.** That directly CONVICTS `FrontClipping` — the clip mechanism is clipping scaled-dest
terrain it must not — out of the three S13-K suspects (runtime stencil-write band rejection / FrontClipping /
DEPTH_CLEAR-vs-fill coverage mismatch). And it convicts *exactly the edge the port already predicted*: the
S11-B verifier's documented deferral (`S11B-render-drivers.md §3.1 L310-312` + item 4 `L584-585`, and the
`FrontClipping` class SIGN NOTE) — *"the mod's live path uses a rotation-only `viewRotation`; the scaling-portal
edge (model-view carries scale → `nView` non-unit → the signed distance is mis-scaled) is flagged for S13
driver-core, NOT fixed here."* That refinement is now DUE with a live conviction, and it landed.

**USER SYMPTOM (verbatim, S13-K carry):** on a `set_portal_scale 2` portal, a white band at the BOTTOM of
the enlarged opening; the user's break-test removed it by removing the source blocks in front of the bottom
band, and the A/B removed it by disabling the clip mechanism. Also, on the SCALED pair the user observed
*"when i move away from the portal, the portal render camera also moves away instead of being pinned to the
dest portal spot."*

**Citation conventions:** as §S13-C…§S13-K — `IP:` = 1.21.3 (`ImmersivePortalsMod/.../qouteall`); `26.2:` =
`mc262-ref` (Mojang mappings); `MOD-qouteall:` = the held/registered `qouteall.*` ports (the live driver).
Line numbers are the current on-disk files.

---

## L.1 — THE CONVICTION (why the A/B pins FrontClipping, and why it is the scaled-clip edge specifically)

The white bar is the **S13-I Row-16 backdrop fill** (`getCurrentFogColor` = the now-live dest sky/fog color,
S13-I) showing through in a band where the **dest terrain that should cover it is being clipped away**. The
A/B is the decisive discriminator:

- **`enableClippingMechanism=false` → bar GONE.** `FrontClipping.disableClipping()` / the whole feed is
  gated on `IPGlobal.enableClippingMechanism` (`FrontClipping.java:104,167`); with it off, `gl_ClipDistance[0]`
  is never fed a portal plane, so NO dest fragment is clipped — and the bottom band fills with dest terrain.
  That the bar vanishes precisely when the clip is disabled means the clip half-space was **rejecting dest
  terrain fragments inside the scaled opening** — i.e. the kept half-space `n·p_rel + c > 0` was evaluating
  with the WRONG boundary under scale.
- **Break-test agreement.** Removing the source blocks in front of the bottom band also removed the bar
  (S13-K) — consistent: with the clip mis-placed, the dest terrain that WOULD cover the band is clipped, so
  whatever is nearest wins the reversed-Z GEQUAL competition; remove the competing source depth and the
  backdrop no longer survives there. Both observations converge on "the clip boundary is displaced under
  scale," not on a stencil-write or depth-clear coverage gap.
- **Only-on-scaled.** The bar appears ONLY on `set_portal_scale > 1` portals; unscaled portals are seamless
  (first light, S13-J). A defect that manifests only under portal scale, on the clip plane, is the S11-B
  scaled-clip edge by elimination.

---

## L.2 — FIXCLIP: the on-paper derivation (sign-by-sign), and the landed fix

### L.2.1 — Ground truth: IP is scale-INVARIANT because it clips in WORLD space; we clip in EYE space

- **IP's kept half-space** (`getClipEquationInner`, ported verbatim `FrontClipping.java:237-255`):
  `n·p_rel + c > 0` in TRUE dest-world units, where `p_rel = worldPos − destCamPos`, `n` = unit dest-portal
  normal, `c = −n·(clipPoint + corr·n − destCam)`.
- **Why IP is scale-invariant.** IP's vanilla-terrain clip shader evaluates that half-space in WORLD space —
  `IP:shader_transformation.yaml:17`: `gl_ClipDistance[0] = dot(Position.xyz + ChunkOffset, {n,c}.xyz) +
  {n,c}.w`. The model-view SCALE never touches the clip: the plane is dotted against raw world position, so
  a scaling portal's `k` in the camera model-view is irrelevant to the clip test.
- **OUR shader evaluates in EYE space.** The proven 26.2 clip mechanism (`com.warwa…ShaderCodeTransformation`,
  the always-on `GlCommandEncoderClipMixin` upload) injects `gl_ClipDistance[0] = dot(ModelViewMat·pos,
  planeXYZ) + planeW`. The model-view is applied to the position BEFORE the dot — so any scale in
  `ModelViewMat` DOES touch the clip. This is the whole reason IP's world-space equation cannot be fed to
  our eye-space store verbatim under scale.

### L.2.2 — The feed site + the scaling model-view

- **Feed site (unchanged).** `SecondaryWorldRenderCore.setupInnerClipping(getActiveClippingPlane(),
  destViewMatrix, −ADJUSTMENT)` (`SecondaryWorldRenderCore.java:441-442`). `destViewMatrix`
  (`:237-243`) = `newCamera.getViewRotationMatrix` ∘ `TransformationManager.processTransformation`, and the
  SAME matrix feeds `destRenderer.prepareChunkRenders(destViewMatrix)` (`:424`, the dest TERRAIN vertices).
  So the clip and the terrain it must bound are rasterized under the IDENTICAL model-view — the covector
  transform inverts exactly what the terrain draw applies.
- **When the model-view carries scale.** A fuse-view scaling portal installs a UNIFORM scale `k = 1/s` on
  the camera model-view: `PortalRenderer.getPortalScaleMatrix = scale(1/portal.getScale())` when
  `shouldApplyScaleToModelView(portal) = portal.hasScaling() && portal.isFuseView()`
  (`PortalRenderer.java:383-396`). So `M = R·kI` with `det(M linear) = k³`.

### L.2.3 — The bug, exact

Old feed: forward column-form rotate `planeXYZ = M·n` (the S11-A anti-fix guard column form, correct for a
pure rotation). Under `M = R·kI` that gives `planeXYZ = k·R·n`. The shader then computes
`dot(M·p_rel, planeXYZ) + c = dot(k·R·p_rel, k·R·n) + c = k²(n·p_rel) + c`. The kept half-space boundary is
DISPLACED by `k²` — the clip plane sits at the wrong depth, clipping scaled-dest terrain that lies on the
kept side. That displacement IS the white bar. (For `k=1`, `k²=1` → no displacement → the unscaled seamless
case, which is why first light was clean.)

### L.2.4 — The fix, exact (INVERSE-TRANSPOSE of the covector, det≈1 fast path)

A clip normal is a **covector**; to survive a linear map `M` it transforms by `M⁻ᵀ`, not `M`. Feeding
`planeXYZ = M⁻ᵀ·n = (1/k)·R·n` makes the shader compute
`dot(k·R·p_rel, (1/k)·R·n) + c = (k/k)(n·p_rel) + c = n·p_rel + c` — the EXACT IP world-space half-space,
for `k>1` and `k<1` alike. `planeW = c` is unchanged (the correction term rides in `c`, a scale-free scalar
in this decomposition; IP's own `transformClipEquation` leaves the `w` component of the after-model-view
equation as the transformed constant — our `c` is already the pre-model-view constant the eye-space form
needs). This is precisely IP's `transformClipEquation` (its after-model-view equation) restricted to the
translation-free 3×3 block.

Landed in `MOD-qouteall:render/FrontClipping.java` — the ONE changed file (`+94/−19`), the qouteall BRIDGE
copy; the live `com.warwa…FrontClipping` store is **UNTOUCHED** (B1 single-store contract intact):

- **`rotateClipNormalToViewSpace(double[] beforeModelView, Matrix4f modelView)`** (`:201-223`) — the single
  place the world-space clip normal becomes the eye-space `planeXYZ`. It takes the linear 3×3 block
  (`new Matrix3f(modelView)`), computes `det`, and:
  - **`|det − 1| ≤ SCALE_DETECT_EPSILON` (`= 1.0e-3f`, `:101`)** → UNSCALED / rigid rotation: `R⁻ᵀ == R`, so
    the proven forward column-form rotate `new Vector4f(nx,ny,nz,0).mul(modelView)` IS the covector transform.
    **BIT-IDENTICAL to the first-light path** — no invert, no float drift, COLUMN FORM (anti-fix guard: never
    `mulTranspose`). A pure-rotation product's determinant carries only ~1e-6 float error, while any real
    portal scale `k=1/s` moves `det=k³` by ≥~3% for `s≳1.01`, so `1e-3` cleanly separates the two.
  - **else** (scaling model-view, `det = k³ ≠ 1`) → `linear.invert().transpose().transform(n)` = `M⁻ᵀ·n`.
- **Both consumers route through it** — so the fix is applied everywhere the clip normal is produced, with
  ZERO other call-shape change:
  - `feedViewSpacePlane` (`:166-177`) — the LIVE inner/outer clip feed to the `com.warwa` store
    (`setupInnerClipping`/`setupOuterClipping` → the dest-render Step-10.3 inner-clip bracket).
  - `toViewSpaceSnapshot` (`:367-376`) — the R3 per-entity capture variants
    (`captureOuterClipping`/`captureInnerClipping`, the `PerEntityClipBracket` Snapshot path); same eye-space
    covector transform, returning the Snapshot instead of writing the store.
- The vestigial IP `double[]` before/after-model-view equations (`transformClipEquation`, `:225-235`) are
  still computed for the held IP-contract getters but back the dead GL_CLIP_PLANE0 path (26.2-superseded);
  the LIVE feed is the view-space store.

### L.2.5 — S13-I nested-matrix interplay (cross-checked)

The S13-I `ViewAreaRenderer` fix (per-call MODEL_VIEW/PROJECTION install around `drawMesh`, `§I.5`) keeps
the shader's ambient `ModelViewMat == destViewMatrix` for the portal-area draw — so the covector transform
inverts EXACTLY the model-view the shader applies. The two fixes compose: S13-I guarantees the shader sees
the fed matrix; S13-L makes the clip normal the correct covector under that matrix. (Documented in the
`FrontClipping.java:71-74` class SIGN NOTE.)

---

## L.3 — CAMERAAUDIT: the dest camera MOVING with the player is IP-CORRECT (verdict: IP-identical, NO fix)

The user's *"the portal render camera moves away when I move away instead of being pinned to the dest portal
spot"* (observed on the scaled pair) is the **CORRECT window behavior**, verified 1:1 against IP and the
project's own block-era memory. NO code defect; NO fix.

- **Ground truth (IP).** The dest render camera is the PLAYER camera carried through `Portal.transformPoint`:
  `pos_dest = destPos + rotation(scaling·(pos − originPos))`. It MOVES with the player — that is what makes
  a portal a real window with parallax; a camera "pinned to the dest portal spot" would be a static painting.
- **Our chain is IP-identical.** The dest camera pos is `PortalRendering.getRenderingCameraPos()`, built into
  `WorldRenderInfo.Builder().setCameraPos(...)` (`PortalRenderer.java:304-306`) and consumed by
  `SecondaryWorldRenderCore` (`ip_resetState(WorldRenderInfo.cameraPos, destLevel)`, the §H.2 Step-1 camera
  config). It traces back to `Portal.transformPoint` on the player camera pos — the same construction IP uses.
- **The SCALE WRINKLE (why the user noticed it on the SCALED pair).** Through a scale-2 portal the `scaling·`
  factor in `transformPoint` makes the dest camera move **TWICE** the player's displacement. So "I move 1
  block, the view shifts as if I moved 2" is the *correct* scaled-window parallax — exactly what made the
  motion conspicuous on the scaled pair where it is invisible-because-correct on an unscaled pair. The scale
  factor is applied EXACTLY ONCE (in `transformPoint`), not dropped and not double-applied.
- **Project memory confirms.** The block-era lesson `portal-view-camera-transform` records that the dest
  render camera KEEPS `transformPoint` (1:1 translation = the correct locked parallax), and that switching it
  to `transformTeleportPoint` inverts depth parallax → the view SWIMS (tried and reverted). Pinning the camera
  would be the same class of regression.
- **Reachability caveat for the retest (action = verify at the A/B, NOT a code change).** The clip's fed
  model-view carries scale ONLY when `shouldApplyScaleToModelView = hasScaling && isFuseView`
  (`PortalRenderer.java:395-396`) — i.e. a FUSE-VIEW scaling portal installs `scale(1/s)` on the camera
  model-view. The user's `set_portal_scale 2` A/B was exactly that path, so the L.2 inverse-transpose branch
  is the branch under test. A non-fuse scaling portal does NOT install scale on the view matrix (the scale
  lives only in the camera-pos `transformPoint`), so it takes the bit-identical rotation-only fast path and
  its clip is unaffected — expected, and worth confirming at the retest if a non-fuse scaled portal is tried.

**VERDICT: IP-identical. No math, sign, or consumer defect in the camera chain.** The camera moving with the
player (2× on a scale-2 portal) is correct window parallax; document and explain, do not "fix."

---

## L.4 — RECORD: the "buried portal" is byte-identical IP placement (accepted rung-1 look)

Consolidating S13-K's geometry verdict: the "buried portal" the user perceived is **placement math
byte-identical to IP**, not a defect.

- `make_portal` places the frame at the player's location; the portal's BOTTOM row sits BELOW the grass line
  as placed (the bottom band is below the surface). S13-K confirmed `set_portal_scale` leaves the scaled
  portal's OWN crossable rectangle as placed. The "buried" perception was **real source-side grass in front of
  the bottom band** (same blocks = jump-over collision + render occlusion), not a mis-placed portal.
  **S13-M correction:** the earlier "grows NEITHER the source NOR the dest rectangle" absolute is WRONG for a
  COMPLETED pair — `complete_bi_way_portal`/`createReversePortal` spawns the REVERSE at `width*scale ×
  height*scale` (`PortalManipulation.createReversePortal:98-99`, reverse scaling `1/scale`), so a scale-2 3×3
  portal has a **6×6 reverse on disk** (S13-M save-data ground truth). The scaled portal itself is unchanged;
  its reverse is grown. The "bigger/buried after scaling" perception is real geometry on the reverse side plus
  the buried-band occlusion on the front side, not a defect.
- The below-ground band of the opening maps to **dest-UNDERGROUND rays** → the S13-I Row-16 backdrop fill /
  dest-underground terrain = an accepted rung-1 look. The user's "dig a hole and xray through it" observation
  is this SAME mechanism: looking through the below-ground portion of the opening shows the dest world's
  sub-surface, exactly as the placement geometry dictates.
- Disposition: **NO code change** — byte-identical IP placement; the below-ground band is dest-underground
  backdrop, an accepted rung-1 look. Documented in `S13-FIRST-LIGHT-TEST.md` (this stage's §2 amendment).

---

## L.5 — VERIFICATION TIER + GATE DISCIPLINE

**Tier: live A/B conviction (user ground truth) + on-paper sign-derivation + adversarial-verify of the landed
fix (S13 is a hard live-checkpoint stage).** The FixClip verdict re-derived the half-space EXACTLY from both
shader spaces (IP world-space `shader_transformation.yaml:17`; our eye-space `ShaderCodeTransformation`) and
the feed site (`SecondaryWorldRenderCore.java:237-243,424,441-442`), and confirmed the fix: *"No math, sign,
or consumer defect found — the fix is exact and the unscaled path is bit-identical."* The camera verdict
re-read the `transformPoint` chain 1:1 vs IP + the `portal-view-camera-transform` memory. The buried-portal
record is grounded on the S13-K geometry verdict.

**Green gate PASS (post-fix):** `:common:compileJava`, `:fabric:compileJava`, `:neoforge:compileJava`, and
`:common:test` ALL **BUILD SUCCESSFUL** under the committed `ip_scc_closed=true` (the closure is on-classpath,
so the edited `FrontClipping.java` was actually compiled — `JOML 1.10.8` resolved `Matrix3f`/`Vector3f`; a
bad import/signature would have failed). Logs: `scratchpad/s13l-build*.log`.

**Gate discipline (constraints honored):** ZERO IP-semantic deviation — the fix re-expresses IP's
`transformClipEquation` (the covector inverse-transpose) restricted to the translation-free block, DUE per
the S11-B documented deferral; the unscaled/non-fuse path is **BIT-IDENTICAL** (the det≈1 fast path keeps the
proven first-light render byte-for-byte — the regression risk was the first-light render, and it is untouched).
Flag-OFF surface byte-identical (the single edit is inside the held/registered qouteall BRIDGE
`FrontClipping.java`; the live `com.warwa…FrontClipping` store is UNTOUCHED; no `com.warwa` change, no
`mixins.json`, AW/AT, or build wiring). **This stage did NOT run gradle beyond the green gate, did NOT run the
game, and did NOT commit** (per the S13-L task constraint) — the orchestrator ships and commits.

**Working-tree touch (S13-L):** 1 tracked source, zero `com.warwa`:
- `render/FrontClipping.java` (`+94/−19`) — the S13-L class SIGN NOTE refinement block; `SCALE_DETECT_EPSILON`;
  `rotateClipNormalToViewSpace` (det≈1 rotation-only fast path / covector inverse-transpose branch); both
  `feedViewSpacePlane` and `toViewSpaceSnapshot` routed through it.

Plus the doc amendments (this record + `S13-FIRST-LIGHT-TEST.md` §2 re-test expectations, L.6).

---

## L.6 — CARRIED FORWARD (rung 1 CLOSED)

1. **Re-test (the LIVE confirmation of FixClip). — S13-M CORRECTION: this expectation was WRONG for a command
   portal.** The user reran `S13-FIRST-LIGHT-TEST.md §1.2 step 3` (a `set_portal_scale 2` portal with
   `enableClippingMechanism=true`) and the bar PERSISTED (clearing only on dig-out). Root cause: a
   `set_portal_scale` portal is NON-fuse (`fuseView=0`), and S13-L's covector clip refinement only engages a
   FUSE-view scaling model-view (`shouldApplyScaleToModelView = hasScaling && isFuseView`,
   `PortalRenderer:395-397`) — so **S13-L is INERT for command portals**, and "GONE with clipping ON" never
   applied to them. The bar on a command portal is the **buried-opening** look (Row-16 backdrop fill where
   source blocks occlude the below-ground band); **dig out the burying blocks and it clears** (source-depth
   occlusion, S13-K depth-competition — EXPECTED IP behavior, not a failed fix). The earlier
   `enableClippingMechanism=false` A/B removed the bar only because it disabled clipping wholesale. **S13-L
   stands but must be confirmed on a `fuseView=true` scaled portal, not `set_portal_scale`.** See the S13-M
   record at the end of this file.
2. **Camera parallax expectation.** On a scale-2 portal the dest view moves 2× the player's displacement —
   this is CORRECT (L.3). Document, do not re-file as a bug.
3. **The det≈1 fast path is the unscaled guarantee.** Any future change to `rotateClipNormalToViewSpace` must
   keep the `|det−1| ≤ SCALE_DETECT_EPSILON` fast path bit-identical (it is the first-light render's clip
   feed). The inverse-transpose branch is exercised only by a fuse-view scaling model-view.
4. **Non-fuse scaling portals (untested).** The reachability caveat (L.3): a non-fuse scaling portal takes the
   rotation-only fast path (no scale on the view matrix). If a future rung exercises non-fuse scaled portals,
   confirm the clip there — the scale lives only in the camera-pos transform, so the fast path is expected to
   be correct, but it has not been A/B-convicted.
5. **S18 nested-recursion (unchanged carry).** The S13-I nested-layer matrix bracket (`§I.5`) and this covector
   transform first co-exercise under S18 recursion; the sliver genre + a scaled nested clip are the S18 watch.
6. **Rung 1 is CLOSED.** The rung-1 census (S13-J/§J.0) is fully dispositioned: FIRST LIGHT confirmed; findings
   1+2 (one-sided/one-way) = EXPECTED IP; 3a (giant scale) = faithful IP physics; 6 (clouds fence) = FIXED
   (skip-until-S18); 7 (stencil-FBO `GL_INVALID_OPERATION`) = the code's own deferred substrate item; **3b (the
   white bar) = FIXED here (the S11-B scaled-clip refinement).** Next: S14 (cross-dimension portals, rung 2).

## STATUS: S13-L RUNG-1 CLOSE-OUT — the white bar is CONVICTED (live A/B: `enableClippingMechanism=false` removes it) as the S11-B-deferred scaled-clip edge, and FIXED (the covector INVERSE-TRANSPOSE of the clip normal under a scaling model-view, `M⁻ᵀ·n = (1/k)·R·n`, cancelling the shader's `k²` half-space displacement EXACTLY for k>1 and k<1; det≈1 fast path keeps the unscaled render BIT-IDENTICAL). Camera verdict: IP-identical — the dest camera moving with the player (2× on a scale-2 portal) is correct window parallax, NO fix. Buried portal: byte-identical IP placement, the below-ground band = dest-underground backdrop (accepted rung-1 look). One file changed (qouteall bridge `FrontClipping.java`, +94/−19); flag-OFF `com.warwa` FrontClipping UNTOUCHED; green gate PASS on `:common`/`:fabric`/`:neoforge` compileJava + `:common:test`; game NOT run; NO commit. Rung 1 CLOSED; next is S14 (rung 2, cross-dimension).

## S13-L — the scaled-portal clip fix + camera verdict (2026-07-16)

**The A/B conviction landed its fix:** the S11-B-deferred scaled-portal FrontClipping refinement is
implemented (qouteall FrontClipping.java, +94/−19): the clip NORMAL now transforms via the proper
inverse-transpose under det≠1 model-views (`rotateClipNormalToViewSpace`), derived on paper from IP's
world-space half-space `n·p_rel + c > 0` (scale-invariant ground truth). Unscaled path bit-identical
(Fable-verified term-by-term); flag-OFF com.warwa FrontClipping untouched; 3-loader + test gate green.

**Camera-scale audit: NO DIVERGENCE** — the dest-camera chain (transformPoint/transformLocalVec/
getRenderingCameraPos → the 26.2 decomposition → SecondaryWorldRenderCore consumption) is byte-identical
IP, scaled and unscaled. The user-observed "camera moves away" = correct IP window parallax (scale-2 moves
the dest camera at 2× the player rate). The block-era swimming-bug transform is intact.

**RETEST CAVEAT (Fable):** the fed model-view carries scale only when `hasScaling && isFuseView`
(PortalRenderer:395-397, byte-identical IP). If the user's scaled pair is NOT fuse-view, this fix is
provably inert for it and the (certain) clip-side culprit has a different entry point — the closeout run
discriminates: bar gone = fixed; bar persists = next diagnosis is already narrowed to the non-modelView
clip path.

---

## S13-M — the view-bob "window head-bob" fix (Finding B) + the bar-is-expected verdict (Finding A) (2026-07-16)

The S13-L closeout run (user, live, clipping ON) returned two results. The RETEST CAVEAT above called both:

**NBT GROUND TRUTH (Finding A anchor — the mission's "GROUND TRUTH FIRST").** Read from the user's newest test
save `New World (16)`, overworld entities region `r.-1.0.mca` (save 2026-07-16 18:43): **4
`immersive_portals:portal` entities, data in nested `imm_ptl_portal_data`, ALL FOUR `fuseView=0`.**
- **#2 (the scale-2 portal under test):** origin `(-0.5, -58.5, 14.5)`; `width=3 height=3 thickness=0`;
  **`scale=2`, `fuseView=0` (FALSE), `teleportChangesScale=1`**; `axisW=(-1,0,0) axisH=(0,1,0)` ⇒ normal
  `(0,0,-1)` [faces −Z]; `dimensionTo=minecraft:overworld` (SAME dim); `destination=(-0.5,-58.5,34.5)`;
  `reversePortalId=#4`; `portalShape=rectangular`; window **y-extents `[-60.0, -57.0]`** (centered `-58.5`, h=3)
  ⇒ the bottom row sits **at/below the superflat surface** = the L.4 "buried bottom", located FACTUALLY vs
  terrain as the mission demanded.
- **#4 (#2's reverse):** origin `(-0.5,-58.5,34.5)`; **`width=6 height=6`, `scale=0.5`, `fuseView=0`**;
  `axisW=(1,0,0)` ⇒ normal `(0,0,+1)`. #2↔#4 bi-way, 20 blocks apart in +Z, same dim ⇒ **on-disk proof that
  `createReversePortal` grew the reverse to 6×6** for the scale-2 3×3 (the L.4 correction below).
- **#1, #3:** `scale=1`, non-fuse (a separate scale-1 bi-way pair, 20 apart).

This is the definitive reconciliation of the S13-K "byte-identical geometry" contradiction: because `fuseView=0`,
`shouldApplyScaleToModelView = hasScaling && isFuseView` (`PortalRenderer:395-397`) is FALSE ⇒
`getPortalScaleMatrix` returns null ⇒ the DRAW/model-view carries NO scale, and the view-area quad is built from
the RAW `width`/`height` (`RectangularPortalShape:149-152`). The DRAWN window is therefore **3×3, byte-identical
to the 3×3 crossable rectangle** — so the mission's leading candidate (a fuse-view scaled DRAW matrix rendering
the window at 2× = 6×6 visual) is **REFUTED at BOTH the flag AND the mesh**. The user's "bigger/buried after
scaling" is the CONTENT seen THROUGH a 3×3 window (more dest ground falls into the visible band as the dest
camera is displaced 2× via `transformPoint`), not a bigger frame. IP semantics match exactly: `set_portal_scale`
(`PortalCommand.java:730-736`) calls `portal.setScaling(scale)` ONLY — it never sets `fuseView`, so an IP scaled
command portal is likewise non-fuse.

- **(R1) The bar persisted** on the `set_portal_scale 2` command portal, clearing only on dig-out — i.e. the
  caveat's "bar persists" branch. **Verdict (Finding A): EXPECTED, no code change.** The user's portals are
  all `fuseView=0` (`set_portal_scale` produces non-fuse portals), so S13-L's covector clip fix — gated on
  `shouldApplyScaleToModelView = hasScaling && isFuseView` (`PortalRenderer:395-397`) — is **provably INERT
  for them**. The bar is the S13-I Row-16 backdrop fill showing through the BURIED below-ground band where
  source-side blocks occlude the dest terrain (source-depth occlusion = the S13-K depth-competition
  mechanism); digging them out clears it. This is the accepted "buried portal" look, byte-identical IP
  placement. Save-data ground truth also corrected the L.4 "grows NEITHER rectangle" absolute: a completed
  bi-way pair grows the REVERSE to `width*scale × height*scale` (`createReversePortal:98-99`) — a 6×6 reverse
  for a scale-2 3×3. S13-L's clip fix STANDS; it is exercised only by a `fuseView=true` scaled portal.
  **Disposition: DOC-only (P4)** — the S13-L "NO white bar with clipping ON" retest promise and the "grows
  NEITHER rectangle" claim are corrected in place (above) and in `S13-FIRST-LIGHT-TEST.md §1.2 step 3`, so
  the user does not re-file the bar as a failed fix.

- **(R2) A NEW defect: the dest view WOBBLES relative to the frame** ("like the window camera has a head-bob
  like the player"). **Verdict (Finding B): FIXED (P1/P2/P3).** 26.2 applies view-bob AND the nausea/portal
  spin to the PROJECTION, not the model-view (`GameRenderer.renderLevel:535-557`), while
  `cameraRenderState.projectionMatrix` is kept BOB-FREE (it is the extract-time cull base). IP's ambient
  `RenderSystem.getProjectionMatrix()` was `base*bob*spin`, and IP drew the stencil aperture / cull frustum /
  depth-restore AND the dest content with that same ambient, so aperture + content + frame all bobbed
  together. Our port had drifted off that in three ways:

  - **P1 — the aperture drew UNBOBBED.** `getCurrentProjectionMatrix()` returned the bob-free
    `cameraRenderState.projectionMatrix`, so `RendererUsingStencil` (stencil write :287-293, Row-11/12
    restore :362-370) and `PortalRenderer` (cull frustum :163-166) drew the aperture with an unbobbed
    projection while the frame + content bobbed → the aperture wobbled vs both. **Fix:**
    `getCurrentProjectionMatrix()` now returns `RenderStates.getPortalDrawProjection(base,
    getExtraModelViewScaling())` — the captured bobbed main-pass projection, scaled to the current layer.
    Centralized there, so ALL renderers (stencil/framebuffer/debug/iris) get it, matching IP's single
    `RenderSystem.getProjectionMatrix()` source. Signature unchanged → no caller edits.

  - **P2 — the capture was PRE-spin.** `MixinGameRenderer` captured at the `:542` bob multiply, on the FALSE
    premise "IP's dest excluded the spin". IP's dest re-enters the FULL `renderLevel` (`IP:MyGameRenderer:231`,
    no spin suppression anywhere in IP) and gets the SAME nausea/portal spin, so IP's dest is `base*bob*spin`
    too. **Fix:** the `@WrapOperation` moved to `ProjectionMatrixBuffer.getBuffer(Matrix4f)` ordinal 0 at
    `:557` (the POST-spin upload — the block-era `GameRendererObliqueClipMixin`'s proven interception point,
    now unregistered so no weave conflict). `spin==0` in normal play, so the normal-play wobble fix is
    unaffected; only the nausea/portal-overlay edge is corrected.

  - **P3 — the dest bob was UNSCALED.** IP's dest re-enters `renderLevel` under the pushed portal, so its A2
    bob ModifyArg multiplies the walk-bob translate by `viewBobFactor * getExtraModelViewScaling()` — a
    non-fuse scale-`s` portal IS in that product (`PortalRendering:113-121`), giving a `×s` bob so content at
    dest eye-depth `s*z` shifts on-screen by `s*t/(s*z) = t/z`, locking to the aperture's `t/z`. Our
    decomposition does NOT re-enter `renderLevel`, so `SecondaryWorldRenderCore` now derives the dest DRAW
    projection via `getPortalDrawProjection(destProjection, getExtraModelViewScaling())`, which scales the
    bob TRANSLATION by `s`. **Sign-derivation:** with the bob column of `B*SPIN` = `B`'s (SPIN has no
    translation) = `R_hurt·t` (IP's ModifyArg target), scaling that column by `s` is exactly IP's ModifyArg;
    on the composite it is `col3(Pdest) = s·col3(Pfinal) + (1-s)·col3(Pbase)` (the `(1-s)·col3(Pbase)` term is
    the affine bob column's `w=1` homogeneous correction), every other column unchanged. `s==1` (non-scaling /
    fuse-view) returns `Pfinal` bit-unchanged — so the first-light-confirmed unscaled path is untouched.

  The three form one coherent re-expression: `getPortalDrawProjection` is the single derivation used by BOTH
  the aperture (`getCurrentProjectionMatrix`, at the OUTER layer's scaling since the portal is pushed only
  around its own content) AND the dest content (`SecondaryWorldRenderCore`, at this-portal scaling) — so a
  nested aperture drawn into a dest pass (Step 10.10) and that pass's content use the identical projection.

**Working-tree touch (S13-M):** 5 tracked sources, ALL `qouteall.*` (flag-ON only; flag-OFF `com.warwa`
untouched — the D3 gate skips every `qouteall.*` mixin flag-OFF, and the capture site is the flag-ON
`MixinGameRenderer`):
- `render/context_management/RenderStates.java` — new `getPortalDrawProjection(base, extraScaling)` helper +
  `capturedMainPassBobbedProjection` javadoc (POST-spin).
- `render/renderer/PortalRenderer.java` — `getCurrentProjectionMatrix()` returns the bobbed+scaled draw
  projection (P1); signature unchanged.
- `render/SecondaryWorldRenderCore.java` — dest DRAW projection via `getPortalDrawProjection` (P3, P2).
- `mixin/client/render/MixinGameRenderer.java` — capture `@WrapOperation` moved to the POST-spin
  `getBuffer(Matrix4f)` upload (P2); handler ⑩ note updated.
- `render/ViewAreaRenderer.java` — corrected the false "NO-OP at the outer site" comment (P1).

Plus the doc amendments (this record + the L.4 / L.6.1 corrections above + `S13-FIRST-LIGHT-TEST.md §1.2 step
3`). **Green gate PASS:** `:common`/`:fabric`/`:neoforge` `build` + `:common:test` all BUILD SUCCESSFUL under
`ip_scc_closed=true` (the `@WrapOperation` target descriptor validated by the per-loader mixin AP). Logs:
`scratchpad/s13m-greengate.log`, `scratchpad/s13m-shipping.log`. Game NOT run; NO commit (orchestrator ships).

## STATUS: S13-M — Finding B (view-bob window-wobble) FIXED zero-deviation (P1 aperture returns the bobbed
draw projection; P2 capture moved POST-spin at renderLevel:557; P3 dest bob translation scaled by
`getExtraModelViewScaling()` — the exact IP ModifyArg re-expression, `col3(Pdest)=s·col3(Pfinal)+(1-s)·col3(Pbase)`,
`s==1` bit-identical). Finding A (the bar) = EXPECTED IP behavior on a BURIED non-fuse `set_portal_scale`
command portal (S13-L clip fix is `isFuseView`-gated → inert for command portals; dig-out clears it =
source-depth occlusion); DOC-only correction (P4), reverse portal is 6×6 by `createReversePortal`. 5 qouteall
sources changed; flag-OFF `com.warwa` untouched; green gate PASS on 3-loader `build` + `:common:test`; game
NOT run; NO commit.
