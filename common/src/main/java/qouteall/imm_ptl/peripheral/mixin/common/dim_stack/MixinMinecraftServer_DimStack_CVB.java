package qouteall.imm_ptl.peripheral.mixin.common.dim_stack;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement;

import java.util.Map;

/**
 * S19-C port of IP:peripheral/mixin/common/dim_stack/MixinMinecraftServer_DimStack_CVB —
 * the dim-stack server half (early bedrock-map priming + the apply-at-world-creation hook).
 * TWO 26.2-forced mechanical re-targets (recon wf_9aecac4e-330, pinned against mc262-ref):
 * (1) {@code createLevels} lost its ChunkProgressListener param (26.2 :421 is no-arg; the
 * progress type became LevelLoadListener read from a field) — IP's fully-qualified method
 * descriptor goes bare-name (the shipping precedent: MixinMinecraftServer_Misc:51 injects
 * "createLevels" RETURN the same way; the two RETURN injections coexist) and the handlers
 * drop the listener param. (2) {@code setInitialSpawn} gained a 5th arg (LevelLoadListener,
 * :482-484) — the INVOKE descriptor is updated. The semantic window is preserved on 26.2:
 * overworld created first, setInitialSpawn, the other dims, RETURN — so onServerEarlyInit
 * still sees only-overworld and onServerCreatedWorlds sees all levels (IP-identical).
 * 26.2 nuance (ledgered, coherent): the setInitialSpawn INVOKE sits inside
 * {@code if (!levelData.isInitialized())} — the early hook fires only on FRESH worlds
 * (exactly when bedrock replacement matters); the RETURN hook always fires and restores the
 * bedrock map from persisted storage on existing worlds.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer_DimStack_CVB {
    @Shadow
    public abstract ServerLevel getLevel(ResourceKey<Level> dimensionType);

    @Shadow
    @Final
    private Map<ResourceKey<Level>, ServerLevel> levels;

    @Inject(
        method = "createLevels",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;setInitialSpawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/ServerLevelData;ZZLnet/minecraft/server/level/progress/LevelLoadListener;)V"
        )
    )
    private void onBeforeSetupSpawn(CallbackInfo ci) {
        DimStackManagement.onServerEarlyInit((MinecraftServer) (Object) this);
    }

    @Inject(
        method = "createLevels",
        at = @At("RETURN")
    )
    private void onCreateWorldsFinishes(CallbackInfo ci) {
        DimStackManagement.onServerCreatedWorlds((MinecraftServer) (Object) this);
    }
}
