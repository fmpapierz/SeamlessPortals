# Adversarial verification: `q_misc_util` slice docs (round 2 — current docs)

Verified docs (as of 2026-07-12, post-revision):
- INV = `migration/inventory/q-misc-util.md`
- MAP = `migration/api-map/q-misc-util.md`

Ground truth re-opened for every claim below:
- IP source: `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/q_misc_util/` (citations relative to this unless prefixed)
- 26.2 decompile: `C:/Users/warwa/ModDev/mc262-ref/` (citations relative to that root)

Method: re-derived every geometry/sign claim from IP source (never trusting the doc); re-opened every cited
26.2 file at the cited line; grepped the whole 26.2 tree for renames behind each GONE verdict; traced
dependency/flow claims in IP source; compared doc coverage against a full directory listing of the slice.

Note on the previous verify round: its R1 (INV §3.4 claiming "the reported normal always opposes the ray"
in `raytraceAABB`) is **fixed in the current INV** — §3.4 now states the mode-dependent normal correctly,
and I independently re-derived that corrected statement as true (see §1.4 below).

**Bottom line this round: no substantive claim refuted. 47 claims checked, 45 confirmed, 2 trivial
citation errors (one off-by-one import line, one swapped line-pairing). Severity: minor.**

---

## 0. Scope completeness — CONFIRMED

`find`-listing of `q_misc_util/**/*.java` returns **55 files**. Every one is covered by the inventory:
root 8 (3 cross-referenced to the network slice as declared: `MiscNetworking`, `ImplRemoteProcedureCall`,
`api/McRemoteProcedureCall`), `dimension/` 3, `ducks/` 1, `mixin/` 5, `my_util/` 33, `my_util/animation/` 5.
No silently skipped files. MAP's "zero-MC classes port verbatim, no rows needed" list accounts for the
classes without rows.

Version-context claim also confirmed: `gradle.properties:28` `minecraft_version=1.21.3`;
`fabric.mod.json:49` `"minecraft": ["1.21", "1.21.1"]` (stale, as INV says).

---

## 1. Geometry / sign / transform claims (re-derived from IP source)

### 1.1 Plane sign conventions — CONFIRMED
- Canonical ctor normalizes normal: `Plane.java:8-11`.
- `getDistanceTo(Vec3)` = `normal.dot(point.subtract(pos))` — signed, positive = normal side: `Plane.java:13-15`; scalar overload `:17-19`.
- `isPointOnPositiveSide` = distance > 0: `Plane.java:30-32`.
- Equation form `ax+by+cz+w > 0` with `W = −normal·pos`: comment `Plane.java:127-130`, `getEquationW()` = `-normal.dot(pos)` at `:143-145`.
- `rayTraceGetT` NaN guard `|normal·lineVec| < 0.00001`: `Plane.java:62-68`; segment clamp t∈[0,1]: `:85-98`; `interpolate` re-normalizes: `:117-121`.

### 1.2 DQuaternion order conventions — CONFIRMED (the load-bearing minefield)
- `hamiltonProduct(other)` doc comment "Equivalent to firstly do 'other' rotation and then do 'this' rotation": `DQuaternion.java:148-151`, formula `:152-167`.
- `combine(other)` = `other.hamiltonProduct(this)` with comment "firstly apply 'this' and then 'other'": `:169-172`.
- `getCameraRotation(pitch, yaw)` = `rotX(pitch).hamiltonProduct(rotY(yaw+180))`, doc "rotation applied to world for world rendering. Its inverse is the rotation applied to entity head": `:227-239`. Closed-form twin `getCameraRotation1` `:242-251`.
- `getPitchYawFromRotation → Tuple<Double,Double>` in degrees via atan2: `:343-359` (uses `net.minecraft.util.Tuple` — G1's IP-usage anchor confirmed).
- `matrixToQuaternion(x,y,z rows)` branch-on-largest-diagonal: `:361-414`; `fromFacingVecs(axisW,axisH)` = `matrixToQuaternion(axisW, axisH, axisW.cross(axisH))`: `:416-422`.
- Euler yaw negated at BOTH boundaries: `fromEulerAngle` uses `rotateY(-eulerAngle.y)` `:507-514`; `toEulerAngle` returns `Math.toDegrees(-result.y)` `:516-520`.
- `fixFloatingPointErrorAccumulation` snaps components within 1e-7 of {0,1,−1} then normalizes: `:465-491` (threshold literal 0.0000001 at `:477`).
- `fromTag` falls back to identity on non-CompoundTag or missing "x": `:445-458`; `toTag` doubles x,y,z,w `:436-443`.
- Basis extraction rotates unit X/Y/Z: `:530-540`; `isValid` = `|q·q| > 0.9`: `:542-544`.

### 1.3 AARotation / IntMatrix3 conventions — CONFIRMED
- `AARotation.multiply(other)` comment "firstly apply other, then apply this", 24×24 precomputed cache: `AARotation.java:106-119`.
- `dirCrossProduct` validates different axes (`Validate.isTrue(a.getAxis() != b.getAxis())`) and asserts non-null: `AARotation.java:88-97` (`Validate.notNull(result)` at `:95`).
- `IntMatrix3` row-vector `p * m`, "the left one gets applied first… different to the MC transformation [column vector]": `IntMatrix3.java:13-18`; rows are `Vec3i x,y,z` `:20-23`; `multiply(m)` — `this` applied first: `:47-54`; `toQuaternion` via `Vec3.atLowerCornerOf`: `:101-107`.

### 1.4 `Helper.raytraceAABB` normal-sign analysis (INV §3.4, revised text) — CONFIRMED, re-derived
- Origin-inside rejection ONLY when `boxFacingOutwards`: `Helper.java:124-130`.
- `testXPosi = (lineDeltaX > 0) ^ boxFacingOutwards` (and Y/Z twins): `Helper.java:134-136`.
- `normalXPosi = lineDeltaX <= 0` values are internal-only, fed solely to `getCollidingT` where the sign cancels (numerator `(planeOrigin−lineOrigin)·n` over denominator `lineDelta·n`, `Helper.java:97-115`): `:140-142` feeding `:144-149`.
- Returned normal is testXPosi-signed: `new Vec3(testXPosi ? 1 : -1, 0, 0)` at `Helper.java:157`; Y twin `:175`; Z twin `:193` — exactly as INV cites.
- Re-derivation of mode semantics: outwards + ray +X → testXPosi = true^true = false → min-X face, normal −X (opposes ray) ✓; inwards + ray +X → testXPosi = true → max-X face, normal +X (points ALONG ray) ✓. INV's "do NOT 'fix' the inward normal sign" warning is well-founded, and the previous round's refutation is properly incorporated.

### 1.5 Mesh2D grid packing — CONFIRMED
`gridCountForOneSide = 1 << 30`; grid = `round(coord * 2^30)`, clamped, packed as
`((gridXClamped & 0xFFFFFFFFL) << 32) | (gridYClamped & 0xFFFFFFFFL)` with the sign-extension warning
comment: `Mesh2D.java:34-47`. `fromTag` reads typed lists `tag.getList("pointCoords", Tag.TAG_DOUBLE)` /
`getList("triangles", Tag.TAG_INT)` at `Mesh2D.java:1697-1698` — anchors G8/C3/C5.

### 1.6 Small-type claims — CONFIRMED
- `IntBox.getCenter()` integer-divides (`Helper.divide(l.offset(h), 2)`) vs `getCenterVec()` = `(l+h+1)/2.0` exact: `IntBox.java:172-182`.
- `LineSegment.interpolate(a,b,progress)` is an instance method that ignores `this`: `LineSegment.java:6-11` (record decl `:5`).

---

## 2. Dependency / flow / lifecycle claims (traced in IP source)

### 2.1 Reverse dependencies into `imm_ptl.core` — CONFIRMED (one line-number nit → R1)
- `Helper` imports `qouteall.imm_ptl.core.McHelper` at `Helper.java:29`; `dimIdToKey(String)` → `McHelper.newResourceLocation(str)` in the `:504-510` region as cited.
- `DimensionIntId` imports `IPCGlobal`, `IPPerServerInfo`, `McHelper` at `DimensionIntId.java:17-19`; DimLib `qouteall.dimlib.api.DimensionAPI` at `:16`.
- `MiscNetworking` imports `ClientWorldLoader` — **at line 28, not 29** (line 29 is the `McHelper` import). Substance correct; citation off by one. (R1.)

### 2.2 Dimension⇄int id lifecycle — CONFIRMED
- Pinned vanilla ids OVERWORLD=0, NETHER=−1, END=1: `fillInVanillaDimIds`, `DimensionIntId.java:91-101` (exact literals `rec.add(Level.OVERWORLD, 0)` / `(Level.NETHER, -1)` / `(Level.END, 1)`).
- Early phase `iportal:early_phase` constant `:26-27`; `addPhaseOrdering(EARLY, DEFAULT)` + register, with the comment "make sure that dimension int id updates before global portal storage update": `:31-44`.
- `onServerStarted` builds map, sequential ids for `server.getAllLevels()`, stores on `IPPerServerInfo.of(server).dimIntIdMap`: `:74-89`.
- `onServerDimensionChanged` adds new ids, keeps 3 vanilla keys, `removeUnused`, broadcasts `DimIdSyncPacket` to every player: `:103-128`.
- `clientRecord` static `:29`; cleared on client exit `:46-54`; `getClientMap` non-null validation with the networking-thread message `:59-65`.
- `DimIntIdMap.removeUnused` DOES iterate `toIntegerId.keySet()` while calling `remove()` (which mutates `toIntegerId` via `removeInt`): `DimIntIdMap.java:93-102` — INV's "preserve exact behavior" warning is grounded.
- Payload id `imm_ptl:dim_int_id_sync`: `MiscNetworking.java:42`; handler writes `ClientWorldLoader.dimIdToDimTypeId` at `:121`; `registryOrThrow(Registries.DIMENSION_TYPE)` at `:56` (C9's IP anchor).

### 2.3 Mixins — targets and injection points CONFIRMED
- `MixinPlayerList_Misc`: `@Mixin(PlayerList.class)`, `@Inject(method="placeNewPlayer", at=@At(value="INVOKE", target="Lnet/minecraft/network/protocol/game/ClientboundChangeDifficultyPacket;<init>(Lnet/minecraft/world/Difficulty;Z)V"))`, sends `DimIdSyncPacket.createPacket(player.server)`: `MixinPlayerList_Misc.java:13-32` (`player.server` at `:29`) — G9's IP anchor confirmed.
- `MixinMinecraftServer_Misc`: `@Shadow @Final storageSource`; ctor `@Inject("<init>", RETURN)` whose handler names the old 8-arg ctor `(Thread, LevelStorageAccess, PackRepository, WorldStem, Proxy, DataFixer, Services, ChunkProgressListenerFactory)` — confirming C11's "old descriptor" claim from the handler params; sets `MiscGlobals.refMinecraftServer`; `@Inject("createLevels", RETURN)` handler takes `ChunkProgressListener` (confirming C12's old 1-arg form) → `DimensionIntId.onServerStarted`; duck `ip_getStorageSource()` impl — all at `MixinMinecraftServer_Misc.java:38-66`.
- `MixinGui_Overlay`: `@Mixin(Gui.class)`, `@Inject(method="render", at=@At("RETURN"))`, handler `(GuiGraphics, DeltaTracker, CallbackInfo)`, gates `!this.minecraft.options.hideGui` → `CustomTextOverlay.render` — G3/G4/G5's IP anchors confirmed.

### 2.4 Dead/vestigial claims — CONFIRMED by repo-wide grep
- `MiscGlobals.serverTaskList`: sole occurrence is its declaration (`MiscGlobals.java:12`); no `addTask`/`processTasks` caller anywhere.
- `ObjectBuffer`, `KeyedTaskList`, `ChangeAccumulator`: zero importers under `qouteall/imm_ptl`.
- `IEClientPacketListener_Misc` / `ip_setLevels`: zero references outside the mixin file itself.

### 2.5 Entrypoints & misc glue — CONFIRMED
- `MiscUtilModEntry.onInitialize()` calls exactly `ImplRemoteProcedureCall.init()`, `MiscNetworking.init()`, `DimensionIntId.init()`: `MiscUtilModEntry.java:8-15`.
- `MiscHelper.getWorldSavingDirectory` chain `((IELevelStorageAccess_Misc)((IEMinecraftServer_Misc)server).ip_getStorageSource()).ip_getLevelPath().path()`: `MiscHelper.java:112-117` verbatim.
- `Helper.createRunnableEvent` wraps `EventFactory.createArrayBacked`: `Helper.java:1424-1432` (F4 anchor).
- `CustomTextOverlay`: doc-comment contrasting `Gui#setOverlayMessage` (single-line) `:17-19`; `render(GuiGraphics, DeltaTracker)` `:66`; label width `guiScaledWidth − 20`; centered at `(guiScaledWidth/2, guiScaledHeight*0.75)`; profiler push `"imm_ptl_custom_overlay"`; pose push/pop; "parchment names are incorrect" comment — all present at `:96-133`. **Nuance:** there is a `renderAtBottomCenter` conditional with a `renderLeftAligned(10, 10, 9, 0xffffffff)` else-branch — but `renderAtBottomCenter` is `private static final boolean = true` (`CustomTextOverlay.java:30,111`), so INV's centered-only description matches live behavior, and MAP G7 maps `renderLeftAligned` anyway. Not a refutation; a 1:1 port should still carry the dead branch (fidelity rule).
- `GuiHelper.Rect.renderTextLeft` → `guiGraphics.drawString(font, text, (int)xMin, (int)yMin+5, -1)`: `GuiHelper.java:105-112` (the `+5` is omitted by both docs but nothing claims otherwise).

---

## 3. GONE verdicts (rename-hunted across the whole 26.2 tree)

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| G1 | `net.minecraft.util.Tuple` GONE | **CONFIRMED** | No `Tuple.java` anywhere in mc262-ref; zero `minecraft.util.Tuple`/`Tuple<` hits repo-wide. DFU `Pair` remains available (vanilla imports it, as MAP cites). |
| G2 | `Direction.fromDelta` GONE; `getNearest(int,int,int,orElse)` behavior-identical on unit vectors | **CONFIRMED + re-derived** | Zero `fromDelta` hits repo-wide. `Direction.java:327-340`: strictly-dominant-axis test, `orElse` on ties. For (±1,0,0)-type unit inputs the dominant-axis branch always fires → exact match. Call-site census: exactly 3 (`AARotation.java:80,90`, `IntMatrix3.java:58`); only `dirCrossProduct` asserts (`AARotation.java:95`) — the assert/no-assert porting note is exactly right. |
| G3 | `GuiGraphics` GONE; `GuiGraphicsExtractor` mechanism | **CONFIRMED** | Only `GuiGraphicsExtractor.java` under `client/gui/`. Ctor `(Minecraft, GuiRenderState, mouseX, mouseY)` `:116`; `guiWidth/guiHeight` `:128-133` (implemented as `Window.getGuiScaled*` — C15 cross-check); `pose()` returns JOML `Matrix3x2fStack` `:136`; `nextStratum()` `:140`; `text(Font,Component,int,int,int)` `:254` delegating to `:258` with `dropShadow=true`; `centeredText:266`; `textWithWordWrap:275`; `textRenderer() → ActiveTextCollector` `:1194`. |
| G4 | `Gui.render` GONE; hook is `Hud.extractRenderState` | **CONFIRMED** | No `render(` method in 26.2 `Gui.java`. `Gui.extractRenderState(DeltaTracker, boolean, boolean)` `Gui.java:148`; constructs extractor `:154`; calls `this.hud.extractRenderState(graphics, deltaTracker)` under `if (shouldRenderLevel)` `:155-157`. `Hud.extractRenderState(GuiGraphicsExtractor, DeltaTracker)` `Hud.java:221`, ends ~`:246` (next method decl at `:248`). `Gui.screen()` `:218`; `public final Hud hud` `:72`. |
| G5 | `Options.hideGui` GONE; `Hud.isHidden` | **CONFIRMED** | Repo-wide `hideGui` hits: ONLY the `ScreenEffectRenderer.submit` boolean param (`:58,:81`) — exactly as MAP says. `Hud.toggle()` `:207-209`, `isHidden()` `:211-213`, gating `!this.isHidden` inside extractRenderState (`:224,:234` region, seen in body), publishes `isHudHidden` `:222`. |
| G6 | `Minecraft.getProfiler()` GONE; `Profiler.get()` | **CONFIRMED** | Zero `getProfiler` in 26.2 `Minecraft.java`. `Profiler.get()` static at `Profiler.java:47`; vanilla usage `ProfilerFiller profiler = Profiler.get(); … profiler.push("gui")` at `Gui.java:149-152`. |
| G7 | `MultiLineLabel.render*` GONE; `visitLines` interface | **CONFIRMED** | `MultiLineLabel.java:115-119`: `int visitLines(TextAlignment, int, int, int, ActiveTextCollector)`, `getLineCount()`, `getWidth()` — no render methods. `create(Font, Component, int)` survives `:44-46`. `AlertScreen.java:53-55` matches MAP's quoted example verbatim. |
| G8 | `ListTag.getElementType()` GONE | **CONFIRMED** (one trivial cite-pairing nit → R2) | Zero `getElementType` hits repo-wide. `ListTag` is `final class … extends AbstractList<Tag> implements CollectionTag` `:16`; `@VisibleForTesting byte identifyRawElementType()` (package-private) `:192`; `getInt(int):278` / `getIntOr:282` / `getDouble(int):294` / `getDoubleOr:298`; `size():327`, `get(int):332`. |
| G9 | `ServerPlayer.server` now private | **CONFIRMED** | `private final MinecraftServer server` `ServerPlayer.java:232` (`public ServerGamePacketListenerImpl connection` at `:231`). `level()` covariant `ServerLevel` `:1731`; `ServerLevel.getServer()` public `ServerLevel.java:1278`. |

---

## 4. CHANGED and SAME verdicts (26.2 lines re-opened)

### CHANGED — all CONFIRMED line-exact
- **C1** `CompoundTag` Optional getters: `getByte:283`, `getInt:299`/`getIntOr:303` (`instanceof NumericTag` default-on-wrong-type, exactly as MAP says), `getLong:307/:311`, `getDouble:323/:327`, `getString:331/:335`, `getBoolean:367`/`getBooleanOr:371-373`. Headline range `:283-373` accurate.
- **C2** `getCompound:351`, `getCompoundOrEmpty:355`. **C3** `getList(String):359` (no type param), `getListOrEmpty:363`. **C4** `keySet():193`, `entrySet():197`. **C5** ListTag per-index Optional getters (see G8 row).
- **C6** `public record StringTag(String value) implements PrimitiveTag` `StringTag.java:8`; `asString() → Optional<String>` `:90-94`.
- **C7** `public final class Identifier` `Identifier.java:18`; `fromNamespaceAndPath:40`, `parse:44`, `withDefaultNamespace:48`, `getPath:100`, `getNamespace:104`.
- **C8** `ResourceKey.identifier()` `ResourceKey.java:64`; `create(registry, Identifier)` `:26`.
- **C9** `RegistryAccess.lookupOrThrow` default method `RegistryAccess.java:21`; vanilla usage `lookupOrThrow(Registries.LEVEL_STEM)` at `MinecraftServer.java:326`.
- **C10** `getUnitVec3i()` `Direction.java:375-377`; siblings `getUnitVec3():379`, `getUnitVec3f():383`.
- **C11** New ctor `MinecraftServer(Thread, LevelStorageAccess, PackRepository, WorldStem, Optional<GameRules>, Proxy, DataFixer, Services, LevelLoadListener, boolean propagatesCrashes, NotificationManager)` `MinecraftServer.java:311-323`; `super("Server", propagatesCrashes)` `:324` matching `ReentrantBlockableEventLoop(String, boolean)` `ReentrantBlockableEventLoop.java:6`; grep confirms exactly one `public MinecraftServer(` ctor — the "no descriptor needed if targeting all ctors" note holds.
- **C12** `protected void createLevels()` (no-arg) `MinecraftServer.java:421`; called from `loadLevel()` at `:402`, after `setModdedInfo` (`:401`) and before `forceDifficulty()`/`prepareLevels()` (`:403-404`). Timing claim (all levels exist at RETURN, pre-join) consistent with `getAllLevels()` `:1197`.
- **C13** `Hud.getFont()` `Hud.java:1276`; `Hud.setOverlayMessage(Component, boolean)` `:1225`; `Minecraft.gui` public `Minecraft.java:290`; `Gui.hud` public final `Gui.java:72`; `Minecraft.font` `:287`.
- **C14** `private final LevelStorageSource.LevelDirectory levelDirectory` `LevelStorageSource.java:458`; public `getLevelDirectory()` `:506-508`; `record LevelDirectory(Path path)` `:422`; `protected final … storageSource` `MinecraftServer.java:217` (duck-still-needed reasoning holds — protected ≠ public). (The aside that the field "was private" in 1.21.3 was not independently checkable — 1.21.3 vanilla not in scope — but it is inert: the port decision depends only on the verified 26.2 visibility.)
- **C15** `Window.getGuiScaledWidth/Height` `Window.java:487,491`; extractor `guiWidth()/guiHeight()` `GuiGraphicsExtractor.java:128-133` implemented as exactly those Window calls; `Minecraft.getWindow()` `:2815`.

### SAME — spot-checked line-exact, all CONFIRMED
- `Vec3`: fields `:41-43`, `atLowerCornerOf:45`, ctor `:65`, `(Vec3i)` ctor `:75`, `normalize:83`, `dot:88`, `cross:92`, `subtract:96`, `add:112`, `distanceToSqr:131`, `scale:152`, `length:180`, `lengthSqr:184`, `lerp:228`, `x()/y()/z()` `:305-315` — every positional cite in the table matches.
- `AABB.contains(x,y,z)` at `:263-264` is `>= min && < max` — **min-inclusive/max-exclusive confirmed**, so `Helper.boxContains`'s documented quirk carries.
- `BlockPos`: extends Vec3i `:32`, ctor `:58`, `offset(int,int,int):121`, `offset(Vec3i):125`, `subtract:129`, `betweenClosedStream:369`.
- `Direction`: `getAxisDirection:155`, `getOpposite:167`, `getStepX/Y/Z:247-255`, `getAxis:267`, `fromAxisAndDirection:287`, `get(AxisDirection,Axis):361`, `Axis.choose` abstract triple `:531-535`.
- `OctahedralGroup.rotate(Direction)` `OctahedralGroup.java:142`; `Rotation` constants with OctahedralGroup backing `Rotation.java:18-21`.
- `Mth.clamp(int):94`, `lerp(float):550`, `lerp(double):558`; `Unit.INSTANCE` `Unit.java:9`.
- NBT write side: `put:223`, `putInt:235`, `putLong:239`, `putDouble:247`, `putString:251`, `get:271`, `contains:275`; `DoubleTag.valueOf:46`, `IntTag.valueOf:44`; `Tag.TAG_INT=3:15`, `TAG_DOUBLE=6:18`, `TAG_COMPOUND=10:22`.
- Registry: `Registries.DIMENSION_TYPE:274`, `Registries.DIMENSION:308`; `Level.OVERWORLD/NETHER/END` `Level.java:95-97`; `dimensionType():952`, `dimension():960`; `Registry.getKey → @Nullable Identifier` `Registry.java:59`; `BuiltinDimensionTypes.OVERWORLD:8`.
- Threading: `BlockableEventLoop.isSameThread:43`, `scheduleExecutables:49`, `execute:98`; `MinecraftServer extends ReentrantBlockableEventLoop<TickTask>` `:192`; `Minecraft extends ReentrantBlockableEventLoop<Runnable>` `Minecraft.java:261`; `Minecraft.getInstance():2517`.
- Server: `levelKeys:1193`, `getAllLevels:1197`, `getPlayerList:1383`, `registryAccess() → RegistryAccess.Frozen:2005`; `PlayerList.getPlayers:817`; `placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` `PlayerList.java:142` with the `new ClientboundChangeDifficultyPacket(difficulty, locked)` anchor call at `:185` — verified to be INSIDE placeNewPlayer (no intervening method declaration between :142 and :199); packet is `record (Difficulty, boolean)` `ClientboundChangeDifficultyPacket.java:10`. The 1:1 mixin-port claim holds.
- GUI: `MultiLineLabel.create:44-46` + `@OnlyIn(Dist.CLIENT)` decompile note `:13-17` + `splitIgnoringLanguage:78`; `AbstractWidget.setWidth:181`, `setX:262`, `setY:272`; `Component.literal:135`, `empty:166`; `MutableComponent.append:48,52`; `DeltaTracker` interface `:8`, `getGameTimeDeltaTicks:12`.
- Networking: `CustomPacketPayload:13`, `record Type<T>(Identifier id):56`; `StreamCodec.of:21`; `FriendlyByteBuf.writeNbt:517`, `readNbt:534`; `ClientCommonPacketListener` exists.
- `ClientPacketListener`: `private Set<ResourceKey<Level>> levels` `:403`; public getter `levels()` `:2624` — the "setter still needs the mixin, getter no longer does" note is correct.

### Verdict-count arithmetic — CONFIRMED
G1–G9 = 9; C1–C15 = 15; F1–F6 = 6; SAME tables tally 12+5+5+13+5+4 = 44. Matches the header.

---

## 5. Refuted (both trivial, severity minor)

### R1 — INV §1: "`MiscNetworking` imports `ClientWorldLoader` (MiscNetworking.java:29)"
**REFUTED (citation only).** The import is at `MiscNetworking.java:28`; line 29 is
`import qouteall.imm_ptl.core.McHelper;`. The architectural substance (q_misc_util → imm_ptl.core reverse
dependency via ClientWorldLoader) is correct — `ClientWorldLoader.dimIdToDimTypeId` is written at
`MiscNetworking.java:121`.

### R2 — MAP G8 migration note: "`Tag.TAG_DOUBLE`/`TAG_INT` constants still exist … (`net/minecraft/nbt/Tag.java:15,18`)"
**REFUTED (pairing only).** Positionally swapped: `TAG_INT = 3` is at `Tag.java:15` and `TAG_DOUBLE = 6` is
at `Tag.java:18`. Both constants exist as claimed; MAP's own SAME-table row cites them correctly
(`Tag.java:18,15,22`).

---

## 6. Notes (not refutations)

- `CustomTextOverlay`'s compile-time-dead `renderLeftAligned` branch: see §2.5 — carry it in the 1:1 port.
- IP's `IntMatrix3.java:17-18` comment "Orthogonal matrices are symmetric" is mathematically wrong in
  general (orthogonal ⇒ inverse = transpose, not symmetric) — but that is IP's own comment; the docs do not
  repeat the erroneous part, and the row-convention claims are verified independently.
- Previous verify round's R1 (raytraceAABB normal prose) is confirmed FIXED in the current INV §3.4.

## Summary
- Checked: 47 nontrivial claims (9 geometry/sign re-derivations, 8 dependency/flow traces, 9 GONE hunts
  with rename sweeps, 15 CHANGED line checks, SAME-table spot-check batches, scope census, verdict
  arithmetic).
- Confirmed: 45. Refuted: 2 (both single-line citation slips with correct substance).
- **Severity: minor.** Nothing found that would misdirect the port — geometry conventions, API fates,
  mixin targets, and the GUI-rewrite architecture claims all held up under independent re-derivation.
