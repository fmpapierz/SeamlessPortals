package qouteall.imm_ptl.peripheral.mixin.common.dim_stack;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement;

import java.util.concurrent.CompletableFuture;

/**
 * S19-C 1:1 port of IP:peripheral/mixin/common/dim_stack/
 * MixinChunkStatusTasks_BedrockReplacement — dim-stack bedrock replacement at spawn-step
 * chunk generation. ZERO 26.2 changes needed (recon wf_9aecac4e-330: the generateSpawn
 * signature is byte-identical on 26.2, ChunkStatusTasks.java:169-171; WorldGenContext keeps
 * its level() accessor).
 */
@Mixin(ChunkStatusTasks.class)
public class MixinChunkStatusTasks_BedrockReplacement {
    @Inject(
        method = "generateSpawn", at = @At("HEAD")
    )
    private static void onGenerateSpawn(
        WorldGenContext worldGenContext, ChunkStep chunkStep, StaticCache2D<GenerationChunkHolder> staticCache2D, ChunkAccess chunkAccess, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        DimStackManagement.replaceBedrock(worldGenContext.level(), chunkAccess);
    }
}
