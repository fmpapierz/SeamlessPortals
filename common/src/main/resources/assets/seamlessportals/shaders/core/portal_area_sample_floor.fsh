#version 330

// IS5-XCUT -- the stamp fragment shader carrying the NEAR FLOOR **PER FRAGMENT** (2026-08-01).
// Identical to portal_area_sample.fsh except for the gl_FragDepth line at the bottom.
//
// WHY THE FLOOR MOVED HERE FROM THE VERTEX SHADER. The floor used to live in
// portal_area_sample.vsh as `gl_Position.z = max(gl_Position.z, -0.998 * gl_Position.w)`.
// Depth interpolates SCREEN-AFFINE across a triangle, so clamping per VERTEX computes
// L[max(z,c)] where the correct value is max(L[z],c): it TILTS the interpolated depth plane
// instead of clamping it. The S14.36 CPU clip (viewZ = -1e-4) leaves one aperture vertex ~0.1 mm
// from the eye whose true NDC z is ~-1e3; flooring that ONE vertex skewed the whole plane, and the
// resulting depth error is affine in screen space => a STRAIGHT boundary, pinned where the
// unfloored vertices are (so it pivots about a corner) and SWEEPING as the clip-produced vertex
// slides along the portal edge with camera rotation. That is the user-reported
// "the window moves/changes shape when I pan near the seam".
//
// ATTRIBUTED BY THREE USER-VERIFIED LEGS, each config-proven in the log:
//   depth ON  + floor ON  -> cut PRESENT   (baseline)
//   depth OFF + floor ON  -> cut GONE      (-PdebugStampSolid -PdisableStampDepthTest;
//                                           the raw footprint is a clean stable rectangle,
//                                           so the aperture GEOMETRY is innocent)
//   depth ON  + floor OFF -> cut GONE      (-PdisableStampHandDepthCap, bound=SOLID vsh=NOCAP,
//                                           probe CAP-IN-SOURCE=false x14)
// The cut requires BOTH => the per-vertex floor is the carrier. The clip, the mesh and the
// projection are exonerated and are NOT touched by this fix.
//
// THE CONSTANT IS 0.001 IN **WINDOW** SPACE, AND THAT IS MEASURED, NOT ASSUMED.
// gl_FragCoord.z and gl_FragDepth are both post-viewport window depth. The stamp draw runs under
// glDepthRange(0,1): the draw-time probe reported range=[0.0000,1.0000] on all 26 sampled stamp
// draws across both the floor-ON (vsh=capped) and floor-OFF (vsh=NOCAP) legs, including in-window
// samples. The hand's glDepthRange(0.0, 0.0005) bracket is SEQUENTIAL with the stamp, not nested
// around it (IrisHandSeamDepthBracket sets it, then restores (0,1) before this draw). With
// clipDepthMode=NEGATIVE_ONE_TO_ONE (also measured, 39/39) the old vertex constant NDC -0.998 maps
// to window 0.5*(-0.998)+0.5 = 0.001 -- so this is the SAME depth the shipped floor targeted, in
// the units this stage works in. Do not "convert" it again.
//
// WHAT THE FLOOR IS FOR (closed, user-confirmed arc -- do not weaken): it keeps the portal window
// BEHIND the first-person hand at a crossing. The hand is bracketed into window depth [0, 0.0005];
// pinning the window at 0.001 leaves ~8000 representable 24-bit steps of margin while staying well
// in front of the scene content it replaces (~0.98). Under GL_DEPTH_CLAMP (measured ON) the
// aperture's projected depth otherwise falls to ~0 at the crossing and LEQUAL lets it sweep across
// the hand.
//
// COST: writing gl_FragDepth disables early-Z for this draw. Accepted -- the stamp is one
// portal-shaped quad, not a scene pass, and correctness of the depth boundary is the entire point.
// The depth WRITE is preserved (writeMask=true, measured), so the multi-portal ordering fix (#13:
// the near portal's plane depth rejects the far stamp) still holds -- the value written is now the
// clamped one at every pixel, which is what that fix wanted in the first place.

uniform sampler2D InSampler;

in vec4 vertexColor;

out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0) * vertexColor;
    // The floor, computed per fragment: a true clamp, so it cannot tilt an interpolated plane.
    gl_FragDepth = max(gl_FragCoord.z, 0.001);
}
