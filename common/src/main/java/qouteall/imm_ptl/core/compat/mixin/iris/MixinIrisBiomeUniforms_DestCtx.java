package qouteall.imm_ptl.core.compat.mixin.iris;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisDestContext;

/**
 * IS5-DESTCTX (design §3 + panel folds B1/Q1, wf_ed89a27c-2de) — dest-context the biome
 * family's player reads inside iris's five per-uniform lambdas.
 *
 * <p>⟦J⟧ TARGETS ARE BYTECODE-PINNED against the byte-identical runtime jar (irisdump
 * SHA-verified vs maven.modrinth/iris/1.11.2+26.2-fabric): the {@code Level.getBiome}
 * INVOKEs live in {@code lambda$addBiomeUniforms$0..$4} (biome, biome_category,
 * biome_precipitation, rainfall, temperature) — NOT in the playerI/playerF helper lambdas
 * (those only fetch {@code Minecraft.player}). Redirecting {@code LocalPlayer.level()} +
 * {@code LocalPlayer.blockPosition()} in those five bodies (the panel's preferred variant)
 * also dest-ifies $2's {@code getPrecipitationAt(pos, seaLevel)} tail, which a
 * getBiome-only redirect would leave half-source.
 *
 * <p>⟦J⟧ fold Q1 config: {@code @Pseudo} + {@code remap=false} (mojmap-named dev runtime,
 * the DestPrevWrite precedent) + {@code require=0, expect=0} (the mixin json's
 * defaultRequire=1 would hard-crash on a shape drift — the house rule is disarm-loud,
 * never crash). The liveness watchdog lives in {@link IrisDestContext}: ACTIVE pushes with
 * zero redirect hits WARN once (require=0 must not hide a dead fix). On any iris bump,
 * re-javap BiomeUniforms before first launch (the mixin-bytecode-verification rule).
 *
 * <p>Inactive context ⇒ the vanilla reads run unchanged — same-dim views and every
 * non-window frame are byte-identical by construction (fold B2's predicate is applied by
 * the pusher, MyGameRenderer's shell bracket).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.BiomeUniforms", remap = false)
public class MixinIrisBiomeUniforms_DestCtx {

    @Redirect(
        method = {
            "lambda$addBiomeUniforms$0",
            "lambda$addBiomeUniforms$1",
            "lambda$addBiomeUniforms$2",
            "lambda$addBiomeUniforms$3",
            "lambda$addBiomeUniforms$4"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;"
        ),
        require = 0, expect = 0
    )
    private static Level ip_destCtxLevel(LocalPlayer player) {
        if (IrisDestContext.isActive()) {
            IrisDestContext.noteRedirectHit();
            return IrisDestContext.level();
        }
        return player.level();
    }

    @Redirect(
        method = {
            "lambda$addBiomeUniforms$0",
            "lambda$addBiomeUniforms$1",
            "lambda$addBiomeUniforms$2",
            "lambda$addBiomeUniforms$3",
            "lambda$addBiomeUniforms$4"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;blockPosition()Lnet/minecraft/core/BlockPos;"
        ),
        require = 0, expect = 0
    )
    private static BlockPos ip_destCtxBlockPos(LocalPlayer player) {
        if (IrisDestContext.isActive()) {
            return IrisDestContext.pos();
        }
        return player.blockPosition();
    }
}
