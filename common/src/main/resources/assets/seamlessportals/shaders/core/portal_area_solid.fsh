#version 330

// IS5-SEAM -- the SOLID-PAINT discriminator fragment shader (diagnostic only, selected solely by
// -Dseamlessportals.debugStampSolid; never shipped on). Paints the stamp's vertex color DIRECTLY,
// ignoring the sampled main-target content. Exists because the magenta coverage tint in
// portal_area_sample.fsh is a MULTIPLY (tint x sampled): on sampled content that is already pure
// black the tint is invisible, so "the band did not turn magenta" cannot distinguish "no fragment
// landed" from "fragments landed painting black content". This shader removes the content term
// entirely: any fragment that survives the pipeline paints solid vColor (WHITE, or MAGENTA under
// -PdebugTintStamp), so a band that stays black under this lever is proof NO fragment survived
// there.
//
// The sampled value is kept alive behind a never-true guard. VERIFIED CONSEQUENCE of letting the
// linker strip InSampler (26.2 bytecode: GlProgram.setupUniforms / GlCommandEncoder.trySetup): it
// would be BENIGN — one "does not use sampler ... might be a bug" warn at compile, then the pass's
// bindTexture entry is silently ignored (samplers are bound by iterating the LINKED program's
// active uniforms, and the bind-group validation set contains no sampler type). The guard exists
// to keep the log clean of that scary warn and to keep the pipeline/pass/sampler shape IDENTICAL
// to the sampling siblings, so a solid leg differs from a sample leg by the fragment output alone.
// RGBA8 normalized alpha is in [0,1] so `a < -1.0` never discards, and the compiler cannot bound
// a texelFetch result, so the fetch survives with zero visual contribution.

uniform sampler2D InSampler;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 sampled = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
    if (sampled.a < -1.0) {
        discard;
    }
    fragColor = vertexColor;
}
