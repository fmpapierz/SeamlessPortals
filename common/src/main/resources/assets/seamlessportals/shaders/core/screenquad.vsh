#version 330

// IS1 (iris shaders-ON engagement, design S2.3 P-PASTE belt-and-suspenders): a VERBATIM
// mod-namespace copy of vanilla minecraft:core/screenquad.vsh, so no substitution machinery can
// ever key our straight-copy pipeline by shader asset. Keep byte-identical to vanilla's logic.

out vec2 texCoord;

void main() {
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vec4 pos = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);

    gl_Position = pos;
    texCoord = uv;
}
