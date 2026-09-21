#version 330
#extension GL_ARB_separate_shader_objects : require

// 26.3: ported with the SAME four edits Mojang made to its own shaders (diffed 26.2 -> 26.3 jars: core/screenquad.vsh,
// core/blit_screen.fsh, core/position_color.vsh) because 26.3 compiles every shader with shaderc under Vulkan rules
// (GlslCompiler.java:79,121): (1) the GL_ARB_separate_shader_objects line, (2) #moj_import -> #include, (3) an explicit
// layout(location = N) on every in/out -- "SPIR-V requires location for user input/output" -- numbered exactly like
// vanilla's position_color (Position 0, Color 1; stage-to-stage by matching location), (4) gl_VertexID -> gl_VertexIndex.
// Nothing else in this file changed.

// IS1 -- the portalAreaSample STAMP fragment shader (design S2.3 / D20). Samples the MAIN
// target's color at THIS fragment's own screen coordinate -- the 1:1 screen-space UV law
// (mining S3-8): the dest world was rendered full-screen with the same projection, so its
// pixels already sit at the correct screen positions; the portal SHAPE comes from the mesh's
// rasterized footprint + the reversed-Z GEQUAL depth test against the snapshot depth (both
// pipeline-side). texelFetch = exact integer-texel read (NEAREST semantics regardless of
// sampler state; the pass still binds NEAREST clamp-to-edge per the block-era fix #3).
// vertexColor is WHITE from the stamp driver -- the multiply is identity (copy stays exact).

uniform sampler2D InSampler;

layout(location = 0) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0) * vertexColor;
}
