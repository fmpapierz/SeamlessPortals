#version 330

// IS1 -- the portalAreaSample STAMP fragment shader (design S2.3 / D20). Samples the MAIN
// target's color at THIS fragment's own screen coordinate -- the 1:1 screen-space UV law
// (mining S3-8): the dest world was rendered full-screen with the same projection, so its
// pixels already sit at the correct screen positions; the portal SHAPE comes from the mesh's
// rasterized footprint + the reversed-Z GEQUAL depth test against the snapshot depth (both
// pipeline-side). texelFetch = exact integer-texel read (NEAREST semantics regardless of
// sampler state; the pass still binds NEAREST clamp-to-edge per the block-era fix #3).
// vertexColor is WHITE from the stamp driver -- the multiply is identity (copy stays exact).

uniform sampler2D InSampler;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0) * vertexColor;
}
