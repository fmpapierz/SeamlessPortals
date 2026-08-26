package qouteall.imm_ptl.core.mixin.common.interaction;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

/**
 * NF-PARITY live fix (2026-08-26, user-reported: cross-seam PLACE works, BREAK does not —
 * NEOFORGE_ONLY): NeoForge's {@code ServerPlayerGameMode} patch moves the actual
 * block-removal lines out of {@code destroyBlock} into a patched-in private helper
 * {@code removeBlock(BlockPos, BlockState, boolean, ItemStack)} (NF
 * ServerPlayerGameMode.java:312-327 — {@code state.onDestroyedByPlayer(this.level, ...)} +
 * {@code getBlock().destroy(this.level, ...)} + {@code getFluidState}). That helper is NOT
 * in {@link MixinServerPlayerGameMode}'s level-redirect method list (it does not exist on
 * vanilla/Fabric), so during a cross-portal break the removal ran against the PLAYER'S
 * dimension at the target coords instead of the redirect context's dimension — the far-side
 * block never broke, while placement (whose NF patch has no such refactor) worked.
 *
 * <p>Same redirect semantics as the parent mixin's {@code redirectGetLevel}, all
 * {@code this.level} GETFIELDs in the helper. Applied only on NeoForge
 * ({@code NEOFORGE_ONLY_MIXINS} — the target method is NF-patched-in), where
 * {@code defaultRequire = 1} makes a zero-match a hard boot failure.
 */
@Mixin(ServerPlayerGameMode.class)
public class MixinServerPlayerGameMode_RemoveBlockNeoForge {

    @Shadow protected ServerLevel level;

    @Redirect(
        method = "removeBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;ZLnet/minecraft/world/item/ItemStack;)Z",
        at = @At(
            value = "FIELD",
            opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/server/level/ServerPlayerGameMode;level:Lnet/minecraft/server/level/ServerLevel;"
        ),
        remap = false
    )
    private ServerLevel ip_redirectLevelInRemoveBlock(ServerPlayerGameMode instance) {
        BlockManipulationServer.Context redirect =
            BlockManipulationServer.REDIRECT_CONTEXT.get();
        if (redirect != null) {
            return redirect.world();
        }
        return level;
    }
}
