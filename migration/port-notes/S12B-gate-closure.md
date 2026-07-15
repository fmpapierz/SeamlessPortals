# S12-B — HARD GATE closure: category-(c) translation-slip fixes + the ratified gate-set amendment

**Stage S12-B of the entity-portal migration, gate-closure slice.** S12-B lands the client mixins
(second half of U10, `mixin-client.md` multiworld + render halves, all UNREGISTERED) and then runs
the **last pre-closure HARD GATE**: the probe (`:common:compileJava -Pip_scc_closed=true`) may
reference ONLY the S13 closure set. This fragment records the resolution of the three category-(c)
gaps the S12-B triage surfaced, per the binding parent rulings. ZERO IP-logic deviation; every fix
is a 26.2-API translation slip corrected against the api-maps, or a documentation/plan action.

**Citation conventions** as in S12A-renderers.md (`IP:` 1.21.3, `26.2:` mc262-ref, `MOD:` the live
proven `com.warwa` substrate).

---

## 1. Category-(c) translation slips FIXED (blocked a clean S12 HARD GATE)

### 1.1 `ClientWorldLoader.java:545,:610` — map-key type `String` → `MapId` (parent ruling 1)

**Slip:** the S10 `ClientWorldLoader.getWorld` locals for the shared map-data hand-off still used
IP 1.21.3's `Map<String, MapItemSavedData>`, but 26.2 `ClientLevel.mapData` is
`Map<MapId, MapItemSavedData>` (the map KEY changed `String` → `MapId`,
`26.2:ClientLevel.java:160`). The S12-B multiworld accessor `IEClientLevel_Accessor` is CORRECT —
it already returns/accepts `Map<MapId, MapItemSavedData>`
(`mixin/client/accessor/IEClientLevel_Accessor.java:22,:26`, matching the already-landed
`MixinClientLevel.getAllMapData()`/`addMapData(Map<MapId,…>)`). The S10 locals were the slip; they
produced two `incompatible types` errors:
- `:545` — assigning `ip_getMapData()` (returns `Map<MapId,…>`) into a `Map<String,…>` local.
- `:610` — passing that `String`-keyed local into `ip_setMapData(Map<MapId,…>)`.

**Fix:** retyped the local to `Map<MapId, MapItemSavedData>` and added the
`net.minecraft.world.level.saveddata.maps.MapId` import (before the existing `MapItemSavedData`
import). IP LOGIC UNCHANGED — this is the same "all worlds share the same map-data map" hand-off;
only the generic key type moves to the 26.2 type the accessor already speaks. (Flagged in the render
slice as S12B-render §7; neither the render nor the multiworld slice actually applied it — closed
here.) Probe: both `ClientWorldLoader.java` errors GONE (the file now produces zero probe errors;
the sole remaining `ClientWorldLoader` mention in the probe is a *caller* at another site reaching
`ClientWorldLoader.getWorld(BlockManipulationClient.remotePointedDim)` — an S13 `block_manipulation`
forward-ref, not this file).

### 1.2 `IEShader.java:3` — dead GONE-type import removed (parent ruling 2)

**Slip:** `ducks/IEShader.java` (S4 carve-in-adjacent duck, HELD by name in `IpHeldPaths` line 112)
carried `import com.mojang.blaze3d.shaders.Uniform;` — a GONE 26.2 type (render-core G5/G9: the
`RenderSystem.getShader` / `CompiledShaderProgram` / `Uniform` current-shader stack was removed for
the core-profile pipeline model). The import was **vestigial even in IP 1.21.3** (verified against
`IP:ducks/IEShader.java` — the interface body `int ip_getClippingEquationUniformLocation();` never
used `Uniform`; the S4 copy was faithful, carrying IP's own dead import). It surfaced only now
because this is the last pre-closure probe.

**Disposition — the S4-carried GONE-type-duck disposition (recorded per parent ruling 2):** per
`api-map/mixin-client.md §8` (the `MixinShaderInstance` row) the cached-uniform-location duck is
**TARGET-GONE with NO 26.2 equivalent and none is needed** — UBO slices bind by name per pass; the
functionality is owned by the **FrontClipping shader redesign**. `grep` confirms **no live Java code
references `IEShader`** — the only textual mentions are documentation comments in
`render/FrontClipping.java` (`:24`, `:313`) describing exactly this bridge decision. Dropping the
file would require a non-carve-in `IpHeldPaths` edit (removing line 112), which the stage constraints
restrict to genuine carve-in holds; the parent ruling explicitly authorizes the alternative ("else
delete the dead `Uniform` import and document"). **Chosen:** delete only the dead `Uniform` import,
keep the file held-inert, and document the GONE-type origin + FrontClipping ownership in a header
comment. The interface body is kept VERBATIM from IP. The file stays HELD until the FrontClipping
redesign revives or retires it. Probe: `IEShader` error GONE (zero refs).

> Sibling note: `IEShader` shares its GONE `com.mojang.blaze3d.shaders.*` origin with the three
> already-dropped render-shader ducks/mixins (`MixinCompiledShader`, `MixinShaderInstance`,
> `MixinRenderSystem_Clipping`) — same redesign owner. This is the last of the S4-carried
> GONE-`blaze3d.shaders` ducks to reach the probe.

---

## 2. Ratified gate-set AMENDMENT — `render/ShaderCodeTransformation` (parent ruling 3)

**Not a slip — an import-graph-justified miss OUTSIDE the plan's enumerated S13 closure set, ratified
by the parent and committed with this stage.**

**Import chain (documented per the ruling):** `IPModMainClient` — the U11 client-init closure hub,
committed S10.1 — imports `qouteall.imm_ptl.core.render.ShaderCodeTransformation`
(`IPModMainClient.java:25`) and calls `ShaderCodeTransformation.init()` (`:77`) exactly as IP does
(`IP:IPModMainClient` client-init sequence). The client-init hub's own import graph therefore pulls
`ShaderCodeTransformation` into the S13 closure. The plan's enumerated gate set (S12(b) /
S13(a)) did NOT list it ⇒ per the D4.2 NOTE the set is AMENDED to include
`qouteall.imm_ptl.core.render.ShaderCodeTransformation`. Plan amended in `EXECUTION_PLAN.md` (the
S12(b) HARD-GATE "S12-B GATE-SET AMENDMENT" note + the S13(a) `(c) Commits` closure-source line).

**S13-GREEN BLOCKER (recorded LOUDLY):** IP's `ShaderCodeTransformation` imports the GONE 26.2 type
`com.mojang.blaze3d.shaders.CompiledShader` (`api-map/mixin-client.md §8`, `MixinCompiledShader`
row: the whole `CompiledShader`/`CompiledShaderProgram` GLSL-compile stack is TARGET-GONE — the GLSL
transform re-sites to the `ShaderManager` source layer as part of the FrontClipping redesign). It is
therefore **NOT verbatim-portable**. **S13 MUST** do one of:
1. land `ShaderCodeTransformation` as a **SHELL** with the shader-transform internals commented /
   FrontClipping-deferred (mirroring the dropped `MixinCompiledShader` / `MixinShaderInstance` /
   `MixinRenderSystem_Clipping` precedent — same redesign owner as §1.2's `IEShader`), OR
2. gate / comment the `IPModMainClient.init()` call to `ShaderCodeTransformation.init()`.

Left unresolved, `IPModMainClient` stays RED at S13 and there is no first green build. This rides the
S13 entry ticket. **Porting `ShaderCodeTransformation` early is FORBIDDEN this stage** (S13 closure
class); it is documented, not authored.

---

## 3. NOT problems (documented so nothing is buried — no action, ratified)

- **`ImmPtlNetworkConfig.java:194,:218`** — `addTask`/`completeTask` `cannot find symbol`. These are
  **Fabric interface-injection residue** (S10A §4): the methods are injected into a Fabric API type
  by the real Fabric API at `:fabric`. The shipping `:fabric` build is green; the `:common` probe
  cannot see the injection. Environment-justified, pre-existing since S7/S10. NO action (parent
  ruling 5).
- **`mixin/.../MinecraftFramePumpMixin.java`** — the single `com.warwa` diff vs HEAD is comment-only,
  runtime byte-identical: the plan-authorized S3-anchor inert IP-dispatch scaffold
  (EXECUTION_PLAN S12 / EXCLUSIVITY_LEDGER §4 row 1). Parent-ratified as the single sanctioned
  exception to the literal "zero com.warwa" requirement (parent ruling 4). NOT a defect.

---

## 4. Verification (post-fix)

**AUTHORITATIVE builds** (`--no-daemon`, per `multiloader-common.gradle` documented invocation; logs
in the session scratchpad `s12b_shipping.log` / `s12b_probe.log`).

| Build | Result |
|---|---|
| Shipping `:common:compileJava` (no flag) | **BUILD SUCCESSFUL** (EXIT 0) — all held files excluded; live block-era render byte-untouched (zero `com.warwa` runtime edit; `buildSrc/build.gradle` + `.accesswidener`/AT untouched) |
| Probe `:common:compileJava -Pip_scc_closed=true` | **165 javac errors** (grep `: error:` = 166; the +1 = the documented Gradle problems-report phantom, S11B §7). All 165 are forward-ref / package-does-not-exist debt |

**Fixed-site confirmation:** `ClientWorldLoader.java` = 0 probe errors; `IEShader` = 0 refs;
`Uniform`/`MapId`/`MapItemSavedData` incompatible-types errors = GONE. `IPModInfoChecking` (the
S12-A/S12-B mission-named forward-ref) = fully resolved, 0 refs.

**HARD GATE re-check — HELD.** Every one of the 165 probe errors references ONLY:

| Bucket | Symbols / packages | Resolves at |
|---|---|---|
| U11 autoconfig mass | `me.shedaniel.autoconfig`(+`.annotation`/`.serializer`), `ConfigEntry.*`, `AutoConfig`, `Config`/`ConfigData`/`ConfigHolder`, `GsonConfigSerializer` (bulk of `IPConfig`=65, `IPModMain`=24, `IPGlobal`) | S13 (F21 autoconfig stub) |
| commands + argument types | `qouteall.imm_ptl.core.commands`, `PortalCommand`, `ClientDebugCommand`, `PortalDebugCommands`, `AxisArgumentType`/`SubCommandArgumentType`/`TimingFunctionArgumentType` | S13 closure |
| block_manipulation | `qouteall.imm_ptl.core.block_manipulation`, `BlockManipulationClient`, `BlockManipulationServer` | S13 closure |
| U12 generation slice | `custom_portal_gen`, `BreakablePortalEntity`, `NetherPortalEntity`, `GeneralBreakablePortal`, `FastBlockAccess`, `BlockTraverse`, `CustomPortalGenManager`, `PortalGenInfo` | S13 (U12 slice) |
| wand / debug / misc shells | `PortalWandInteraction` (wand), `qouteall.imm_ptl.core.debug`/`DebugUtil`, `GcMonitor`/`DubiousThings` (`miscellaneous/` — named in S13(a) contents) | S13 closure |
| **ratified amendment** | `ShaderCodeTransformation` (`IPModMainClient:25,:77`) | S13 (SHELL — §2) |
| documented residue | `addTask`/`completeTask` (`ImmPtlNetworkConfig`) | `:fabric` (Fabric injection — §3) |

No probe error references anything outside this set. The absolute count (161 S12-A → 165 here) rose
as S12-B landed the ~62 client/common mixins (each carrying its own S13-closure forward-refs, e.g.
`MixinMinecraft_B`, `MixinServerPlayerGameMode`, the `container_gui`/`portal_generation` mixins),
then fell by the 3 slips fixed here; the gate is the *per-error closure-set membership*, not the
integer, and it HOLDS.

---

## 5. Working-tree touch list (this slice)

| File | Δ | Reason |
|---|---|---|
| `common/.../ClientWorldLoader.java` | EDIT | §1.1 — `String`→`MapId` map-key retype + `MapId` import |
| `common/.../ducks/IEShader.java` | EDIT | §1.2 — dead `Uniform` GONE-type import removed + disposition header |
| `migration/EXECUTION_PLAN.md` | EDIT | §2 — ratified `ShaderCodeTransformation` gate-set amendment (S12(b) note + S13(a) commit line) |
| `migration/port-notes/S12B-gate-closure.md` | NEW | this fragment |

**No `com.warwa` runtime edit; no `.accesswidener`/AT edit; no `mixins.json` edit; no
`buildSrc/build.gradle` edit; no `IpHeldPaths` edit** (the `IEShader` disposition deliberately avoided
the non-carve-in held-list edit that dropping the file would have required). ZERO AW/AT pairs needed
(no private-access slip in the triage — the accessors were already correct; the fixes were caller-
side generic retype + a dead-import delete). No S13 closure class ported early.
