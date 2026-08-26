# Phase B port spec — finish the MC 26.2 secondary-renderer / LevelExtractor / submit-model rewrite

This is the authoritative, faithful plan for the **remaining** `:common:compileJava` errors
(the architectural core). The mechanical Phase A renames, the `PortalRenderTypes` pipeline
rewrite, and the immediate-draw helper are **already done and compiling**. Do NOT redo them.

**Reference (read for exact signatures):** `MIGRATION_API_MAP.md` (same dir) + decompiled
26.2 vanilla sources at `C:\Users\warwa\ModDev\mc262-ref`. Build with
`./gradlew :common:compileJava --offline --console=plain`.

## ABSOLUTE RULES (fidelity)
- This mod is a faithful hand-port of Immersive Portals. **Translate the existing logic 1:1 to
  the new 26.2 vanilla API. Do NOT simplify, stub, comment-out, `try/catch`-swallow, or `return`-early
  to make an error disappear.** Preserve every existing comment/behavior; only change what the API
  forced. If a faithful translation is genuinely unclear, leave the code, add a
  `// SEAMLESS-26.2-TODO: <why>` note, and report it in your final message — do not invent behavior.
- Keep going until `:common:compileJava` is GREEN (0 errors). Warnings (unused imports etc.) are fine.

## Design decisions already made (apply exactly)

### D1 — `LevelExtractor` is the per-dimension extraction owner
`PortalWorldManager` now has `private static final Map<ResourceKey<Level>, LevelExtractor> extractors`
populated in `createRenderer` (already done). Add a public accessor next to `getOrCreateRenderer`/`getLevel`:
```java
public static LevelExtractor getExtractor(ResourceKey<Level> dimension) {
    return extractors.get(dimension);
}
```
Everything that used to call `LevelRenderer.setLevel/onResourceManagerReload/setSectionDirtyWithNeighbors`
or `extractLevel` now calls it on the dimension's `LevelExtractor` (these MOVED to `LevelExtractor`
per the map). `extractLevel(dt,cam,pt)` → `extract(dt,cam,pt)`.

### D2 — Section dirty/rebuild API (RenderSection → SectionUpdateTracker)
`RenderSection.isDirty()/setNotDirty()/rebuildSectionAsync(cache)` are GONE. The dirty state lives on
the `LevelExtractor`'s `sectionUpdateTracker` (already widened via AT + AW). Add this shared helper to
`PortalWorldManager` (public static), and route ALL dirty-loop sites through it:
```java
/**
 * 26.2: schedule an async chunk-section compile iff the section is dirty in the
 * extractor's SectionUpdateTracker. Replaces the old
 * {@code if (section.isDirty()) { section.rebuildSectionAsync(cache); section.setNotDirty(); }}.
 * Returns true if a compile was scheduled.
 */
public static boolean scheduleCompileIfDirty(
        net.minecraft.client.renderer.extract.LevelExtractor extractor,
        net.minecraft.client.multiplayer.ClientLevel level,
        net.minecraft.client.renderer.chunk.RenderRegionCache cache,
        net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section) {
    if (extractor == null) return false;
    net.minecraft.client.SectionUpdateTracker sut = extractor.sectionUpdateTracker;
    if (sut == null) return false;
    net.minecraft.client.SectionUpdateTracker.SectionDirtyState ds =
        sut.getDirtyState(section.getSectionNode());
    if (ds == null || !ds.isDirty()) return false;
    section.compileAsync(cache.createRegion(level, section.getSectionNode()));
    ds.setNotDirty();
    return true;
}
```
(Field/method names verified: `LevelExtractor.sectionUpdateTracker` (widened), `SectionUpdateTracker.getDirtyState(long)`
→ `@Nullable SectionDirtyState`, `SectionDirtyState.isDirty()`/`setNotDirty()`, `RenderSection.compileAsync(RenderSectionRegion)`,
`RenderRegionCache.createRegion(ClientLevel, long)`.)

### D3 — `setSectionDirtyWithNeighbors` MOVED to `LevelExtractor`
Every `someRenderer.setSectionDirtyWithNeighbors(x,y,z)` becomes the dimension's extractor:
`PortalWorldManager.getExtractor(dim).setSectionDirtyWithNeighbors(x,y,z)` (null-guard the extractor).

### D4 — `setLevel(null)` MOVED to `LevelExtractor`
`renderer.setLevel(null)` → `extractor.setLevel(null)` (look up the extractor for that dim; null-guard).
In `removeRenderer`/`cleanup`, also `extractors.remove(dimension)` / `extractors.clear()`.

### D5 — `LevelRenderer.renderLevel(...)` → `render(...)` (drop ChunkSectionsToRender)
New signature (8 args, NO `ChunkSectionsToRender`):
`render(GraphicsResourceAllocator, DeltaTracker, boolean renderOutline, CameraRenderState, Matrix4fc modelView, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky)`.
`ChunkSectionsToRender` is no longer a `LevelRenderState` field; it is produced internally by
`render(...)` via `LevelRenderer.prepareChunkRenders(Matrix4fc)`. So:
- `ChunkSectionsToRender destChunks = destLRS.chunkSectionsToRender;` →
  `ChunkSectionsToRender destChunks = destRenderer.prepareChunkRenders(destViewMatrix);`
  (keep `destChunks` for the existing `maxIndicesRequired()==0` bail + diagnostics + Sodium re-point).
- The `destRenderer.renderLevel(alloc, dt, false, camState, viewMatrix, fogBuf, fogColor, true, destChunks)`
  call → `destRenderer.render(alloc, dt, false, camState, viewMatrix, fogBuf, fogColor, true)` (drop the trailing `destChunks` arg).
- `destRenderer.update(virtualCamera);` — there is NO `LevelRenderer.update(Camera)` in 26.2. The
  per-frame culling/extract is now `LevelExtractor.extract(...)` (already called at the `extractLevel`→`extract`
  site just above). REMOVE the `destRenderer.update(virtualCamera);` line. Add `// SEAMLESS-26.2-TODO:`
  noting the Sodium cullTerrain path previously driven by update() now relies on extract(); verify at runtime.
- **SEAMLESS-26.2-TODO (report this):** the mod pre-computes `destChunks` to re-point Sodium
  (`SodiumBridge.updateChunkSectionsRenderer(destChunks, ...)`), but `render(...)` recomputes its own
  `ChunkSectionsToRender` internally — so the Sodium re-point may not affect the actual draw. Keep the
  pre-compute+re-point as-is for now (compiles, vanilla path unaffected); this is a runtime item.

### D6 — Submit model: delete the FeatureRenderDispatcher buffer-source swap
`MultiBufferSource`/`OutlineBufferSource` and `RenderBuffers.bufferSource()/outlineBufferSource()/crumblingBufferSource()`
and the `FeatureRenderDispatcher.bufferSource/...` fields are ALL gone (submit model). In
`PortalContextSwitch.withSwitchedWorld`:
- DELETE the block that reads `destRendererAccess.seamlessportals$getFeatureRenderDispatcher()` + the three
  `savedDestDispatcher*` saves (the `MultiBufferSource.BufferSource`/`OutlineBufferSource` locals).
- In the swap `try`: DELETE the three `destDispatcherAccess.seamlessportals$setBufferSource(pooledBuffers.bufferSource())` etc.
- In `finally`: DELETE the three `destDispatcherAccess.seamlessportals$set*BufferSource(savedDestDispatcher*)` restores.
- Keep the rest of the swap (mainRenderTarget, level, levelRenderer, mainCamera, lightmap, hitResult, noPhysics,
  particleEngine.level, and the `mc.renderBuffers`/`destRenderer.renderBuffers` pooledBuffers swap — `RenderBuffers`
  still exists and those accessors are unchanged).
- Add a `// SEAMLESS-26.2-TODO:` noting the dispatcher's per-frame isolation is now via its own
  `StagedVertexBuffer` (from its isolated `RenderBuffers`), not swappable buffer-source refs — verify entity/item
  rendering in the portal view at runtime.
Then DELETE the file `mixin/client/FeatureRenderDispatcherAccessorMixin.java` and remove
`"client.FeatureRenderDispatcherAccessorMixin"` ... wait, it is in the `mixins`/`client` list? It is referenced
ONLY from `PortalContextSwitch`; it is NOT registered in `seamlessportals-common.mixins.json` (verify; if present, remove its entry).
Also remove the now-unused `destRendererAccess.seamlessportals$getFeatureRenderDispatcher()` import/usage if it
becomes unused (the accessor method itself can stay on `LevelRendererAccessorMixin`).

### D7 — `ClientLevel` ctor now takes `LevelExtractor` (HandleRespawnMixin)
`HandleRespawnMixin` constructs/!redirects a `ClientLevel` passing a `LevelRenderer levelRenderer` parameter where
26.2 wants `LevelExtractor`. The mixin's target method now receives a `LevelExtractor` at that position. Change the
captured parameter type `LevelRenderer levelRenderer` → `LevelExtractor levelExtractor` (and the `new ClientLevel(...)`
arg) to match the vanilla method's new signature. Verify against vanilla `Minecraft`/`ClientPacketListener` how the
`ClientLevel` is now built (the arg is `mc.levelExtractor` in vanilla). Update imports accordingly.

## Per-file checklist (apply D1–D7)
1. **PortalWorldManager.java** — add `getExtractor` (D1) + `scheduleCompileIfDirty` (D2). Replace the two dirty
   loops in `advanceOneRenderer` (the `if (!section.isDirty()) continue; section.rebuildSectionAsync(cache); section.setNotDirty(); scheduled++;`)
   with `if (!scheduleCompileIfDirty(extractors.get(dim), level, cache, section)) continue; scheduled++;`
   (`advanceOneRenderer`'s first param is the `dim`). Fix `destRenderer.setSectionDirtyWithNeighbors(...)` in the
   feed drain (D3 — use `extractors.get(feed.dim)`). Fix the two `renderer.setLevel(null)` (D4) in `removeRenderer`
   + `cleanup`, and clear `extractors` there too. (`createRenderer` itself is already done.)
2. **PortalContextSwitch.java** — D5 (extract/render/destChunks/update), D6 (dispatcher swap deletion). The inner
   force-dirty loop (`if (section.isDirty()) { ... section.rebuildSectionAsync(cache); section.setNotDirty(); ... }`)
   → use the extractor's tracker: fetch `LevelExtractor destExtractor = PortalWorldManager.getExtractor(destDim);`
   near where `destRenderer`/`destLevel` are obtained, then rewrite that loop's dirty check + schedule via the same
   `getDirtyState`/`compileAsync`/`setNotDirty` pattern (or call `PortalWorldManager.scheduleCompileIfDirty(...)`
   guarded by the existing radius/budget logic — preserve the `skippedFar`/budget bookkeeping). `extractLevel`→`extract`
   on `destExtractor`.
3. **RemoteBlockUpdater.java** — `renderer.setSectionDirtyWithNeighbors(sx,sy,sz)` (D3) and the
   `rs.rebuildSectionAsync(...)` + `rs.setNotDirty()` block (D2) → use the dimension's extractor. Read the file for
   how it gets `renderer`/dim; route through `PortalWorldManager.getExtractor(dim)` + `scheduleCompileIfDirty`.
4. **PortalDimensionManager.java** — `destRenderer.setSectionDirtyWithNeighbors(...)` (D3) → extractor.
5. **PortalFrameSuppressor.java** — `section.setDirty(false)` → set via the extractor's tracker:
   `SectionUpdateTracker.SectionDirtyState ds = ext.sectionUpdateTracker.getDirtyState(node); if (ds != null) ds.setDirty(false);`
   (read the method to find the dim/extractor; the surrounding loop iterates `viewArea.sections` which now compiles via AT widening).
   Note `SectionDirtyState.setDirty(boolean fromPlayer)`; passing the section's node requires `section.getSectionNode()`.
6. **HandleRespawnMixin.java** — D7.
7. **StencilPortalRenderer.java** — 1 remaining error; read it, identify, and apply the matching D-rule (likely a
   `setSectionDirtyWithNeighbors`/dirty/`renderLevel` ripple). Report what it was.
8. **FeatureRenderDispatcherAccessorMixin.java** — delete (D6).

## When green
Run `./gradlew :common:compileJava --offline --console=plain`, confirm 0 errors, and in your final message:
(a) confirm GREEN, (b) list every `SEAMLESS-26.2-TODO` you added with its file:line, (c) list anything you could
NOT translate faithfully and why. Do not touch `fabric/` or `neoforge/`.
