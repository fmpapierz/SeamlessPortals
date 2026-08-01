#version 330

// IS5-XCUT -- the SOLID-PAINT diagnostic fragment shader, carrying the same PER-FRAGMENT near
// floor as portal_area_sample_floor.fsh (2026-08-01). Identical to portal_area_solid.fsh except
// for the gl_FragDepth line at the bottom.
//
// WHY THIS SIBLING HAS TO EXIST: the solid variant is how the footprint-vs-depth question gets
// answered, and that reading is only trustworthy if a solid leg differs from a sample leg by the
// FRAGMENT OUTPUT ALONE. If solid kept the old per-vertex floor while the shipped sample pipeline
// used the per-fragment one, every future diagnostic leg would be measuring a different depth
// boundary than the one that ships -- exactly the "the probe measured a different configuration"
// failure this project has already paid for twice. The axes stay comparable.
//
// The floor's derivation, units (window space, 0.001), and the three-leg attribution are in
// portal_area_sample_floor.fsh -- single source of truth, do not restate it here where it would
// drift.
//
// The never-true sampler guard below is inherited verbatim from portal_area_solid.fsh: it keeps
// InSampler alive so the linker cannot strip it, which keeps the pipeline/pass/sampler shape
// identical to the sampling siblings. RGBA8 normalized alpha is in [0,1] so `a < -1.0` never
// discards, and the compiler cannot bound a texelFetch result, so the fetch survives with zero
// visual contribution.

uniform sampler2D InSampler;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 sampled = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
    if (sampled.a < -1.0) {
        discard;
    }
    fragColor = vertexColor;
    // The floor, computed per fragment: a true clamp, so it cannot tilt an interpolated plane.
    gl_FragDepth = max(gl_FragCoord.z, 0.001);
}
