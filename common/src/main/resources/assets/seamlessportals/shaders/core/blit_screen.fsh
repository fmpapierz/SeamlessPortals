#version 330
#extension GL_ARB_separate_shader_objects : require

// 26.3: ported with the SAME four edits Mojang made to its own shaders (diffed 26.2 -> 26.3 jars: core/screenquad.vsh,
// core/blit_screen.fsh, core/position_color.vsh) because 26.3 compiles every shader with shaderc under Vulkan rules
// (GlslCompiler.java:79,121): (1) the GL_ARB_separate_shader_objects line, (2) #moj_import -> #include, (3) an explicit
// layout(location = N) on every in/out -- "SPIR-V requires location for user input/output" -- numbered exactly like
// vanilla's position_color (Position 0, Color 1; stage-to-stage by matching location), (4) gl_VertexID -> gl_VertexIndex.
// Nothing else in this file changed.

// IS1 (iris shaders-ON engagement, design S2.3 P-PASTE belt-and-suspenders): a VERBATIM
// mod-namespace copy of vanilla minecraft:core/blit_screen.fsh (the straight per-pixel copy --
// blend state lives on the PIPELINE, which declares none = blend OFF).

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(InSampler, texCoord);
}
