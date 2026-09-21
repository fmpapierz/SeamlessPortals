#version 330
#extension GL_ARB_separate_shader_objects : require

// 26.3: ported with the SAME four edits Mojang made to its own shaders (diffed 26.2 -> 26.3 jars: core/screenquad.vsh,
// core/blit_screen.fsh, core/position_color.vsh) because 26.3 compiles every shader with shaderc under Vulkan rules
// (GlslCompiler.java:79,121): (1) the GL_ARB_separate_shader_objects line, (2) #moj_import -> #include, (3) an explicit
// layout(location = N) on every in/out -- "SPIR-V requires location for user input/output" -- numbered exactly like
// vanilla's position_color (Position 0, Color 1; stage-to-stage by matching location), (4) gl_VertexID -> gl_VertexIndex.
// Nothing else in this file changed.

// IS1 -- the portalAreaSample STAMP vertex shader (iris shaders-ON engagement, design S2.3 /
// D20: IP's PORTAL_DRAW_FB_IN_AREA re-expressed). Transforms the camera-relative portal
// view-area mesh by ONE combined clip matrix uploaded as the Projection UBO
// (projection * modelView, premultiplied CPU-side -- no DynamicTransforms dependency).
// vertexColor is passed through (bound WHITE by the stamp driver = identity multiply in the
// fragment) -- declaring-and-USING Color keeps the POSITION_COLOR attribute live so the
// vertex-format binding never targets an optimized-out attribute.

#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * vec4(Position, 1.0);
    // IS5-STAMP-EAT NEAR FLOOR (2026-07-28; A/B = the nocap.vsh sibling via
    // -PdisableStampHandDepthCap). MEASURED, per-pixel, at the anchor->blit boundary: at the
    // crossing (camera distance ~0.00 to the portal plane) EVERY hand pixel is overpainted by
    // the stamp (359/359, mean |dlum| 0.41), while at distance 0.29 none are (0/359) — the
    // progressive slice. Mechanism: the stamp executes func=LEQUAL (draw-time ground truth,
    // trySetup RETURN) under GL_DEPTH_CLAMP, and as the camera reaches the portal plane the
    // aperture's projected depth falls to the near plane (clamped to ~0.0). Once it drops
    // BELOW the hand's depth, LEQUAL lets the aperture win — and it sweeps across the hand as
    // more of the aperture crosses that threshold.
    //
    // The previous line capped the FAR side (min(z, 0.5w)) — the reversed-Z assumption, which
    // this buffer does not use (the hand pass proves small-is-near/LEQUAL: hand 0.5546 beats
    // scene 0.9945). Capping the far side cannot stop a near-plane-clamped fragment. The
    // correct guard is a NEAR FLOOR: keep every stamp fragment at window depth >= 0.005
    // (NDC z >= -0.99), which is
    //   * ALWAYS behind the IS5-HAND bracket's hand (remapped into [0, 0.0005]) => the hand can
    //     never be overpainted, at any crossing distance; the two fixes compose by design, and
    //   * still in front of everything the window must replace (scene behind the portal sits
    //     at ~0.98), so window content and the C4-SEAM band fix are unaffected.
    //
    // FLOOR VALUE — TIGHT BY NECESSITY (live-corrected 2026-07-28). The first floor was
    // window depth 0.005, which under LEQUAL also loses to REAL geometry nearer than ~10 cm:
    // at a crossing that is the portal frame / doorway blocks around the camera, so the window
    // stopped painting wherever near geometry was in view and its covered region shrank and
    // shifted as the camera panned (user-observed regression: "the window moves and changes
    // shape"). The floor must clear the HAND and nothing else, so it now sits at window depth
    // 0.001 (NDC -0.998) against a hand pinned into [0, 0.0005]: a 0.0005 margin is ~8000
    // representable steps on a 24-bit depth buffer, while real scene geometry must be within
    // ~5 cm (essentially at the near plane) before it can occlude the window.
    // w > 0 for every vertex here (the S14.36 CPU clip keeps only in-front-of-camera
    // geometry), so the max() is well-formed.
    gl_Position.z = max(gl_Position.z, -0.998 * gl_Position.w);
    vertexColor = Color;
}
