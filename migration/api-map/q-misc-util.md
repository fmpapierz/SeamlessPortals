# 26.2 API Map — `q_misc_util` library slice

**Inventory:** `migration/inventory/q-misc-util.md` (this doc covers its §4 touchpoint list, one row per touchpoint; mixin/duck targets from §2.6 included).
**26.2 ground truth:** decompiled vanilla at `C:/Users/warwa/ModDev/mc262-ref` (Mojang mappings). All 26.2 citations are relative to that root. IP citations are relative to `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall/q_misc_util/` unless prefixed.
**Cross-reference:** `migration/api-map/network.md` already maps the shared server/networking touchpoints (MinecraftServer ctor, createLevels, ServerPlayer.server, Identifier rename, Fabric networking v6 renames) — verdicts here independently re-verified and consistent.
**Verdict counts:** 44 SAME rows · 15 CHANGED · 9 GONE (vanilla) + 6 FABRIC-API/external rows (rows bundle related members; the SAME tables have 12 math + 5 NBT + 5 registry + 13 server/threading + 5 GUI + 4 networking rows).

**Headline findings (read first):**
1. **The math core ports verbatim.** `Vec3`, `AABB`, `BlockPos`, `Vec3i`, `Direction` (minus two members), `OctahedralGroup`, `Rotation`, `Mth`, `Unit` are all intact — the `Plane`/`DQuaternion`/`Mesh2D`/`IntBox`/`GeometryUtil` sign-convention minefield carries over untouched.
2. **The whole NBT read surface changed** (1.21.5-style rework is in effect): every `CompoundTag`/`ListTag` scalar getter now returns `Optional<T>`, with `getXxxOr(name, default)` twins (`net/minecraft/nbt/CompoundTag.java:283-373`). Every `toTag`/`fromTag` in this slice (`DQuaternion`, `IntBox`, `Mesh2D` — **savegame format**, `Helper.put/get*`, `DimIntIdMap`) must be mechanically rewritten while preserving IP's exact fallback values.
3. **`ResourceLocation` is renamed `Identifier`** (`net/minecraft/resources/Identifier.java:18`); `ResourceKey.location()` → `ResourceKey.identifier()` (`net/minecraft/resources/ResourceKey.java:64`). Cross-cuts `Helper.dimIdToKey`, `DimIntIdMap` NBT, `WithDim`, payload ids.
4. **The client GUI layer was rewritten around extraction.** `GuiGraphics` no longer exists as a class; the extraction-side object is `GuiGraphicsExtractor` (`net/minecraft/client/gui/GuiGraphicsExtractor.java`). `Gui.render(GuiGraphics, DeltaTracker)` is gone — HUD drawing is `Hud.extractRenderState(GuiGraphicsExtractor, DeltaTracker)` (`net/minecraft/client/gui/Hud.java:221`) driven from `Gui.extractRenderState` (`net/minecraft/client/gui/Gui.java:148-158`). `options.hideGui` is gone — the flag is `Hud.isHidden()` (`Hud.java:211`). `MultiLineLabel`'s render methods are gone — it is now a `visitLines(TextAlignment, anchorX, topY, lineHeight, ActiveTextCollector)` interface (`components/MultiLineLabel.java:115`). This changes **every** part of `CustomTextOverlay` + `MixinGui_Overlay` + `GuiHelper.Rect.renderTextLeft` except the data model.
5. **`net.minecraft.util.Tuple` is gone** (no file, zero uses in vanilla) — `DQuaternion.getPitchYawFromRotation` and `Helper`'s Tuple-returning helpers need a replacement pair type (DFU's `com.mojang.datafixers.util.Pair` is still on the classpath and used by vanilla, e.g. `com/mojang/blaze3d/vertex/UberGpuBuffer.java` imports it).
6. **`MixinMinecraftServer_Misc`'s two injection targets both changed:** the `MinecraftServer` ctor has a new arg list (`net/minecraft/server/MinecraftServer.java:311-323`) and `createLevels` is now **no-arg**, invoked from `loadLevel()` (`MinecraftServer.java:402,421`) — the `DimensionIntId.onServerStarted` hook point survives but its descriptor must be rewritten.
7. **`Minecraft.getProfiler()` is gone** — profiling is the static `Profiler.get()` (`net/minecraft/util/profiling/Profiler.java:47`); this is also how `Gui`/`Hud` self-profile (`Gui.java:149`).

---

## GONE (9 vanilla)

### G1. `net.minecraft.util.Tuple`
| | |
|---|---|
| **IP usage** | `DQuaternion.getPitchYawFromRotation(DQuaternion) → Tuple<Double,Double>` (my_util/DQuaternion.java:343-359); assorted `Helper` pair returns (Helper.java multiple). |
| **Verdict** | **GONE** — no `Tuple.java` anywhere in the 26.2 decompile; zero `Tuple<` usages in vanilla (repo-wide grep, 0 hits). |
| **26.2 mechanism** | Vanilla replaced Tuple call sites with records and DFU `com.mojang.datafixers.util.Pair` — DFU is still shipped and imported by 26.2 vanilla code (`com/mojang/blaze3d/vertex/UberGpuBuffer.java`, `com/mojang/blaze3d/platform/NativeLibrariesBootstrap.java`, `net/minecraft/client/data/models/BlockModelGenerators.java` all `import com.mojang.datafixers.util.Pair;`). |
| **Migration note** | Mechanical: swap the return/param type to `Pair<Double,Double>` (getters `getFirst()`/`getSecond()` vs Tuple's `getA()`/`getB()`) at every Tuple site in the slice. No semantics involved. |

### G2. `Direction.fromDelta(int, int, int)`
| | |
|---|---|
| **IP usage** | Three call sites: `AARotation.transformDirection` — converts a transformed unit vector back to a `Direction` (my_util/AARotation.java:78-85); `AARotation.dirCrossProduct` (my_util/AARotation.java:88-97); `IntMatrix3.transformDirection` (my_util/IntMatrix3.java:56-59). |
| **Verdict** | **GONE** — not present in `net/minecraft/core/Direction.java` (full static-method audit at `Direction.java:275-361`); zero `fromDelta` hits repo-wide. |
| **26.2 mechanism** | `Direction.getNearest(int x, int y, int z, @Nullable Direction orElse)` (`net/minecraft/core/Direction.java:327-340`): picks the strictly dominant axis, returns `orElse` on ties. For the **exact unit vectors** these two call sites always feed it (one component ±1, others 0), `getNearest(x, y, z, null)` returns exactly the matching direction — behavior-identical to `fromDelta` on this input domain. (`getApproximateNearest` `:303-320` also exists but is float-dot based; `getNearest` is the integer-exact one.) |
| **Migration note** | Use `Direction.getNearest(v.getX(), v.getY(), v.getZ(), null)`. Of the three `fromDelta` call sites, only `AARotation.dirCrossProduct` asserts non-null (`Validate.notNull(result)`, my_util/AARotation.java:95) — keep that one assert (it now also guards the tie case, impossible for unit vectors). The other two — `AARotation.transformDirection` (my_util/AARotation.java:78-85) and `IntMatrix3.transformDirection` (my_util/IntMatrix3.java:56-59) — return `Direction.fromDelta(...)` directly with **no assert**; port them as-is, without adding one. |

### G3. `net.minecraft.client.gui.GuiGraphics` (the whole class, incl. `drawString`, `pose().pushPose()/popPose()`)
| | |
|---|---|
| **IP usage** | `CustomTextOverlay.render(GuiGraphics, DeltaTracker)` — pose push/pop + MultiLineLabel rendering (CustomTextOverlay.java:66-133); `GuiHelper.Rect.renderTextLeft` → `guiGraphics.drawString(font, text, x, y, -1)` (my_util/GuiHelper.java:105-112). |
| **Verdict** | **GONE** — no `GuiGraphics.java` exists in the 26.2 decompile (find: only `GuiGraphicsExtractor.java` under `net/minecraft/client/gui/`). |
| **26.2 mechanism** | `GuiGraphicsExtractor(Minecraft, GuiRenderState, mouseX, mouseY)` (`net/minecraft/client/gui/GuiGraphicsExtractor.java:116`) is the extraction-side drawing object; it records draw commands into `GuiRenderState` rather than drawing immediately. Text: `text(Font, Component, int x, int y, int color)` (`:254`, delegates to `:258` with `dropShadow=true` — same default as old `drawString`); `centeredText(...)` (`:266`); `textWithWordWrap` (`:275`). Pose: `pose()` returns a **JOML `Matrix3x2fStack`** (`:136`) — 2D-only; push/pop are JOML's `pushMatrix()`/`popMatrix()`. Dimensions: `guiWidth()`/`guiHeight()` (`:128/:132`). Text collector for label rendering: `textRenderer()` → `ActiveTextCollector` (`:1194`). |
| **Migration note** | `drawString(font, text, x, y, -1)` → `graphics.text(font, text, x, y, -1)` — 1:1 including the drop-shadow default (`:254→:234` chain). `pose().pushPose()`/`popPose()` → `pose().pushMatrix()`/`popMatrix()`. There is no Z translation in the 2D pose (Matrix3x2f); layering is via `nextStratum()` (`:140`). The profiler wrap moves to `Profiler.get()` (see G6). |

### G4. `Gui.render(GuiGraphics, DeltaTracker)` — the `MixinGui_Overlay` injection target
| | |
|---|---|
| **IP usage** | `MixinGui_Overlay` injects `@At("RETURN")` of `Gui.render` to draw `CustomTextOverlay` when `!options.hideGui` (mixin/client/MixinGui_Overlay.java:21-31). |
| **Verdict** | **GONE** — 26.2 `Gui` has no `render` method at all; it is a screen/overlay manager (`public @Nullable Screen screen()` `net/minecraft/client/gui/Gui.java:218`, `public final Hud hud` `:72`). HUD drawing is extraction-based. |
| **26.2 mechanism** | Per-frame flow: `Gui.extractRenderState(DeltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded)` (`Gui.java:148`) constructs the `GuiGraphicsExtractor` (`:154`) and, when `shouldRenderLevel`, calls `this.hud.extractRenderState(graphics, deltaTracker)` (`:157`). `Hud.extractRenderState(GuiGraphicsExtractor, DeltaTracker)` (`net/minecraft/client/gui/Hud.java:221-246`) extracts every HUD element, gating most on `!this.isHidden` (`:224,:234`). |
| **Migration note** | The faithful hook is `Hud.extractRenderState` `@At("RETURN")` — it receives the `GuiGraphicsExtractor` as a parameter (no local capture needed) and is the exact analog of the old Gui.render tail (runs only when the level renders, `Gui.java:155-158`). Gate on `!hud.isHidden()` (see G5) to preserve the `!options.hideGui` check. The old vanilla `Gui.setOverlayMessage` that CustomTextOverlay's doc-comment contrasts against is now `Hud.setOverlayMessage(Component, boolean)` (`Hud.java:1225`) — still single-line, so the class's reason to exist is unchanged. |

### G5. `Options.hideGui`
| | |
|---|---|
| **IP usage** | `MixinGui_Overlay` renders the overlay only when `!minecraft.options.hideGui` (mixin/client/MixinGui_Overlay.java:28). |
| **Verdict** | **GONE** — zero `hideGui` hits in `net/minecraft/client/Options.java`; the only repo-wide `hideGui` occurrences are a plain boolean parameter of `ScreenEffectRenderer.submit` (`net/minecraft/client/renderer/ScreenEffectRenderer.java:58,81`). |
| **26.2 mechanism** | The F1 state moved onto the HUD: `private boolean isHidden` with `Hud.toggle()` (`net/minecraft/client/gui/Hud.java:207-209`) and `Hud.isHidden()` (`:211-213`). `Hud.extractRenderState` itself gates its elements on `!this.isHidden` (`:224,:234`) and publishes the flag to the render state (`gameRenderState().guiRenderState.isHudHidden`, `:222`). |
| **Migration note** | Replace `minecraft.options.hideGui` with `minecraft.gui.hud.isHidden()` (`Gui.hud` is a public field, `Gui.java:72`; `Minecraft.gui` is a public field, `net/minecraft/client/Minecraft.java:290`). |

### G6. `Minecraft.getProfiler()` (and `ProfilerFiller` push/pop via the Minecraft instance)
| | |
|---|---|
| **IP usage** | `CustomTextOverlay.render` wraps itself in `minecraft.getProfiler().push("imm_ptl_custom_overlay")`/`pop()` (CustomTextOverlay.java:99-133). |
| **Verdict** | **GONE** — no `getProfiler` on 26.2 `Minecraft` (grep of `net/minecraft/client/Minecraft.java`, 0 hits). |
| **26.2 mechanism** | Static thread-bound accessor `Profiler.get()` → `ProfilerFiller` (`net/minecraft/util/profiling/Profiler.java:47`). Vanilla GUI code uses exactly this: `ProfilerFiller profiler = Profiler.get(); ... profiler.push("gui")` (`net/minecraft/client/gui/Gui.java:149-152`). |
| **Migration note** | `Profiler.get().push("imm_ptl_custom_overlay")` / `.pop()` — drop-in. |

### G7. `MultiLineLabel.renderCentered(GuiGraphics, int, int)` / `renderLeftAligned(GuiGraphics, int, int, int, int)`
| | |
|---|---|
| **IP usage** | `CustomTextOverlay.render` builds a cached `MultiLineLabel.create(font, component, width)` and calls `renderCentered` at (guiScaledWidth/2, height*0.75) (CustomTextOverlay.java:96-127). |
| **Verdict** | **GONE** — 26.2 `MultiLineLabel` is an interface whose entire API is `visitLines(TextAlignment, int anchorX, int topY, int lineHeight, ActiveTextCollector)`, `getLineCount()`, `getWidth()` (`net/minecraft/client/gui/components/MultiLineLabel.java:115-119`). No render methods. |
| **26.2 mechanism** | `create(Font, Component, int maxWidth)` **survives** (`MultiLineLabel.java:44-46`). Rendering: obtain an `ActiveTextCollector` from the extractor via `graphics.textRenderer()` (`GuiGraphicsExtractor.java:1194`), then `label.visitLines(TextAlignment.CENTER, centerX, topY, lineHeight, textRenderer)`. Vanilla example: `AlertScreen` does `ActiveTextCollector textRenderer = graphics.textRenderer(); this.message.visitLines(TextAlignment.CENTER, this.width / 2, 90, 9, textRenderer);` (`net/minecraft/client/gui/screens/AlertScreen.java:53-55`). `TextAlignment` enum at `net/minecraft/client/gui/TextAlignment.java`; `ActiveTextCollector.accept(x, y, FormattedCharSequence)` defaults at `net/minecraft/client/gui/ActiveTextCollector.java:33-53`. |
| **Migration note** | `renderCentered(g, x, y)` → `visitLines(TextAlignment.CENTER, x, y, 9, g.textRenderer())` (9 = vanilla line height, the old renderCentered default). `renderLeftAligned(g, x, y, lh, color)` → `visitLines(TextAlignment.LEFT, x, y, lh, collector)` — per-line color now rides `ActiveTextCollector.Parameters` (`ActiveTextCollector.java:29-45`) instead of a method arg. The in-code "parchment names are incorrect" comment (CustomTextOverlay.java:113) is obsolete — delete on port. |

### G8. `ListTag.getElementType()`
| | |
|---|---|
| **IP usage** | `Mesh2D.fromTag` validates `pointsTag.getElementType() == Tag.TAG_DOUBLE` / `trianglesTag.getElementType() == Tag.TAG_INT` before decoding (my_util/Mesh2D.java:1696-1728); `Helper.vec3FromListTag` similar (Helper.java:1465-1477). |
| **Verdict** | **GONE** as public API — 26.2 `ListTag` is heterogeneous (`public final class ListTag extends AbstractList<Tag> implements CollectionTag`, `net/minecraft/nbt/ListTag.java:16`); the only element-type probe is **package-private** `byte identifyRawElementType()` (`ListTag.java:192`). |
| **26.2 mechanism** | Per-element typed access returns `Optional`: `getDouble(int)` (`ListTag.java:294`), `getInt(int)` (`:278`), plus `-Or` twins (`:282,:298`); raw `Tag get(int)` (`:332`) for instanceof checks. |
| **Migration note** | Replicate IP's validation semantics ("null on malformed lists") by checking each element: e.g. decode via `getDouble(i)`/`getInt(i)` and treat `Optional.empty()` as the malformed case → return null, exactly matching IP's fromTag contract. Do **not** rely on a whole-list type check — it no longer exists. `Tag.TAG_DOUBLE`/`TAG_INT` constants still exist if needed for `get(i).getId()` comparisons (`TAG_DOUBLE = 6` at `net/minecraft/nbt/Tag.java:18`, `TAG_INT = 3` at `:15`). |

### G9. `ServerPlayer.server` (public field)
| | |
|---|---|
| **IP usage** | `MixinPlayerList_Misc` builds the login-time `DimIdSyncPacket` from `player.server` (mixin/dimension/MixinPlayerList_Misc.java:29-31). |
| **Verdict** | **GONE** as public surface — now `private final MinecraftServer server` (`net/minecraft/server/level/ServerPlayer.java:232`), no getter on `ServerPlayer`. |
| **26.2 mechanism** | `player.level().getServer()` — `ServerPlayer.level()` covariantly returns `ServerLevel` (`ServerPlayer.java:1731`); `ServerLevel.getServer()` is public (`net/minecraft/server/level/ServerLevel.java:1278`). Inside `MixinPlayerList_Misc`, `PlayerList.server` is also available (the mixin runs in `PlayerList`; vanilla uses `this.server` throughout, e.g. `net/minecraft/server/players/PlayerList.java:144`). |
| **Migration note** | Same verdict and fix as network.md §G3 (it owns the other two call sites). |

---

## CHANGED (15 vanilla)

### C1. `CompoundTag` scalar getters — `getDouble`/`getInt`/`getString`/`getLong` (+ `getBoolean`, `getFloat`)
| | |
|---|---|
| **IP usage** | Every NBT reader in the slice: `DQuaternion.fromTag` (my_util/DQuaternion.java:436-458, falls back to **identity**), `IntBox.fromTag` (my_util/IntBox.java:489-516), `Helper.getVec3d/getVec3i/getQuaternion/getUuid/parse helpers` (Helper.java:512-733), `Mesh2D.fromTag` (my_util/Mesh2D.java:1696-1728), `DimIntIdMap.fromTag` (dimension/DimIntIdMap.java:116-146). |
| **Verdict** | **CHANGED** — 1.21.5-style Optional rework. |
| **26.2 equivalent** | `Optional<Integer> getInt(String)` (`net/minecraft/nbt/CompoundTag.java:299`), `getIntOr(String,int)` (`:303`); `Optional<Long> getLong` (`:307`)/`getLongOr` (`:311`); `Optional<Double> getDouble` (`:323`)/`getDoubleOr` (`:327`); `Optional<String> getString` (`:331`)/`getStringOr` (`:335`); `getBoolean`/`getBooleanOr` (`:367-373`); `getFloat`/`getFloatOr` (`:315-321`). Wrong-typed tags read as `Optional.empty()` / the default (`instanceof NumericTag` guard, e.g. `:304`). |
| **Migration note** | Old getters returned **0/""/false on missing or wrong type** — the exact 1:1 mapping is the `-Or` variant with that zero default (`getIntOr(name, 0)`, `getDoubleOr(name, 0.0)`, `getStringOr(name, "")`). Where IP checks `contains(name)` first and falls back deliberately (e.g. `DQuaternion.fromTag` → identity when `"x"` missing, DQuaternion.java:443-448; `Helper.getWorldId` → OVERWORLD on error, Helper.java:519-525), port to the `Optional` form so the *fallback value stays IP's*, not vanilla's. Audit each of the ~12 fromTag/get* sites individually — this is savegame + wire format. |

### C2. `CompoundTag.getCompound(String)`
| | |
|---|---|
| **IP usage** | `DimIntIdMap.fromTag` reads the `"intids"` compound (dimension/DimIntIdMap.java:116-146); `Helper.listTagDeserialize` compounds. |
| **Verdict** | **CHANGED** — returns `Optional<CompoundTag>` (`net/minecraft/nbt/CompoundTag.java:351`); missing-key/wrong-type convenience is `getCompoundOrEmpty(String)` (`:355`, returns fresh empty compound). |
| **Migration note** | Old `getCompound` returned an empty compound on miss → `getCompoundOrEmpty` is the drop-in. |

### C3. `CompoundTag.getList(String name, int type)`
| | |
|---|---|
| **IP usage** | `Helper.getCompoundList(tag,name)` = `tag.getList(name, 10)` (Helper.java:688-690); `Mesh2D.fromTag` `getList(..., Tag.TAG_DOUBLE)` (my_util/Mesh2D.java:1696-1699); `Helper.vec3FromListTag` (Helper.java:1465-1471). |
| **Verdict** | **CHANGED** — the element-type parameter is gone: `Optional<ListTag> getList(String)` (`net/minecraft/nbt/CompoundTag.java:359`), `getListOrEmpty(String)` (`:363`). Lists are heterogeneous (see G8). |
| **Migration note** | Old typed `getList` returned an **empty list** when the element type mismatched — that per-list guard must be re-expressed as per-element validation (G8 mechanism) wherever IP relied on it (Mesh2D.fromTag's null-on-malformed contract). Where IP only ever wrote the list itself (Helper.getCompoundList round-trips), `getListOrEmpty` is the drop-in. |

### C4. `CompoundTag.getAllKeys()`
| | |
|---|---|
| **IP usage** | `DimIntIdMap.fromTag` iterates the `"intids"` compound keys (dimension/DimIntIdMap.java:121); `MiscNetworking` handler iterates the dim-type map (network slice, MiscNetworking.java:107). |
| **Verdict** | **CHANGED** — renamed `keySet()` (`net/minecraft/nbt/CompoundTag.java:193`); also new `entrySet()` (`:197`), `forEach(BiConsumer)` (`:205`). |
| **Migration note** | Rename; `entrySet()`/`forEach` avoid the per-key second lookup if desired — but 1:1 is `keySet()`. |

### C5. `ListTag.getDouble(int)` / `getInt(int)`
| | |
|---|---|
| **IP usage** | `Mesh2D.fromTag` per-element decode (my_util/Mesh2D.java:1696-1728); `Helper.vec3FromListTag` (Helper.java:1465-1477). |
| **Verdict** | **CHANGED** — `Optional<Double> getDouble(int)` (`net/minecraft/nbt/ListTag.java:294`) / `getDoubleOr(int,double)` (`:298`); `Optional<Integer> getInt(int)` (`:278`) / `getIntOr(int,int)` (`:282`). |
| **Migration note** | Combine with G8: the Optional-empty case IS the malformed-list detection IP got from `getElementType`. |

### C6. `StringTag` — `getAsString()` and shape
| | |
|---|---|
| **IP usage** | `Helper.getWorldId`: `tag instanceof StringTag` then `((StringTag)tag).getAsString()` (Helper.java:517-521). |
| **Verdict** | **CHANGED** — `StringTag` is now a record: `public record StringTag(String value) implements PrimitiveTag` (`net/minecraft/nbt/StringTag.java:8`). `getAsString()` no longer exists; the accessor is `value()`, and `Tag.asString()` returns `Optional<String>` (`StringTag.java:92`). |
| **Migration note** | `tag instanceof StringTag st ? st.value() : ...` — or pattern-match destructuring as vanilla does (`CompoundTag.java:336`). Keep IP's error branch (log + OVERWORLD fallback) intact. |

### C7. `ResourceLocation` → `Identifier` (cross-cutting rename)
| | |
|---|---|
| **IP usage** | `Helper.dimIdToKey(ResourceLocation)` (Helper.java:504-510); `DimensionIntId` id constants (dimension/DimensionIntId.java:27); `WithDim` indirectly via `ResourceKey`; payload ids (network slice). |
| **Verdict** | **CHANGED** — class renamed to `net.minecraft.resources.Identifier` (`resources/Identifier.java:18`). |
| **26.2 equivalent** | `Identifier.fromNamespaceAndPath(ns, path)` (`Identifier.java:40`), `Identifier.parse("ns:path")` (`:44`), `Identifier.withDefaultNamespace(path)` (`:48`); `getPath()`/`getNamespace()` unchanged (`:100,:104`). |
| **Migration note** | Pure rename. Note `Helper.dimIdToKey(String)` routes through `McHelper.newResourceLocation` (an imm_ptl.core dependency, Helper.java:509) — that helper's body becomes `Identifier.parse`. |

### C8. `ResourceKey.location()`
| | |
|---|---|
| **IP usage** | `DimIntIdMap.toTag` serializes dimension keys as location strings (dimension/DimIntIdMap.java:66,74,116-146). |
| **Verdict** | **CHANGED** — renamed `identifier()` (`net/minecraft/resources/ResourceKey.java:64`). `ResourceKey.create(registry, Identifier)` itself survives (`ResourceKey.java:26`). |
| **Migration note** | Rename only; the serialized string form (`namespace:path`) is unchanged, so `DimIntIdMap`'s NBT layout is wire/save-compatible. |

### C9. `RegistryAccess.registryOrThrow(...)`
| | |
|---|---|
| **IP usage** | `MiscNetworking.DimIdSyncPacket` builds the dimId→dimTypeId map from `server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)` (registered by this slice's entrypoint; MiscNetworking.java:52-79). |
| **Verdict** | **CHANGED** — renamed `lookupOrThrow` (`net/minecraft/core/RegistryAccess.java:21`, default method on the interface). Vanilla usage: `this.registries.compositeAccess().lookupOrThrow(Registries.LEVEL_STEM)` (`net/minecraft/server/MinecraftServer.java:326`). |
| **Migration note** | Rename. `MinecraftServer.registryAccess()` itself survives (`MinecraftServer.java:2005`, returns `RegistryAccess.Frozen`); `Registries.DIMENSION_TYPE` survives (`net/minecraft/core/registries/Registries.java:274`). |

### C10. `Direction.getNormal()`
| | |
|---|---|
| **IP usage** | `AARotation` constant definitions + `dirCrossProduct` (my_util/AARotation.java:62-97); `Helper.getUnitFromAxis`-adjacent direction math (Helper.java:218-236). |
| **Verdict** | **CHANGED** — renamed `getUnitVec3i()` (`net/minecraft/core/Direction.java:375-377`); new siblings `getUnitVec3()` → `Vec3` (`:379`) and `getUnitVec3f()` → `Vector3fc` (`:383`). |
| **Migration note** | Mechanical rename; the returned `Vec3i` values are identical (enum constants, `Direction.java:33-38`). |

### C11. `MinecraftServer` constructor (mixin `@At("RETURN")` target of `MixinMinecraftServer_Misc`)
| | |
|---|---|
| **IP usage** | Ctor-RETURN inject sets `MiscGlobals.refMinecraftServer` (MixinMinecraftServer_Misc.java:48-56); descriptor named the old 8-arg ctor `(Thread, LevelStorageAccess, PackRepository, WorldStem, Proxy, DataFixer, Services, ChunkProgressListenerFactory)`. |
| **Verdict** | **CHANGED** — new signature: `MinecraftServer(Thread serverThread, LevelStorageSource.LevelStorageAccess storageSource, PackRepository packRepository, WorldStem worldStem, Optional<GameRules> gameRules, Proxy proxy, DataFixer fixerUpper, Services services, LevelLoadListener levelLoadListener, boolean propagatesCrashes, NotificationManager notificationManager)` (`net/minecraft/server/MinecraftServer.java:311-323`). `ChunkProgressListenerFactory` → `LevelLoadListener`; new `Optional<GameRules>`, `boolean`, `NotificationManager`. Superclass ctor is now `ReentrantBlockableEventLoop(String name, boolean propagatesCrashes)` (`net/minecraft/util/thread/ReentrantBlockableEventLoop.java:6`; super call `MinecraftServer.java:324`). |
| **Migration note** | The inject survives conceptually (`@Inject(method = "<init>", at = @At("RETURN"))` with no arg capture needs no descriptor change if the mixin targets all ctors — there is exactly one). Only code that *names* the ctor descriptor or extends the class breaks. Same finding as network.md §headline-6. |

### C12. `MinecraftServer.createLevels(ChunkProgressListener)` (inject target for `DimensionIntId.onServerStarted`)
| | |
|---|---|
| **IP usage** | `@Inject(method="createLevels", at=@At("RETURN"))` → `DimensionIntId.onServerStarted(server)` builds the dim⇄int map after all levels exist (MixinMinecraftServer_Misc.java:58-61; DimensionIntId.java:74-89). |
| **Verdict** | **CHANGED** — now **no-arg** `protected void createLevels()` (`net/minecraft/server/MinecraftServer.java:421`), invoked from `loadLevel()` (`:402`) after `setModdedInfo` and before `forceDifficulty()`/`prepareLevels()` (`:396-404`). |
| **Migration note** | Rewrite the descriptor to `createLevels()V`. Timing contract holds: at RETURN, `server.getAllLevels()` (`:1197`) is fully populated, before any player joins — exactly what `onServerStarted` needs. |

### C13. HUD member relocations — `gui.getFont()`, `Gui.setOverlayMessage`
| | |
|---|---|
| **IP usage** | `CustomTextOverlay` doc-comment contrasts with vanilla `Gui.setOverlayMessage` (CustomTextOverlay.java:17-19); `GuiHelper`/overlay code takes fonts from `Minecraft.getInstance().font` / `gui.getFont()` (my_util/GuiHelper.java:105-112, CustomTextOverlay.java:96). |
| **Verdict** | **CHANGED** — moved onto `Hud`: `Hud.getFont()` (`net/minecraft/client/gui/Hud.java:1276`), `Hud.setOverlayMessage(Component, boolean)` (`:1225`). Reach the Hud via public fields `Minecraft.gui` (`net/minecraft/client/Minecraft.java:290`) → `Gui.hud` (`net/minecraft/client/gui/Gui.java:72`). `Minecraft.font` is still a public field (`Minecraft.java:287`). |
| **Migration note** | `minecraft.gui.getFont()` → `minecraft.gui.hud.getFont()` (or keep `minecraft.font` — same Font object vanilla hands the Hud). |

### C14. `LevelStorageSource.LevelStorageAccess.levelDirectory` accessor mixin (`IELevelStorageAccess_Misc`)
| | |
|---|---|
| **IP usage** | `@Accessor("levelDirectory")` to read the save dir for `MiscHelper.getWorldSavingDirectory` (IELevelStorageAccess_Misc.java:7-11; MiscHelper.java:112-117). |
| **Verdict** | **CHANGED** — the private field survives (`private final LevelStorageSource.LevelDirectory levelDirectory`, `net/minecraft/world/level/storage/LevelStorageSource.java:458`) **but vanilla now exposes a public getter**: `getLevelDirectory()` (`LevelStorageSource.java:506-508`). `LevelDirectory` is a record with `path()` (`:422`). |
| **Migration note** | The accessor mixin is obsolete — `((IEMinecraftServer_Misc)server).ip_getStorageSource().getLevelDirectory().path()` uses only vanilla surface. The `IEMinecraftServer_Misc` duck for `storageSource` is still needed (field is `protected final`, `MinecraftServer.java:217` — not public), unless the port prefers the also-public `server.getLevelPath(LevelResource)` route (`:1990`). Keep the duck for 1:1 fidelity. |

### C15. `Minecraft.getWindow()` + `Gui`-side size queries (context shift, members intact)
| | |
|---|---|
| **IP usage** | `CustomTextOverlay.render` centers text at `(getGuiScaledWidth()/2, getGuiScaledHeight()*0.75)` (CustomTextOverlay.java:99-127). |
| **Verdict** | **CHANGED (context)** — `Minecraft.getWindow()` (`net/minecraft/client/Minecraft.java:2815`) and `Window.getGuiScaledWidth()/getGuiScaledHeight()` (`com/mojang/blaze3d/platform/Window.java:487,491`) all survive, but inside an extraction hook the idiomatic source is `GuiGraphicsExtractor.guiWidth()/guiHeight()` (`net/minecraft/client/gui/GuiGraphicsExtractor.java:128-133` — implemented as exactly those Window calls). |
| **Migration note** | Either compiles; use the extractor's accessors inside the Hud hook to match vanilla HUD code style. |

---

## SAME (44 vanilla — verified signatures)

### Math / value types
| Touchpoint | IP usage | 26.2 citation | Note |
|---|---|---|---|
| `Vec3` ctor `(double,double,double)`, `(Vec3i)`; fields `x,y,z`; accessors `x()/y()/z()` | Plane, DQuaternion, Sphere, Helper (everywhere) | `net/minecraft/world/phys/Vec3.java:65,75,41-43,305-315` | — |
| `Vec3.add/subtract/scale/dot/cross/normalize/lerp/distanceToSqr/lengthSqr/length/atLowerCornerOf` | same | `Vec3.java:112,96,152,88,92,83,228,131,184,180,45` | `atLowerCornerOf(Vec3i)` is the `IntMatrix3.toQuaternion` bridge (IntMatrix3.java:103-106) |
| `AABB` fields `minX..maxZ`; ctors 6-double + `(Vec3,Vec3)`; `contract`; `contains(x,y,z)`; `getXsize/getYsize/getZsize` | Helper AABB algebra (Helper.java:403-469, 997-1063, 1165-1168) | `net/minecraft/world/phys/AABB.java:15-20,22,35,129,263,274-282` | `contains` is still min-inclusive/max-exclusive (`:264`) — `Helper.boxContains`'s documented quirk is preserved |
| `BlockPos` ctor `(int,int,int)`; `offset(int,int,int)/offset(Vec3i)`; `subtract(Vec3i)`; `betweenClosedStream(BlockPos,BlockPos)`; extends `Vec3i` (getX/getY/getZ) | IntBox everything (IntBox.java:21-516) | `net/minecraft/core/BlockPos.java:58,121,125,129,369,32` | `betweenClosedStream` still yields **mutable** poses — IntBox.fastStream's "store its copy" warning stands |
| `Vec3i` | AARotation, IntMatrix3, Helper | `BlockPos.java:32` (extends), `Direction.java:33-38` (ctor use) | — |
| `Direction`: `getStepX/Y/Z`, `getAxis()`, `getAxisDirection()`, `getOpposite()`, `get(AxisDirection,Axis)`, `fromAxisAndDirection(Axis,AxisDirection)`, `values()` | Helper axis algebra (Helper.java:218-401), AARotation, IntMatrix3 | `net/minecraft/core/Direction.java:247-255,267,155,167,361-369,287` | Only `getNormal` (C10) and `fromDelta` (G2) changed |
| `Direction.Axis.choose(x,y,z)` (int/double/boolean) | `Helper.getCoordinate` (Helper.java:226-230) | `Direction.java:531-535` (abstract), impls `:397-459` | — |
| `OctahedralGroup.rotate(Direction)` | `IntMatrix3(OctahedralGroup)` ctor (IntMatrix3.java:31-38) | `com/mojang/math/OctahedralGroup.java:142` | Constants (`IDENTITY`, `ROT_90_Y_NEG`, …) in active vanilla use (`Rotation.java:18-21`) |
| `Rotation` enum constants `NONE/CLOCKWISE_90/CLOCKWISE_180/COUNTERCLOCKWISE_90` | `AARotation.toVanillaRotation/fromVanillaRotation` (AARotation.java:183-201) | `net/minecraft/world/level/block/Rotation.java:18-21` | — |
| `Mth.clamp(int/float/double)`, `Mth.lerp(float/double)` | Mesh2D grid clamp (Mesh2D.java:42-43), Sphere.interpolate (Sphere.java:82), Animated (Animated.java:113) | `net/minecraft/util/Mth.java:94-106,550,558` | — |
| `Unit.INSTANCE` | Mesh2D traversal sentinel (Mesh2D.java:1292) | `net/minecraft/util/Unit.java:9` | — |
| JOML: `Quaternionf`, `Quaterniond`, `Matrix4f`, `Matrix3f`, `Vector3f` (+ new `Matrix3x2fStack` in GUI) | DQuaternion boundary (DQuaternion.java:75-83,507-520), IntMatrix3.toMatrix (IntMatrix3.java:84-99) | external lib, still bundled — vanilla imports at `Direction.java:26-29` (`Matrix4fc`,`Quaternionf`,`Vector3f`,`Vector3fc`), `GuiGraphicsExtractor.java:136` (`Matrix3x2fStack`) | JOML API (`set(Quaternionf)`, `rotateX/Y/Z`, `getEulerAnglesZYX`) is library-stable; not an MC surface |

### NBT (the parts that did NOT change)
| Touchpoint | IP usage | 26.2 citation | Note |
|---|---|---|---|
| `CompoundTag.putDouble/putInt/putString/putLong` (+ putBoolean/putFloat/putIntArray…) | all toTag writers | `net/minecraft/nbt/CompoundTag.java:247,235,251,239,267,243` | Write side is untouched — save/wire layouts stay identical |
| `CompoundTag.put(String,Tag)`, `get(String)`, `contains(String)` | Helper.putVec3d etc. (Helper.java:626-733) | `CompoundTag.java:223,271,275` | Untyped `contains(String)` survives; the *typed* `contains(String,int)` idiom is subsumed by Optional getters (C1) |
| `Tag.TAG_DOUBLE/TAG_INT` (and `TAG_COMPOUND`) constants | Mesh2D.fromTag (Mesh2D.java:1697-1698) | `net/minecraft/nbt/Tag.java:18,15,22` | — |
| `DoubleTag.valueOf`, `IntTag.valueOf` | Helper.listToListTag (Helper.java:692-733) | `net/minecraft/nbt/DoubleTag.java:46`, `IntTag.java:44` | — |
| `ListTag`: no-arg ctor, `add(Tag)`, `size()`, `get(int)` | Mesh2D.toTag, Helper.vec3ToListTag | `net/minecraft/nbt/ListTag.java:145,340` (`add(int,Tag)` backing `AbstractList.add(E)`, class decl `:16`), `:327,332` | — |

### Registry / resources
| Touchpoint | IP usage | 26.2 citation | Note |
|---|---|---|---|
| `ResourceKey.create(Registries.DIMENSION, id)` | `Helper.dimIdToKey` (Helper.java:504-510) | `net/minecraft/resources/ResourceKey.java:26`; `Registries.DIMENSION` at `net/minecraft/core/registries/Registries.java:308` | Second param is now `Identifier` (C7 ripple) |
| `Registry.getKey(T)` | DimIdSync dim-type map (MiscNetworking.java:56-62) | `net/minecraft/core/Registry.java:59` (`@Nullable Identifier getKey(T)`) | Return type renamed with C7; shape identical |
| `Level.OVERWORLD/NETHER/END` | `DimensionIntId.fillInVanillaDimIds` pinned ids {0,−1,1} (DimensionIntId.java:91-101), `Helper.getWorldId` fallback (Helper.java:525) | `net/minecraft/world/level/Level.java:95-97` | The wire/compat contract's three keys are intact |
| `DimensionType`, `BuiltinDimensionTypes.OVERWORLD` | DimIdSync fallback (MiscNetworking.java:61-69) | `net/minecraft/world/level/dimension/DimensionType.java` (exists; used at `LevelStorageSource.java:519`); `BuiltinDimensionTypes.java:8` | — |
| `Registries.DIMENSION_TYPE` | MiscNetworking.java:113-116 | `Registries.java:274` | — |

### Server lifecycle / threading
| Touchpoint | IP usage | 26.2 citation | Note |
|---|---|---|---|
| `MinecraftServer.isSameThread()` / `execute(Runnable)` | `MiscHelper.executeOnServerThread` (MiscHelper.java:93-105) | inherited from `net/minecraft/util/thread/BlockableEventLoop.java:43,98`; `MinecraftServer extends ReentrantBlockableEventLoop<TickTask>` `net/minecraft/server/MinecraftServer.java:192` | `scheduleExecutables()` (the doc-referenced deferral mechanism) also survives, `BlockableEventLoop.java:49` |
| `MinecraftServer.getAllLevels()` / `levelKeys()` | DimensionIntId.java:79,106,112 | `MinecraftServer.java:1197,1193` | — |
| `MinecraftServer.getPlayerList().getPlayers()` | DimIdSync broadcast (DimensionIntId.java:125) | `MinecraftServer.java:1383`; `net/minecraft/server/players/PlayerList.java:817` | — |
| `MinecraftServer.registryAccess()` | MiscNetworking.java:56 | `MinecraftServer.java:2005` | pairs with C9 |
| `MinecraftServer.storageSource` field (@Shadow target) | duck `ip_getStorageSource` (MixinMinecraftServer_Misc.java:38-40,63-66) | `MinecraftServer.java:217` (`protected final LevelStorageSource.LevelStorageAccess storageSource`) | Was private, now protected — @Shadow unaffected; duck still needed for external access (see C14) |
| `Minecraft.getInstance()`, `isSameThread()`, `execute(Runnable)` | `MiscHelper.executeOnRenderThread` (MiscHelper.java:64-85) | `net/minecraft/client/Minecraft.java:2517`; extends `ReentrantBlockableEventLoop<Runnable>` `:261` → `BlockableEventLoop.java:43,98` | **Caution from network.md:** packets now drain via `Minecraft.packetProcessor()` *before* the event-loop tasks — `executeOnRenderThread` itself is fine, but don't use it to re-order against packet handling |
| `Minecraft.font`, `Minecraft.gui`, `Minecraft.options` public fields; `getWindow()` | GuiHelper, CustomTextOverlay | `Minecraft.java:287,290,291,2815` | `options` survives; only its `hideGui` member is gone (G5) |
| `Window.getGuiScaledWidth()/getGuiScaledHeight()` | CustomTextOverlay.java:99-127 | `com/mojang/blaze3d/platform/Window.java:487,491` | see C15 for the extractor-side equivalent |
| `ServerLevel.dimension()` / `dimensionType()` | DimensionIntId.java:80, MiscNetworking.java:59-61 | `net/minecraft/world/level/Level.java:960,952` (inherited by ServerLevel) | — |
| `ServerPlayer.connection` public field + `send(Packet<?>)` | DimIdSync sends (DimensionIntId.java:126, MixinPlayerList_Misc.java:29-31) | `net/minecraft/server/level/ServerPlayer.java:231`; `net/minecraft/server/network/ServerCommonPacketListenerImpl.java:156` | — |
| `PlayerList.placeNewPlayer(Connection, ServerPlayer, CommonListenerCookie)` + `new ClientboundChangeDifficultyPacket(Difficulty, boolean)` anchor | mid-login DimIdSync inject (MixinPlayerList_Misc.java:15-32) | `net/minecraft/server/players/PlayerList.java:142` (method), `:185` (anchor call, still inside placeNewPlayer); packet is `record (Difficulty difficulty, boolean locked)` `net/minecraft/network/protocol/game/ClientboundChangeDifficultyPacket.java:10` | Inject target + anchor both port 1:1 — the "sync before any dimension-int-tagged packet" ordering invariant is preservable at the same spot |
| `LevelStorageSource.LevelDirectory.path()` | MiscHelper.java:115 | `net/minecraft/world/level/storage/LevelStorageSource.java:422` (`record LevelDirectory(Path path)`) | — |
| `ClientPacketListener.levels` private field | dead accessor `IEClientPacketListener_Misc` (no call sites) | `net/minecraft/client/multiplayer/ClientPacketListener.java:403`; public **getter** `levels()` now exists `:2624` | Field intact if the accessor is ever revived (setter still needs the mixin; getter no longer does). Port priority zero per inventory §2.6 |

### GUI (the parts that did NOT change)
| Touchpoint | IP usage | 26.2 citation | Note |
|---|---|---|---|
| `MultiLineLabel.create(Font, Component, int)` | CustomTextOverlay.java:96 | `net/minecraft/client/gui/components/MultiLineLabel.java:44-46` | Render side is G7 |
| `Font` | GuiHelper, CustomTextOverlay | `net/minecraft/client/gui/Font.java` (class exists; `splitIgnoringLanguage` used by MultiLineLabel at `MultiLineLabel.java:78`) | — |
| `AbstractWidget.setX/setY/setWidth` | GuiHelper.layoutButton* (GuiHelper.java:13-30) | `net/minecraft/client/gui/components/AbstractWidget.java:262,272,181` | GuiHelper's layout math ports verbatim |
| `Component.empty()`, `Component.literal`, `MutableComponent.append` | CustomTextOverlay join (CustomTextOverlay.java:82-92) | `net/minecraft/network/chat/Component.java:166,135`; `MutableComponent.java:48,52` | — |
| `DeltaTracker` | render arg (CustomTextOverlay.java:66, MixinGui_Overlay.java:25) | `net/minecraft/client/DeltaTracker.java:8` (interface; `getGameTimeDeltaTicks()` `:12`) | Still the Hud extract param type (`Hud.java:221`) |

### Networking surface (registered by this slice's entrypoints; owned by the network slice)
| Touchpoint | IP usage | 26.2 citation | Note |
|---|---|---|---|
| `CustomPacketPayload` + `Type` | `DimIdSyncPacket` (MiscNetworking.java:37-44) | `net/minecraft/network/protocol/common/custom/CustomPacketPayload.java:13,56` | `Type` is `record Type<T>(Identifier id)` — C7 ripple only |
| `StreamCodec.of(encoder, decoder)` | MiscNetworking.java:41-44 | `net/minecraft/network/codec/StreamCodec.java:21` | — |
| `FriendlyByteBuf.writeNbt(Tag)` / `readNbt()` | DimIdSync NBT payload (MiscNetworking.java:52-97) | `net/minecraft/network/FriendlyByteBuf.java:517,534` | — |
| `Packet<ClientCommonPacketListener>` | MiscNetworking.java:81-84 | `net/minecraft/network/protocol/common/ClientCommonPacketListener.java` (exists) | Fabric packet-construction changes: see network.md headline-5 |

---

## FABRIC-API / external rows (multiloader routing required)

The mod is multiloader (common/fabric/neoforge) with a **`PlatformHelper`** ServiceLoader seam and per-loader entrypoint classes (see `migration/inventory/current-mod-core.md`: PlatformHelper interface §2.3:111-113; Fabric entry `SeamlessPortalsModFabric` :256; `FabricPlatformHelper` :258; NeoForge parity gaps :264-265, :382). None of these Fabric-API touchpoints may be used from `common`.

| # | Touchpoint | IP usage | Routing |
|---|---|---|---|
| F1 | `ModInitializer` / `ClientModInitializer` entrypoints | `MiscUtilModEntry` calls `ImplRemoteProcedureCall.init()`, `MiscNetworking.init()`, `DimensionIntId.init()`; `MiscUtilModEntryClient` the client twins (MiscUtilModEntry.java:8-15, MiscUtilModEntryClient.java:6-11) | Fold these init calls into the mod's existing per-loader entrypoints (`SeamlessPortalsModFabric` + NeoForge mod class); the init bodies live in common. |
| F2 | `EnvType` / `@Environment(CLIENT)` annotations | MiscHelper, DimensionIntId, CustomTextOverlay | Per-loader dist annotations (note: the 26.2 decompile itself carries NeoForge's `@OnlyIn(Dist.CLIENT)`, e.g. `MultiLineLabel.java:13-17`); common code uses the mod's own client/server split conventions. |
| F3 | `FabricLoader.getInstance().getEnvironmentType()` | `MiscHelper.isDedicatedServer()` (MiscHelper.java:107-109) | Route through a `PlatformHelper`-style dist query (each loader has one: FabricLoader / NeoForge `FMLEnvironment`-equivalent). |
| F4 | `EventFactory.createArrayBacked` + `Event<T>` | `Helper.createRunnableEvent/createConsumerEvent/createBiConsumerEvent` — the factory behind ALL of IP's own event objects (Helper.java:1424-1455) | **Highest-impact Fabric-API item in the slice**: these events are consumed all over imm_ptl. Needs a common-code event implementation or a loader-abstracted factory behind the PlatformHelper seam. The mod's own `Signal`/`SignalArged` (this slice, pure Java) are the in-house precedent. UNKNOWN-NEEDS-DESIGN at the multiloader level (which side of the seam the event objects live on), NOT at the MC-API level — no vanilla surface involved. |
| F5 | `Event.addPhaseOrdering` + `Event.DEFAULT_PHASE` (early phase `iportal:early_phase`) | `DimensionIntId.init` orders its DimLib subscription before default so dim-int ids update before global portal storage (DimensionIntId.java:31-44) | Only meaningful with F6; if DimLib is dropped, the *ordering invariant* ("dim-id map updates before dependents") must be preserved by whatever dynamic-dimension hook replaces it. |
| F6 | `qouteall.dimlib.api.DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT` (external DimLib mod) | dimension add/remove notifications → `onServerDimensionChanged` (DimensionIntId.java:33-43,103-128) | **External mod, not vanilla, not bundled.** Our mod has no DimLib and no dynamic dimensions (inventory §3.6): the port needs only the static `onServerStarted` path (C12) + login sync (SAME rows). Carry the dynamic-update handler as dead-until-needed code or document as out of scope — decision belongs to the porting plan, flagged here. |

Fabric **networking** registration (`PayloadTypeRegistry.playS2C`, `ClientPlayNetworking.registerGlobalReceiver`, `ServerPlayNetworking.createS2CPacket` — MiscNetworking.java:81-84,134-148) is owned by the network slice; see `migration/api-map/network.md` headline-5 for the verified Fabric-API v6 renames (`clientboundPlay()` etc., `createS2CPacket` gone).

---

## Slice-level port notes

- **Zero-MC classes** (`QuadTree`, `MyTaskList`, `Signal*`, `LimitedLogger`, `CountDownInt`, `RateStat`, `ObjectBuffer`, `KeyedTaskList`, `ChangeAccumulator`, `GeometryUtil` internals, `Animated` core, `Access`, `BoxPredicate*`, `Vec2d`, `LongBlockPos`, `Range`) port verbatim — no rows needed.
- **NBT-bearing value types** (`DQuaternion.toTag/fromTag`, `IntBox`, `Mesh2D` — savegame format, `DimIntIdMap`) are all C1-C5 casualties; their **written** layouts are unchanged (put* survived), so old saves/wire stay compatible — only reader code changes.
- **`CustomTextOverlay` + `MixinGui_Overlay`** are the hardest-hit unit: G3+G4+G5+G6+G7+C13+C15 all land there. The full 26.2 shape: mixin `Hud.extractRenderState` RETURN → guard `!hud.isHidden()` → `Profiler.get().push(...)` → expire entries → `MultiLineLabel.create(font, joined, guiWidth-20)` → `visitLines(TextAlignment.CENTER, guiWidth/2, (int)(guiHeight*0.75), 9, graphics.textRenderer())` → `pop()`.
- **Dead/vestigial** (inventory §"Dead"): `IEClientPacketListener_Misc` (field verified intact if revived), `MiscGlobals.serverTaskList`, `ObjectBuffer`, `KeyedTaskList`, `ChangeAccumulator`, `DimensionIdRecord` (Polymer shim), `Mesh2D.debugVisualize` — no 26.2 blockers found for any; port-priority zero stands.
