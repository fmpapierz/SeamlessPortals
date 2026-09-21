#version 330
#extension GL_ARB_separate_shader_objects : require

// 26.3: ported with the SAME four edits Mojang made to its own shaders (diffed 26.2 -> 26.3 jars: core/screenquad.vsh,
// core/blit_screen.fsh, core/position_color.vsh) because 26.3 compiles every shader with shaderc under Vulkan rules
// (GlslCompiler.java:79,121): (1) the GL_ARB_separate_shader_objects line, (2) #moj_import -> #include, (3) an explicit
// layout(location = N) on every in/out -- "SPIR-V requires location for user input/output" -- numbered exactly like
// vanilla's position_color (Position 0, Color 1; stage-to-stage by matching location), (4) gl_VertexID -> gl_VertexIndex.
// Nothing else in this file changed.

// IS5-HAND A/B SIBLING — the PRE-CAP stamp vertex shader, verbatim (selected at pipeline
// registration by -Dseamlessportals.disableStampHandDepthCap; the default is the capped
// portal_area_sample.vsh — see the cap comment there). With this shader the depth-clamped
// crossing-sliver fragments write reversed-Z 1.0 and the stamp paints the portal view OVER
// the iris-baked first-person hand along the seam — the reproduction leg.

#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * vec4(Position, 1.0);
    vertexColor = Color;
}
