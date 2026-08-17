# SEAM ENTITY ENGINE — winning design (synthesis of the 2026-08-16/17 design panel)

Worktree root (all `W:` paths): `E:/Immersive Portals - Copy/.claude/worktrees/particle-seam-regression-c2f79c/`.
Ledger: `C:/Users/warwa/.claude/projects/E--Immersive-Portals---Copy/memory/seam_regression_sweep_2026_08_10.md`.
Baseline = the round-17/19 configuration (committed tip `dc6498d` + the uncommitted rounds 5–19 net, 8 files, ~341 insertions).

**Provenance.** Three designs were adversarially judged by three independent verdicts. Design 2 (minimal-delta: one predicate module + one post-pass band painter) is the only design that survived all three verdicts (scores 7.5 / 7 / 7.5) and is the SPINE of this document. Grafted onto it, per the judges' explicit endorsements:

- from Design 1: the typed `SeamCrossing` anchor (unit, anchorFace, epoch) with the epoch-guarded unit-atomic FLIP and the no-history reconciliation path — **layered OVER the kept per-member entries, never replacing them** (Verdict 2's decisive break-the-cart-mid-trail construction defeats every anchor-only design; per-member entries are the survival property); plus the at-most-2-crossings rule for adjacent seams.
- from Design 0: the irreducibility proof (§1.2 — recorded as constitution, it is why the anchor exists); shadow-mode divergence-logged migration; the scratch-stencil-bit discipline; the safe ALWAYS-depth escalation path for the band's in-aperture behind half.

Every fatal flaw a judge PROVED against the spine is resolved in-line below (marked **[RESOLVED: Vn-Fm]**) or demoted to §7's open questions (marked **[OPEN]**). Flaws proven against the rejected designs are encoded as prohibitions (marked **[PROHIBITED]**).

---

## §0 THE ONE RULE

> For an entity in a live seam crossing, the crossing's **anchor plane** partitions the body into exactly two pieces: the **front piece** (the side the anchor face is entered from, `+n`) belongs to the real body at real coordinates; the **back piece** (`−n`) belongs to the projection at the anchor transform's image. Every draw decision — draws-or-not, with which clip — is a pure function of exactly three inputs: **B**, the entity's interpolated box (its two signed spans against the anchor plane); **F**, the anchor: plane (origin `o`, normal `n`) + transform `T` + derived outer/inner clip planes; **V**, the render context's visible region (`passKeptNormal` = the ARMED inner-clip normal + window quad geometry + camera for a portal pass; camera + aperture rectangles for the main pass). **Forbidden inputs, enforced by module scope:** which portal *object* the renderer picked for the window (no `==`, no `isFlippedPortal`/`isReversePortal`, no id comparisons, no `getContentDirection`), and any binary eye/center-side test on the seam path. *Which face is the anchor* is the crossing's single stored history bit — irreducible (§1.2) — held as one typed `SeamCrossing` per crossing unit, layered over the per-member `portalCollisions` entries that remain the physics substrate and per-member fallback. The ±ADJUSTMENT plane band is owned by a dedicated **post-pass band painter** in every context it can reach; in the contexts it provably cannot reach (nested and unrelated portal windows), the in-pass projection extension remains the owner. Non-seam portals: byte-identical IP behavior everywhere.

Span primitives (alloc-free, kept verbatim): `SeamStraddleBracket.spanMin/spanMax` (`W:common/src/main/java/com/warwa/seamlessportals/passthrough/SeamStraddleBracket.java:49-59`). Sign conventions, fixed: a portal is entered from its front = the `+n` side; **front piece exists** ⇔ `spanMax > EPS`; **back piece exists** ⇔ `spanMin < −EPS` (EPS = 0.01, `:39`).

---

## §1 THE INVARIANT, FORMALLY

### 1.1 The seam invariant

For each open crossing `X = (unit U, anchorFace F with plane P(o, n) and transform T, epoch e)`:

1. **Partition.** P partitions each member's interpolated box into FRONT = `B ∩ {x : (x−o)·n ≥ 0}` (displayed at real coordinates in the member's level) and BACK = `B ∩ {x : (x−o)·n ≤ 0}` (displayed at `T(x)` in `F.getDestDim()`, clipped by `T·P` keeping the emerged side).
2. **Single-painter tiling.** In every render context each piece is drawn by exactly one designated painter iff the module's verdict admits it, clipped plane-exactly by P (or T·P) with the per-context epsilon of §2. No double-paint, no gap (IP's invariant, re-enforced without the framed-portal assumptions F-1..F-5).
3. **Band totality.** The union of all painters' kept regions ∪ the band owner's slab covers the ±ADJUSTMENT band for every camera position and every pass — and the band owner's term is context-enumerated in §2.4, so no epsilon change can create an unowned band.
4. **No forbidden inputs.** No verdict reads face-object identity, entry order, `contentDirection`, or a binary center/eye-side test. The pass is characterized only by its armed clip plane and its window geometry; the face only by its own plane and transform. (This makes the three orientation-luck defect families — ledger 6/15/17 — *unrepresentable*, not merely avoided.)
5. **One history bit.** The anchor F is the crossing's only stored history; epoch e makes the FLIP idempotent structurally. Everything else is derived per frame from B, F, V.
6. **Observability.** Every cull/skip verdict logs its reason under the probe lever (ledger-18 invariant): any future artifact self-attributes to a named verdict or transition.
7. **Non-seam isolation.** Every engine path begins with `isSeamContinuous(face)`; false ⇒ the untouched IP path runs (byte-identical, diff-provable).

### 1.2 The anchor irreducibility lemma (Design 0's proof, recorded as constitution)

The arrival trail (body wholly behind F_arr, about to emerge — must NOT draw naked at the arrival station) is **geometrically congruent** with an approach toward twin(F_arr) (body wholly in front of the twin — MUST draw naked; it is real local content). Same box, same plane, same spans; opposite draw semantics. The disambiguating fact is *chart membership* — which side of the stitched seam the body belongs to — and because a seam glues two regions of the SAME level (same-dim-far rig), chart membership is not a function of coordinates. `lastTickPos` cannot recover it either: the rebase deliberately maps lastTickPos through the transform for continuity (`W:common/src/main/java/qouteall/imm_ptl/core/teleportation/ClientTeleportationManager.java:991-992`), erasing the discontinuity signal. Hence **exactly one stored bit per crossing unit is irreducible**, and the current code already stores it — in three costumes: the seed ThreadLocal (`SeamStraddleBracket.java:103-115`), the behind-refusal gate (`MixinEntity.java:312-326`), and the pin's MAX_TRAIL reach (`SeamStraddleBracket.java:80-89`). The `SeamCrossing` anchor is that one fact in one costume. Corollary (proven by Verdict 1 against the spine's original form): leaving the bit implicit in the entry substrate makes it invertible by any clear-all + re-sweep whose iteration visits the twin first (`refuses()` only works if the true face's entry exists FIRST) — the typed anchor is immune to booking order. **[RESOLVED: V1-F1]**

### 1.3 The module: `SeamCrossingRule` (new file, `com.warwa.seamlessportals.passthrough`)

```java
public final class SeamCrossingRule {
    // resolution (lifecycle layer, §3)
    @Nullable public static Portal resolveCrossingFace(Entity e);   // anchor-authoritative; entry fallback + assert on divergence
    // context (built once per pass, identity-free; render-thread-confined stack)
    public static void pushPassCtx(...); public static void popPassCtx();
    // the four verdicts every call site consults
    public static boolean drawRealBody(Entity e, Portal face);
    @Nullable public static Plane realBodyClip(Entity e, Portal face);   // outer, −ADJ retreat
    public static boolean drawProjection(Entity e, Portal face);
    public static Plane   projectionClip(Entity e, Portal face);         // inner, epsilon per §2
    // lifecycle predicates (bookkeeping call sites, §3)
    public static boolean mayBook(Entity e, PortalCollisionHandler h, Portal face);
    public static boolean mustKeep(Entity e, PortalCollisionEntry entry);
}
```

Verdict derivations (each 1–3 sign tests):

- **`drawRealBody`** — main pass: `true`. Window pass: `n · passKeptNormal > 0` (the pass shows the face's front side); verdict FINAL — no `onDestSide`, no isHidden afterwards; the pass's plane-exact ambient clip (`W:common/src/main/java/qouteall/imm_ptl/core/render/SecondaryWorldRenderCore.java:1096-1101` + re-arms `:2169-2172`/`:2504-2507`) does all pixel cutting. `passKeptNormal == null` ⇒ the seam rule cannot engage ⇒ IP baseline + log (the `contentDirection` fallback at `W:common/src/main/java/qouteall/imm_ptl/core/render/CrossPortalEntityRenderer.java:674-676` is DELETED — sensitivity #1 closed).
- **`drawProjection`** — exists ⇔ **back piece exists** (`spanMin < −EPS` against the anchor plane): a face projects an entity only while the body actually pokes past (or trails behind) its own plane. Then per context: main pass additionally requires the aperture sight-line mask (current `isProjectionVisibleThroughAperture`, `CrossPortalEntityRenderer.java:267-300` — geometric, identity-free, survives VERBATIM inside the module; all three judges vetoed Design 0's deletion gamble against the documented F6 floating-image family **[PROHIBITED]**) and the dim check (`:334-337`); window pass additionally requires (a) locality — transformed box ∩ the pass's window geometry (current E9, `:490-503`, re-read as a geometry-of-V test) and (b) side agreement — `n_innerImage · passKeptNormal > 0`, where `n_innerImage` is the projection clip's kept normal at the image's station. Test (b) **is** the orientation-safe isHidden derived from the pass clip that round 17 specified (sensitivity #4 closed).
- **`realBodyClip`** — main pass: outer(F) retreated by ADJUSTMENT (`W:common/src/main/java/qouteall/imm_ptl/core/render/FrontClipping.java:322-349`, unconditional retreat KEPT — mandated by the round-18/19 evidence, tombstone `:340-346`). Window pass: `null` (the ambient CASE-3 clip cuts; unchanged).
- **`projectionClip`** — inner(F) with the epsilon per §2. Stages ≤2 preserve the round-17 arithmetic bit-for-bit (`FrontClipping.java:373-418`); stage 3 flips seam projections to RETREAT **scoped per §2.4** (main-pass projections + the outermost seam-co-planar window's in-pass projections only) once the band painter is live-verified.

**Submit-time constraint (latent trap, now explicit):** all verdicts are consulted at SUBMIT time, where the Ctx stack is live. Mechanism-B draw sites (`W:common/src/main/java/com/warwa/seamlessportals/passthrough/PerEntityClipBracket.java:382-433`) and the r43 phase overrides execute AFTER the `submitEntities` TAIL, where the Ctx is popped — any future draw-time consumer must capture its Ctx at submit. The module asserts render-thread + non-empty stack on every pass-context verdict. **[RESOLVED: V1-F5]**

---

## §2 THE PAINTER MATRIX

Contexts (same-dim-far rig; cross-dim is the same matrix with "far station" = the other ClientLevel, placement keyed by `clientDim == destDim` as today):

- **MP-near** — main pass, camera at the body's station.
- **MP-far** — main pass, camera at the far station (plain world geometry; both stations in one level, or the dest ClientLevel cross-dim).
- **WP-back** — portal pass with `n · passKeptNormal > 0` (window shows the anchor face's FRONT side — looking back at the crossing).
- **WP-thru** — portal pass with `n · passKeptNormal < 0` (window shows the BACK side — looking through at the emergence/trail image).
- **Nested / unrelated pass** — any portal pass at recursion depth ≥ 2, or a pass whose window plane is NOT co-planar with the anchor plane.

### 2.1 Piece × context verdict + exact clip

| Context | FRONT piece (real body) | BACK piece (projection at T coords) |
|---|---|---|
| **MP-near** | draws; clip = outer(F) retreated −ADJ (`submitMainPassEntity:192-242` → `PerEntityClipBracket.submitMainPassEntityClipped:192-209`) | absent from this context (displays at the far station) |
| **MP-far** | absent | draws iff backPiece ∧ dim ∧ aperture mask; clip = inner(F): stages ≤2 camera-side epsilon (extend on kept side / retreat otherwise, `FrontClipping.java:410-414`); stage 3 RETREAT always |
| **WP-back** | draws vanilla under the pass's ambient −ADJ EXTENDED clip (E11 + r42 re-arm — UNTOUCHED in all stages); verdict final; no per-entity clip | culled: side test `n_innerImage·k ≤ 0` and/or locality fails |
| **WP-thru** | culled (final; the pixels are the projection's job) | draws iff backPiece ∧ locality; clip = inner(F): stages ≤2 EXTEND (r5 rule, `FrontClipping.java:407-409`); stage 3 RETREAT iff this is the outermost seam-co-planar window, else keep EXTEND |
| **Nested / unrelated** | same sign rules against the innermost armed clip; when the anchor plane is not co-planar with the pass plane, the piece draws with its OWN plane and locality approximates the second plane (the one-hardware-plane honest gap; optional stage 5 closes it) | same; epsilon NEVER flips to retreat here (§2.4) |

### 2.2 Phase × context (what exists, hence what draws)

Pieces re-derive per frame against the CURRENT anchor; the FLIP re-anchors F_dep → F_arr atomically (§3), so "front/back" below always mean "vs the live anchor".

| Phase (anchor) | Piece existence | MP-near | MP-far | WP-thru | WP-back |
|---|---|---|---|---|---|
| **APPROACH** (F_dep) | front=whole, back=EMPTY | body, outer-clipped (clip cuts nothing yet) | nothing — no back piece ⇒ no projection can exist | body culled (side test); no image | body vanilla under ambient clip |
| **PRE-FLIP STRADDLE** (F_dep) | both nonempty | body, outer −ADJ | emergence image, inner clip (§2.1 epsilon) + aperture mask | image, inner clip per §2.1 | body under ambient; image culled |
| **FLIP frame** (F_dep→F_arr, atomic) | re-derived vs F_arr within the same RPC application | first rendered frame is already fully post-flip (§3.3 row 1) | — | — | — |
| **TRAIL** (F_arr) | front=EMPTY, back=whole | (now the far station) — nothing: no front piece | trail image mapped back through F_arr, inner clip | image per §2.1 | body culled — front piece empty; nothing vanilla-unclipped can draw |
| **EMERGENCE** (F_arr) | both nonempty | mirror of PRE-FLIP with F_arr in the F_dep role | | | |
| **EXIT** (release) | front=whole, back=EMPTY | anchor closes at full front emergence (`spanMin ≥ −EPS`); ordinary IP behavior resumes | | | |

### 2.3 Band ownership per context — grounded in the PROVEN pass ordering

Settled constraints (`W:common/src/main/java/qouteall/imm_ptl/core/render/renderer/RendererUsingStencil.java` + ledger rounds 12/18/19):

- (i) The aperture stencil write is depth-tested against main-pass depth (`renderPortalViewAreaToStencil:362-415`, GEQUAL + depth write), then depth inside the stencil region is CLEARED to FAR (`clearDepthOfThePortalViewArea:417-457`), so the window pass **unconditionally overdraws** main-pass pixels at-or-behind the quad inside the aperture — including the ±ADJUSTMENT co-planar band. ⇒ *No main-pass paint inside the aperture is ever load-bearing.* (Rounds 12/18 proved this live, twice.)
- (ii) In-pass painters are stencil-confined to the window rectangle; parallax can push owed band pixels outside it (the tail sliver, ledger 20). ⇒ *Only a post-pass, unstenciled painter can own band pixels in every reachable context.*
- (iii) After the passes, aperture depth is restored to the quad's EXACT projected depth (`restoreDepthOfPortalViewArea:459-495`), stencil values are clamped to 0 (`clampStencilValue:497-535`), and the stencil buffer stays intact until next frame's clear (`:249-250`) — so it can be legally re-populated post-pass.
- (iv) One hardware clip plane per draw (`W:common/src/main/java/com/warwa/seamlessportals/mixin/client/GlCommandEncoderClipMixin.java:72-158`).

| Context | Band owner, stages ≤2 (today's proven r17 config) | Band owner, stage 3+ (end state) |
|---|---|---|
| MP-near | projection extension at the far station (camera-side-scoped) — with the two inherent ~1cm costs | **band painter** (§2.5) |
| MP-far | same | **band painter** |
| WP-thru (outermost seam window) | in-pass projection extension (r5) → ledger 19/20 artifacts | **band painter** (in-aperture: near half by GEQUAL; behind half per the escalation, §2.5) |
| WP-back | pass-wide ambient −ADJ extension (r42 re-arm) — **untouched in all stages** | ambient extension underneath + band painter last-write on top |
| Nested / unrelated windows | in-pass projection extension | **in-pass projection extension KEPT** — the post-pass main-camera painter provably cannot paint at a nested/unrelated window's camera transform, so the retreat flip is scoped to exclude these contexts **[RESOLVED: V0-F4; closes V0's known-open (b)]** |
| Behind main-world depth-writing translucent terrain (underwater straddle) | extension (as today) | **[OPEN §7-Q3]** — post-pass GEQUAL band fragments behind water are depth-rejected (translucent terrain writes depth, `W:fabric/src/main/java/com/warwa/seamlessportals/fabric/SeamlessPortalsClientFabric.java:170-176`) |

The r5 totality invariant becomes: *the union of all painters' kept regions ∪ the band painter's slab (in reachable contexts) ∪ the retained in-pass extension (in unreachable contexts) covers the plane band for every camera and pass.* Every row above is enumerated; no epsilon edit outside this table is legal (ledger-21 invariant (a)).

### 2.4 Stage-3 retreat scoping rule (normative)

`seamBand=true` (projection retreats) applies ONLY to: (a) main-pass projections, and (b) in-pass projections whose display plane is co-planar with the OUTERMOST pass's window plane (co-planarity from PLANES: `|n₁·n₂| > 0.999 ∧ plane distance < ε` — never object identity). All other in-pass projections keep the r5 EXTEND. Call site: the single `seamBand` argument at `CrossPortalEntityRenderer.java:574` gains this scope test. Rollback = one flag.

### 2.5 The band painter (`SeamBandPainter`, new file) — the one new painter

**Hook:** a **second `AFTER_TRANSLUCENT_TERRAIN` registration placed immediately after the portal driver's** (`SeamlessPortalsClientFabric.java:156-168`; Fabric array-backed events invoke in registration order). Runs EVERY frame regardless of whether any portal pass executed — this is why the hook is here and **[PROHIBITED]** never at the `doRenderPortal` epilogue: Verdict 1 proved the epilogue is skipped by the stale occlusion-query early-return (`RendererUsingStencil.java:305-319`) and the fuse-view skip (`:283-285`), which under all-retreat re-creates ledger entry 8; and per-recursion-depth epilogues let an unstenciled draw escape the outer window's confinement (Verdicts 0/1/2, independently).

**Enumeration:** open crossings with a literal straddle (front ∧ back pieces both nonempty — the band exists only then). Per member, up to two piece-draws:

1. the real body at entity coordinates, **gated `entity.level() == mc.level`** **[RESOLVED: V2-F1** — without the world gate, a source-dim straddler in a kept-loaded ClientLevel paints wrong-dim fragments into a cross-dim dest view**]**;
2. the projected body at `T(entity)` coordinates via the camera-substitution trick (`renderEntity:546-575` reuse), gated by the same dim check + aperture mask as E3 — never wider than what E3 drew this frame.

**Slab clipping with one hardware plane** (exact slab — **[PROHIBITED]** Design 1's half-space whole-piece redraw, which double-blends translucent/glint layers over the entire body): two draws per piece, stencil-intersected:

- **Pass A (mark):** color mask off, clip keep `{d ≤ +ADJ}`, stencil op REPLACE→mark ref, depth GEQUAL (marks only where visible). Mark ref uses a HIGH scratch bit masked against the low-bit portal-layer values (Design 0's discipline; the buffer is all-zeros post-clamp, but the mask keeps the invariant explicit).
- **Pass B (color):** clip keep `{d ≥ −ADJ}`, stencil func EQUAL mark with op REPLACE→0, depth GEQUAL, **depth write OFF** (afterTerrain outlines / clouds / weather / hand unaffected).
- **Per-member scissored stencil clear** after each member's Pass B, scoped to the member's screen-bounds rect: Pass B self-cleans only where it generates fragments, so member 1's deep-back-piece marks would otherwise survive for member 2's color pass to hit (cart+cow overlapping on screen). **[RESOLVED: V0-F2]**

**Depth semantics (claim corrected — [RESOLVED: V0-F1]):** outside the aperture, stored depth is real scene depth — band fragments paint iff actually visible; this is exactly the pixel set no stenciled painter could ever own (closes ledger 20 by construction: the painter is unstenciled, so its permitted region always contains its responsibility region). INSIDE the aperture, stored depth is the quad's EXACT projected depth: fragments at `d ≥ 0` pass reversed-Z GEQUAL (passes on equal); fragments strictly behind the plane (`−ADJ ≤ d < 0`) marginally FAIL — the near half closes the visible gap, and the previous overclaim ("deterministically wins the last write") is retracted. Stage 3 is therefore gated on the R3 signature probe (a band-drawn-inside-aperture log line proven in one leg). **Escalation path if a grazing-angle behind-half residue survives live testing** (Design 0's insight in its SAFE form): re-draw the portal quad to re-populate the stencil (legal per constraint (iii)), then run the band passes **stencil-gated with ALWAYS depth** — safe precisely because the depth-tested stencil write proved the aperture contains no nearer main-world occluder. **[PROHIBITED]** an UNSTENCILED ALWAYS-depth band draw in any form — it paints through main-world occluders (wall between camera and seam). **[RESOLVED: V0-F3, V1-D1-flaw-2]**

**Blended-feature policy:** a marked pixel can be colored by a beyond-band fragment of a self-overlapping body edge-on — benign identical repaint for opaque, double-blend for glint/overlay phases. Default: the band redraw executes opaque/cutout feature phases only, excluding glint-class blended phases (residual: a ≤1cm glint gap in the band strip instead of a ≤1cm glint double-blend). The choice is disclosed as **[OPEN §7-Q2]**. **[RESOLVED: V2-F2 (disclosed + defaulted)]**

**Pipelines:** entity-family only (canonical `entity.vsh` clip path, verdict `wf_516e0df3-e27`); no LINES content (NDC-shader clip garbage). Draw machinery: the proven isolated dispatcher trio (`PerEntityClipBracket.getOrCreateOwnDispatcher:439-465` + endFrame lifecycle) — reuse, don't duplicate. Iris shaders-ON: painter disabled with the rest (`CrossPortalEntityRenderer.java:154-159`) — consistent known-open. Lever: `-PdisableSeamBandPainter`.

---

## §3 LIFECYCLE

### 3.1 The crossing unit and the anchor layer

A **crossing unit** = a vehicle root + all (recursive) passengers. State, layered:

- **Layer 1 — `SeamCrossing` (unit, anchorFace, epoch)** (Design 1's graft): stored on the unit root via the existing `IEEntity`/duck (`MixinEntity` implements it; heed the @Unique-init class-init-deadlock rule). THE history bit + idempotence token. At most **2** concurrent crossings per unit (adjacent seams), each resolving independently (replaces the spine's R4 assert+fallback). **[RESOLVED: V2-F3(multi-plane)]**
- **Layer 2 — per-member `portalCollisions` entries** (kept VERBATIM: fan `MixinEntity.java:349-351`, pin-keep + rider mirror `W:common/src/main/java/qouteall/imm_ptl/core/collision/PortalCollisionHandler.java:46-76`, booked-face refusals behind the module API): the physics substrate (`handleCollision:105-118` consumes them on both sides) AND the per-member fallback that survives unit destruction. `resolveCrossingFace` prefers the anchor; on anchor/entry divergence it logs + asserts and follows the anchor.

Phase is DERIVED per frame (§2.2), never stored.

### 3.2 Transitions

- **Book** (`mayBook`, replacing the inline gates at `MixinEntity.java:312-334`): a face may book an entity iff (a) it IS the unit's anchorFace (anchor-authorized booking — this replaces the `beginSeed/endSeed` ThreadLocal costume outright: the arrival face is bookable while wholly behind BECAUSE the anchor says so, not because a thread-local bracket is open), OR (b) `¬whollyBehind(e, face)` ("entered from the front") AND no co-located opposite face holds a live booking (the geometric twin check of `SeamStraddleBracket.refuses:132-152`, kept). First successful booking with no open crossing ⇒ **OPEN**: `anchor := face, epoch := 0`.
- **Keep** (`mustKeep`, replacing `PortalCollisionHandler.update:46-76` inline logic): entry persists iff `pinned(e, face)` (`SeamStraddleBracket.java:80-89`: lateral 0.5, MAX_TRAIL=3.0, releases at full front emergence) OR the entity's vehicle holds an entry for the same face (rider mirror — order-independent: reads the vehicle's CURRENT entries) OR the face is the unit's live anchorFace. Consulted before the box/staleness/eye gates, as today.
- **FLIP** — the one event-carried transition. Server: conserved arrival + rider carry + seam-tagged RPC + immediate refresh (C1–C6, `W:common/src/main/java/qouteall/imm_ptl/core/teleportation/ServerTeleportationManager.java:663-765` — untouched). Client, within ONE RPC application (`ClientTeleportationManager.java:882-958`): epoch-guarded — if the unit's crossing is already at the post-flip anchor, the entire transition no-ops structurally (duplicate/late/rider-echo RPCs are dead on arrival); else: apply the rebase transform to root + ALL rider visuals (existing `applyRebaseVisual` math `:974-1002` kept verbatim, including the nearest-to-server guard for the no-history path), set codec bases, `anchor := findArrivalFacingPortal(crossingPortal)`, `epoch++`, clear + re-book the anchor entry per member (anchor-authorized). No frame can interleave a partial unit.
- **No-history FLIP** (cross-dim spawn-at-dest, late join, unresolvable portalId): open directly post-flip (anchor = arrival face) and reconcile each member's visual by nearest-to-server-truth — the round-7 keystone made structural; this is the DESIGNED cross-dim fix, still owing its live round (§7-Q5).
- **CLOSE**: full front emergence (`spanMin ≥ −EPS` release), lateral exit from the pin column, anchor face removed/level mismatch, or staleness (~40 ticks with no qualifying geometry). Anchor and entries die together; state lives ON the entity (cache-on-subject rule).
- **Dismount / vehicle destruction mid-crossing**: the orphaned member **INHERITS the unit's anchor** as its own single-member crossing (its per-member entry, kept by `mustKeep`, carries it through the same frame). **Never** a larger-span re-derivation — Verdict 2 proved the tie-break anchors a majority-crossed orphan to the WRONG face. The larger-span tie-break exists ONLY for the genuinely history-less case (§7-Q4). **[RESOLVED: V1-graft-5, V2's decisive construction]**

### 3.3 Race table

| Interleaving | Why the engine is immune |
|---|---|
| Frame renders between vehicle RPC and rider RPC (26.2 renders mid-packet) | Unit-atomic FLIP: all member visuals + the anchor change in one handler call; riders resolve through the unit anchor — no partial unit state is observable. |
| `positionRider` drag wins the packet race (rider already at dest when its RPC lands) | Rider's RPC hits a post-flip crossing ⇒ epoch no-op; position apply reconciles via nearest-to-server. |
| Rider's `tickPassenger` prune runs after the vehicle's move, same tick | `mustKeep`'s vehicle mirror + anchor-face keep read CURRENT state — no ordering dependence; sensitivity #6 reduced to one audited call. |
| Both twins evaluated in one registration sweep | Approach: only the front-entered face passes (b); straddle: the anchor face is booked, twin refused; post-flip: anchor-authorized booking selects exactly F_arr. |
| **Clear-all + geometric re-sweep mid-straddle (any caller)** | The anchor survives the entry wipe; re-booking is anchor-authorized ⇒ the correct face re-books first regardless of sweep iteration order. (The spine's original entry-implicit form lost this race — §1.2 corollary.) |
| Frame rendered mid-tick during straddle | All verdicts are pure functions of the interpolated box + the anchor — no tick-cadence discovery step exists to race (ledger-1 invariant). |
| Server flip → RPC latency window | Client anchor stays pre-flip until the RPC; the visual is also pre-flip; each side is self-consistent at every instant. |
| Portal unload / relog mid-crossing | Anchor + entries die with the entity/level; CLOSE fires next tick; vanilla rendering resumes for that unit. |
| Cart broken / cow dismounts mid-TRAIL | Anchor inheritance + kept per-member entry (§3.2) — the wholly-behind orphan stays bracketed through trail and emergence. |
| Cross-dim add-packet → RPC gap frame | **Shared residual, disclosed, NOT claimed closed** (all three verdicts): entry 1's "by construction" is proven same-dim only until the cross-dim live round runs (§7-Q5). |

---

## §4 LEDGER DERIVATION TABLE — why each of the 21 artifacts is impossible

1. **Split-second wrong-side whole cart (F5)** — verdicts are pure functions of the interpolated box + the anchor; FLIP applies rebase + anchor + entries atomically before any frame. No tick-cadence discovery to miss. (Cross-dim gap-frame caveat disclosed, §3.3 last row.)
2. **Crossing hitch / ×2 lurch (F6)** — conserved arrival, rebase, kludge exemption (C1/D1/C6) untouched; no rewind/cancel path exists on the seam route.
3. **Direction-asymmetric disarm** — cell resolution is definitionally one step along the face's own normal (`SeamCartContinuity.java:206-227`, kept); every module verdict logs its `isSeamContinuous` outcome, so a future silent disarm self-attributes.
4. **Trail pop** — trail membership is HELD state (the anchor, opened at booking, released only by §3.2 CLOSE) — a lifecycle phase with explicit begin/end events, not a per-frame geometric predicate; the box/staleness gates cannot outvote the anchor-face keep. (The anchor graft upgrades the spine's pin-window answer to the ledger's own invariant wording — resolving Verdict 1's "artifact-terms only" critique.)
5. **In-pass DISABLED-clip ghost** — `projectionClip` returns a real plane for every seam projection unconditionally; no null-clip branch is reachable for seam faces.
6. **2-frame vanilla-unclipped flash** — `drawRealBody` in a pass is the single final verdict `n·k > 0`; in TRAIL the front piece is EMPTY — nothing exists to draw; isReversePortal/onDestSide are unreachable on the seam path.
7. **Naked 1cm sliver** — stages ≤2: camera-side epsilon retained (r17 proven config); stage 3: no seam projection extends in painter-reachable contexts — the band painter draws real slab matter, occlusion-tested, never a naked epsilon cross-section.
8. **Transparent slit (both retreat)** — band totality (§1.1-3) is enumerated per context in §2.3 with no unowned row; the band painter's hook runs every frame (never skipped by occlusion-query or fuse-view early-returns — the reason the epilogue placement was rejected); nested/unreachable contexts keep the extension.
9. **Double-transform fling (686.7 offset)** — FLIP is epoch-guarded: applying twice is structurally a no-op; the nearest-to-server guard survives only as the no-history reconciliation rule, where it is the correct server-authority principle.
10. **Mid-packet cow fragment** — unit-atomic FLIP + riders resolving through the unit anchor: no observable partial-unit frame (closed twice over).
11. **Cowless ghost (riders never registered)** — the fan + mirror keep per-member entries (kept verbatim), AND riders derive draw state from the unit anchor — registration can no longer be a movement-path side effect a passenger never executes.
12. **Rider entry deleted by own prune** — `mustKeep`'s vehicle mirror + anchor-face keep; order-independent by construction.
13. **Twin approach ghost (~2s)** — `drawProjection` requires backPiece against the ANCHOR plane; during approach the anchor's back piece is empty ⇒ no projection can exist; the twin cannot book (mayBook (b)) and cannot be the anchor (OPEN requires front entry).
14. **Last-3-blocks residual ghost** — the two roles that shared `pinned()` are separated: draw-existence = backPiece span (no behind-reach); lifetime = anchor CLOSE conditions. The twin's approach window overlaps only the lifetime predicate, which grants no draw.
15. **Away-crossing window hole** — the side test derives from the ARMED pass clip, which always exists during a pass; `contentDirection` appears nowhere (fallback deleted).
16. **Away front cut** — no binary center/eye test exists for any piece; booleans only select pass membership; the plane-exact clips decide pixels (ledger-16 invariant verbatim).
17. **Toward back-half cut** — no `isFlippedPortal`/object-identity comparison exists in the module; co-planarity is computed from planes; which face object drew the window cannot enter any decision.
18. **Far-station leak** — side agreement (`n_innerImage·k > 0`) + locality are the visibility geometry itself, never bypassable per-symptom; every cull logs its reason (observability invariant).
19. **Toward micro-bleed (~1cm)** — stage 3: in-window projections retreat in painter-reachable contexts; the band painter, running DOWNSTREAM of the proven overdraw, owns the band. Honest qualification: in-aperture closure is probe-gated (near-half GEQUAL + specified safe escalation), not asserted unconditionally; the underwater-translucent niche is §7-Q3. (Stages ≤2 explicitly retain this artifact — documented, not hidden.)
20. **Tail-end sliver** — the band painter is UNSTENCILED by construction: no view angle can push owed pixels outside where it may paint (the entry's invariant verbatim). Closed at stage 2 outside the aperture, stage 3 inside.
21. **r12/r18 regression sets** — the painter enumeration is closed and written down (§2.3: main body, main projection, in-pass body ambient, in-pass projection, band painter — five rows, every context); ownership changes are one scoped flag + one painter; the pass-overdraw fact is encoded as the band painter's POSITION (post-pass), so a pre-pass painter owning aperture band pixels is unrepresentable.

---

## §5 DELETION LIST

**Dies outright (replaced by derived verdicts / the anchor):**

| Gate | Site | Replaced by |
|---|---|---|
| CASE-2 pin gate (E4) | `CrossPortalEntityRenderer.java:432-449` | backPiece existence in `drawProjection` |
| `seamSamePlane` flipped-skip bypass (E8) + in-pass flipped/reverse skip *for seams* | `:365-386` | in-pass admission = locality ∧ side-vs-pass-clip |
| in-pass `isHidden` *for seams* (r17 standing) | `:392-403` | side test `n_innerImage·k > 0` (round 17's own flagged proper fix) |
| straddle-side gate + KEEP bypass structure (E10 seam branch) | `:661-694` | one call: `drawRealBody` (same math, final verdict) |
| `contentDirection` fallback | `:674-676` | deleted; null passClip ⇒ IP baseline + log |
| last-wins colliding-portal loop *for seam entities* | `:222-228` | `resolveCrossingFace` (anchor-authoritative); retires sensitivity #5 at seams |
| `straddles()` (unreferenced) | `SeamStraddleBracket.java:63-71` | deleted |
| `hasIntersection` (dead in the port AND in IP's own 1.21.3 reference) | `:344-350` | deleted; tombstone points at backPiece |
| seed ThreadLocal `beginSeed/endSeed` (A4) | `SeamStraddleBracket.java:103-115` + call site `ClientTeleportationManager.java:1011-1018` | anchor-authorized booking (`mayBook` clause (a)) — dies at stage 2a, one costume gone |
| behind-refusal + twin-refusal as *inline mixin logic* (B3/B4) | `MixinEntity.java:312-334` | `mayBook` (math kept inside the module; behind-refusal becomes the OPEN precondition) |
| pin-keep + rider-mirror as *inline prune logic* (B1/B2) | `PortalCollisionHandler.java:46-76` | `mustKeep` (math kept inside the module) |
| dormant `seamBand` plumbing as a dead flag | `CrossPortalEntityRenderer.java:574` → `FrontClipping.java:373-418` | repurposed at stage 3 (scoped projection retreat, §2.4) |

**Survives as a derived consequence** (moves inside the module/anchor, call sites become one-liners): pin window (= CLOSE conditions), rider fan (B5, still the entry writer for the substrate), aperture mask E5 (verbatim — deletion PROHIBITED by all three verdicts), locality gate E9, epsilon arithmetic E6 (stage-staged per §2.3/2.4), seam clip threading E7 (subsumed: seam projections always carry a real plane), D1 rebase math, D2 nearest-to-server (scoped to no-history), D3 atomic carry (structural), D4 arrival-face choice (the FLIP's re-anchor target).

**Untouched:** C1–C6 (server), D5 non-seam fallback, E1/E3 painter mechanics, **E11 pass ambient clip + r42 re-arm** (a judge-confirmed load-bearing painter — retreating it was Design 1's fatal error **[PROHIBITED]**), r43 outline exemption, E12 PerEntityClipBracket delivery, E13 stencil, Iris disable, all non-seam IP behavior.

---

## §6 STAGED IMPLEMENTATION PLAN

**Stage −1 — land the baseline.** Commit the uncommitted rounds 5–19 net (suite once pre-commit, per the standing loop). BLOCKED on the user's explicit go-ahead (ledger:350-352). The engine diffs against that state. (§7-Q1.)

**Stage 0 — bit-identical refactor (~0.5 day).** Create `SeamCrossingRule`; move the existing predicate BODIES verbatim behind the API; convert the call-site clusters — `CrossPortalEntityRenderer.java:222-241` (CASE-1 loop), `:360-466` (renderProjectedEntity both branches), `:661-694` (shouldRenderEntityNow seam branch), `MixinEntity.java:312-334` (register gates), `PortalCollisionHandler.java:46-76` (prune keeps), the `FrontClipping.java:322-418` capture pair — to single module calls. Suite green ⇒ bit-identical proof.

**Stage 1 — verdict unification under SHADOW MODE (~1 day + laps).** Design 0's graft: `-PseamResolver=shadow` computes the five new verdicts alongside the legacy gates, logging divergences (volume-capped per the scoped-suppression lesson); a zero-divergence live lap gates each flip. The five deltas, each independently revertible: (a) `contentDirection` fallback deleted; (b) in-pass seam isHidden → side-vs-pass-clip; (c) seam flipped-skip deleted; (d) last-wins → `resolveCrossingFace`; (e) projection existence = backPiece (pixel-equivalent to the pin gate for every legitimate phase). User-first loop: build → user lap → suite once pre-commit.

**Stage 2a — the anchor layer (~1 day).** `SeamCrossing` on the unit root via the duck; FLIP wired into `ClientTeleportationManager.java:882-958` (epoch guard + re-anchor beside `applyRebaseVisual`) and observed from `ServerTeleportationManager.java:663-675`; anchor-authorized booking replaces `beginSeed/endSeed`; anchor inheritance on dismount; at-most-2 rule; `resolveCrossingFace` prefers the anchor with assert-on-divergence (shadow-logged for one lap before the entry-fallback demotes to assert-only). Entries remain the physics substrate — collision byte-equivalent.

**Stage 2b — the band painter, additive (~1–2 days).** New `SeamBandPainter` per §2.5: second event registration after `SeamlessPortalsClientFabric.java:156-168`; two world/dim-gated pieces; two-pass stencil-AND slab with per-member scissored clears; depth GEQUAL, depth-write OFF; opaque-phase-only default; lever `-PdisableSeamBandPainter`. Epsilons untouched — the painter is a last-write coat; zero regression surface when levered off. Run the R3 signature probe (band-drawn-inside-aperture line) and a shallow-angle both-directions lap.

**Stage 3 — band ownership cleanup (~0.5 day + laps).** Flip `seamBand=true` with the §2.4 scope at `CrossPortalEntityRenderer.java:574`. Kills ledger 19/20 in reachable contexts. Gated on the stage-2b probe + lap; rollback = the flag.

**Stage 4 — deletions + constitution.** Remove the §5 dead list, tombstones; record §1.2 and the forbidden-inputs constitution in this file; update `migration/SEAM_SWEEP_HANDOFF.md`. Verify mixin bytecode per the standing rule (javap the loom deobf jar before first launch — 26.2 accessor drift hazard).

**Stage 5 (optional) — second clip plane.** `gl_ClipDistance[1]` in the entity shader path + a second plane upload in `GlCommandEncoderClipMixin:72-158`: closes the non-co-planar in-pass approximation AND gives the band painter an exact one-draw slab. Not required for any ledger entry. (§7-Q7.)

**Honest sizing:** ~4–6 engineering days plus interleaved user live rounds (spine's 3–4 + ~1 for the anchor layer + shadow-mode laps). Every stage has a one-step rollback (revert / flag / lever). Edit-tool-only file changes (PS5.1 UTF-8 rule); fable/opus tags for any spawned subagents.

---

## §7 RISK REGISTER + OPEN QUESTIONS FOR THE USER

### Risk register

| # | Risk | Mitigation |
|---|---|---|
| R1 | A stage-1 verdict diverges from a legacy gate in an unforeseen legitimate case | Shadow mode logs the divergence BEFORE the flip; per-delta revert; every cull self-attributes |
| R2 | Anchor vs entry divergence (layer-1/layer-2 disagreement) | Shadow-logged lap, then assert; anchor authoritative; entries never deleted so physics cannot regress |
| R3 | In-aperture behind-half band residue at grazing angles | Signature probe before stage 3; escalation = re-stencil the quad + stencil-gated ALWAYS-depth (never unstenciled ALWAYS) |
| R4 | Band stencil mark staleness across members | Per-member scissored clear (§2.5); Pass B self-clears its own fragments |
| R5 | Stage-3 retreat opens a slit in a context the scoping rule missed | §2.3 enumeration is normative; stage gating on the shallow-angle lap; rollback = flag |
| R6 | Draw-time consumer reads a popped Ctx | Documented submit-time constraint + module assert (§1.3) |
| R7 | Perf under many straddlers | Entry cap 6 bounds everything; band cost = ≤4 draws per literally-straddling member; measured under the probe pre-commit |
| R8 | Windows PS5.1 encoding mangling | Edit tool / .NET UTF8Encoding(false) only |
| R9 | Mixin drift on 26.2 | javap the deobf jar before first launch; no cross-class static calls in @Unique inits |

### Open questions FOR THE USER (decisions only you can make)

1. **Q1 — Commit go-ahead.** Stage −1 lands the uncommitted rounds 5–19 net. Still awaiting your explicit "commit it" (owed since the memo tip).
2. **Q2 — Band glint policy.** The band redraw either excludes blended/glint feature phases (default proposal: a ≤1cm glint GAP in the band strip) or includes them (a ≤1cm glint DOUBLE-BLEND where the body self-overlaps edge-on). Both are sub-centimeter; pick one.
3. **Q3 — Underwater straddle at stage 3.** Post-pass band fragments behind depth-writing translucent terrain (water, stained glass) are depth-rejected, so a straddle seen through water would carry the old ~1cm slit there. Options: (a) accept as a documented niche residual; (b) keep the in-window extension additionally when the sight line passes through translucent terrain (complexity); (c) live-test first and decide on evidence. Recommendation: (c), during the stage-2b lap.
4. **Q4 — History-less parked straddler.** A cart chunk-loaded already straddling (saved mid-crossing) has no derivable anchor. Default: deterministic larger-span tie-break, self-correcting at release (worst case: one crossing renders sided-wrong until it exits). Alternative: persist the anchor face in saved data (the `SeamOccupancySavedData` precedent). Persisting is more correct but adds a save-format field — your call.
5. **Q5 — Cross-dim live round.** The no-history FLIP path is the designed fix for the deferred cross-dim arc (round-7 keystone made structural), but NO design may claim ledger entry 1 "by construction" cross-dim until the live round runs (all three verdicts). Schedule it after stage 2a.
6. **Q6 — The real far-cart plain-sight ruling.** The same-dim-far station's REAL cart visible in open world ~690 blocks away was exhaustively exonerated as world geometry, not a draw defect. The acceptance harness must not count it as an engine failure — but whether it is acceptable UX is your ruling, still owed since round 3.
7. **Q7 — Stage 5 investment.** The optional second hardware clip plane closes the last honest approximation (seam crossings viewed inside non-co-planar/nested passes) and simplifies the band painter to one draw. Not needed for any ledger entry — invest or defer?

### Standing acceptance criteria

The engine passes iff: (1) every ledger entry 1–21 stays impossible across the full live script (both directions × ridden/empty × window/main/reverse views × same-dim-far, then cross-dim), with the harness watching ALL unit members unfiltered and labeling topology from the log; (2) the suite stays green with volume ceilings intact; (3) non-seam behavior is diff-provably unchanged; (4) the two ~1cm residuals are GONE in painter-reachable contexts (not re-documented), with Q3's niche explicitly ruled; (5) any future artifact's probe log self-attributes to a named verdict, transition, or table row.
