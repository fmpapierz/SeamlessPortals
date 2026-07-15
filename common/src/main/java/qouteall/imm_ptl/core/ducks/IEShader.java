package qouteall.imm_ptl.core.ducks;

import org.jetbrains.annotations.Nullable;

// S4-carried GONE-type duck. IP 1.21.3 imported `com.mojang.blaze3d.shaders.Uniform` (a leftover
// vestigial import even in IP — the interface body never used it); that class is GONE on 26.2
// (render-core G5/G9: the RenderSystem.getShader / CompiledShaderProgram / Uniform stack was removed
// for the core-profile pipeline model). Per api-map/mixin-client §8 (MixinShaderInstance row) this
// cached-uniform-location duck is TARGET-GONE with NO 26.2 equivalent and none is needed — UBO
// slices bind by name per pass; the functionality is owned by the FrontClipping shader redesign.
// The dead `Uniform` import is removed (it blocked the S12 pre-closure probe); the interface itself
// is UNREFERENCED live code (only FrontClipping.java comments name it) and stays HELD until the
// FrontClipping redesign either revives or retires it. Interface body kept verbatim from IP.
public interface IEShader {
    int ip_getClippingEquationUniformLocation();
}
