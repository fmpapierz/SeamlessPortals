#version 330

// IS1 (iris shaders-ON engagement, design S2.3 P-PASTE belt-and-suspenders): a VERBATIM
// mod-namespace copy of vanilla minecraft:core/blit_screen.fsh (the straight per-pixel copy --
// blend state lives on the PIPELINE, which declares none = blend OFF).

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(InSampler, texCoord);
}
