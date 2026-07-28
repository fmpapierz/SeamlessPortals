#version 330

// IS5-HAND A/B SIBLING — the PRE-CAP stamp vertex shader, verbatim (selected at pipeline
// registration by -Dseamlessportals.disableStampHandDepthCap; the default is the capped
// portal_area_sample.vsh — see the cap comment there). With this shader the depth-clamped
// crossing-sliver fragments write reversed-Z 1.0 and the stamp paints the portal view OVER
// the iris-baked first-person hand along the seam — the reproduction leg.

#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;

out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * vec4(Position, 1.0);
    vertexColor = Color;
}
