#version 330

// IS1 -- the portalAreaSample STAMP vertex shader (iris shaders-ON engagement, design S2.3 /
// D20: IP's PORTAL_DRAW_FB_IN_AREA re-expressed). Transforms the camera-relative portal
// view-area mesh by ONE combined clip matrix uploaded as the Projection UBO
// (projection * modelView, premultiplied CPU-side -- no DynamicTransforms dependency).
// vertexColor is passed through (bound WHITE by the stamp driver = identity multiply in the
// fragment) -- declaring-and-USING Color keeps the POSITION_COLOR attribute live so the
// vertex-format binding never targets an optimized-out attribute.

#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * vec4(Position, 1.0);
    // IS5-HAND DEPTH CAP (2026-07-27; A/B = the nocap.vsh sibling via
    // -PdisableStampHandDepthCap). Under a pack, iris bakes the first-person hand into the
    // main frame PRE-anchor with a compressed depth slice MEASURED at 0.5556..0.5569
    // (reversed-Z; 45 probe samples). The stamp draws under GL_DEPTH_CLAMP, so the crossing
    // sliver's nearer-than-near fragments would write depth 1.0 and GEQUAL-paint the portal
    // view OVER the baked-in hand exactly along the seam (the hand "slices away"). Capping
    // NDC z at 0.5 keeps the sliver stamping over all world content beyond 10 cm (the band
    // fix intact — normal window fragments sit below 0.55 anyway) while ALWAYS losing to the
    // hand slice. w > 0 for every vertex here (the S14.36 CPU clip keeps only in-front-of-
    // camera geometry), so the min() is well-formed.
    gl_Position.z = min(gl_Position.z, 0.5 * gl_Position.w);
    vertexColor = Color;
}
