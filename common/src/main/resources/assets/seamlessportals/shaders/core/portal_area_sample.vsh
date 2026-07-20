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
    vertexColor = Color;
}
