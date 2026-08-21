# PASTE-READY PROMPT — the seam band residuals, fresh session

Read `migration/SEAM_BAND_HANDOFF.md` in the worktree
`E:\Immersive Portals - Copy\.claude\worktrees\particle-seam-regression-c2f79c`
(branch `claude/particle-seam-regression-c2f79c`) BEFORE doing anything else, then
`migration/SEAM_ENTITY_ENGINE_DESIGN.md`, then the memory entry "Seam regression sweep
2026-08-10". Do not re-derive their history; do not re-litigate the proven-facts table.

STATE: the seam entity-crossing engine is landed and live-proven except ONE defect family:
(a) a tail-end sliver as the last bit of a cart/cow crosses the seam, and (b) wrong-side
seam-cell bleeds (sliver to larger, angle-dependent, both directions). Twenty-seven live
rounds and three multi-agent research verdicts have PROVEN every individual layer of the
current painter stack correct (draws execute, land, clip correctly, transform correctly,
gating correct) — and the artifacts persist. The analytic route is EXHAUSTED. We have been
going in circles; the missing ingredient is pixel-level attribution.

THE MANDATE (the user's words): "no quick cheap fixes, all fixes must be well researched and
based on evidence"; "extreme deep research and fresh ideas"; zero tolerance — "completely
continuous, no wrong clipping and no wrong bleed AT ALL."

YOUR OPENING MOVES, in order:
1. Build the PIXEL ATTRIBUTION instruments before any hypothesis work (handoff §4):
   the per-painter TINT uniform through the proven shader-injection triple (derive the
   injection anchor from the actual patched entity shader sources — the previously claimed
   anchor does not exist), and/or set the user up with a RenderDoc capture of one artifact
   frame — one captured frame answers every open question at once (which draw owns each
   artifact pixel, the stored depth there, stencil state). Offer the RenderDoc route to the
   user FIRST; it is the single highest-information action available.
2. MEASURE the artifact before theorizing: its world-space thickness (is it even the
   ±ADJUSTMENT band?), its aperture-relative position, its angle dependence (the bias-ladder
   diagnostic in handoff §4.3 quantifies the depth deficit in one lap).
3. Run an extreme-deep-research workflow (multi-agent, adversarial) that takes the handoff's
   §5 fresh directions as first-class hypotheses — especially: question whether the sliver is
   the epsilon band at all; question whether the bleed is entity paint at all (portal quad,
   seam-cell terrain redraw, particles never ruled out); the delete-the-retreat family
   (polygon-offset / sub-epsilon retreat / measured acceptance of the coplanar fight);
   the depth-wall consequence (in-aperture behind-plane content can ONLY be painted in-pass —
   verify the in-pass extension actually reaches the GPU on artifact draws); and the
   fuse-view discriminator lap.
4. Only after the instruments have attributed the artifact pixels: fix, one variable per lap,
   with the staged candidates in handoff §2 (artifacts 4/5 have evidence-gated fixes already
   designed but deliberately unlanded).

RULES THAT HAVE TEETH (all earned this arc — handoff §6): one variable per lap; assert the
outcome, not the request; only clip planes / glDepthRange / stencil func+op survive per-draw
pipeline application (color/depth masks and depth func do NOT); suite green (`ALL LEGS PASS`
in that run's own log) before any commit; user tests first, suite once pre-commit; Edit-tool
or .NET-UTF8 file writes only; javap before new mixin targets.

The environment quirks, run commands, lever inventory, and probe vocabulary are all in the
handoff §3. The user's fixture is the same-dim-far cart+cow loop; they respond fast and test
precisely — give them ONE thing to look at per lap and say exactly what each possible
observation would prove.
