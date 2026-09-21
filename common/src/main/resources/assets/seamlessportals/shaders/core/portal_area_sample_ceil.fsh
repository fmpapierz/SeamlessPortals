#version 330
#extension GL_ARB_separate_shader_objects : require

// 26.3 -- the stamp fragment shader for a draw that executes GEQUAL (large-is-near). Identical to
// portal_area_sample_floor.fsh except for the gl_FragDepth line at the bottom: that file's NEAR FLOOR, mirrored.
//
// WHY A MIRROR IS NEEDED. The floor file's derivation is correct and still stands, for the convention it was measured
// under: on 26.2 the stamp EXECUTED LEQUAL (small-is-near), the first-person hand was bracketed into window depth
// [0, 0.0005], and max(z, 0.001) kept the window behind that hand. On 26.3 the draw-time dump (-PhandDrawDump) reads
// the stamp's executed state as `func=GEQUAL write=true range=[0,1] clamp=true` (29/29 and 31/31 rows, crossing
// gametest under Complementary Reimagined), and the hand's own draws likewise read GEQUAL (57/57). Under GEQUAL the near
// side is LARGE: as the camera reaches the portal plane the aperture's projected depth rises to the near plane and
// GL_DEPTH_CLAMP pins it at 1.0, which beats the hand (bracketed into [0.9995, 1.0] for GEQUAL) -- a max() floor cannot
// touch that side at all. MEASURED (-PgametestSameDimWalk -PhandTeleportProbe, walking through a same-dim portal):
// the hand region of the finished frame shows the portal view instead of the hand in the last 1-2 frames before every
// crossing (3/3, -PdebugTintStamp turns exactly those pixels magenta = this stamp), 0/3 without shaders -- the user's
// "tiny split second when teleporting ow-ow where the hand disappears/reappears".
//
// THE CONSTANT: 0.999 in WINDOW space = the floor's 0.001 reflected about the middle of the range, against the hand's
// reflected bracket [1 - 0.0005, 1]: the same 0.0005 margin (~8000 representable 24-bit steps), and real scene geometry
// must still be within ~5 cm (essentially at the near plane) before it can occlude the window. Far fragments are
// untouched here -- which the floor, run under GEQUAL, did NOT leave alone (it lifted every fragment beyond window depth
// 0.001 up to 0.001, letting a distant window pass over distant scenery in front of it).
//
// Selected per draw by IrisCompatPaste from the depth function the stamp's own previous draw EXECUTED (read at
// GlCommandEncoder.setupDraw RETURN) -- never from a constant.

uniform sampler2D InSampler;

layout(location = 0) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0) * vertexColor;
    // The ceiling, computed per fragment (a true clamp, like the floor it mirrors).
    gl_FragDepth = min(gl_FragCoord.z, 0.999);
}
