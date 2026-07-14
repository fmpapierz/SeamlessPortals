package qouteall.imm_ptl.core.mixin.common.container_gui;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(ContainerOpenersCounter.class)
public abstract class MixinContainerOpenersCounter {
    // S10-C: 26.2 promoted isOwnContainer from protected to public
    // (ContainerOpenersCounter.java:26).
    @Shadow
    public abstract boolean isOwnContainer(Player player);

    // the container could be opened via portal. the player could be anywhere in any dimension
    // check all players
    //
    // S10-C retarget (NEEDS-RETARGET, mixin-common.md §6): 1.21.3
    // getPlayersWithContainerOpen(Level,BlockPos):List<Player> is GONE — 26.2 replaces it with
    // getEntitiesWithContainerOpen(Level,BlockPos):List<ContainerUser>
    // (ContainerOpenersCounter.java:51), whose vanilla body only AABB-scans the container's own
    // level (`level.getEntities(null, searchBox, ...)`) and therefore MISSES cross-dimension
    // openers — the exact case IP guards. Faithful re-implementation: iterate every server
    // player (all dimensions) and keep IP's isOwnContainer(player) membership test, returning
    // them as List<ContainerUser> (Player implements ContainerUser, Player.java:126). Feeds
    // recheckOpeners' cross-dim open-count + maxInteractionRange recompute (:66-93).
    @Inject(method = "getEntitiesWithContainerOpen", at = @At("HEAD"), cancellable = true)
    private void getOpenCount(Level level, BlockPos pos, CallbackInfoReturnable<List<ContainerUser>> cir) {
        List<ContainerUser> list = new ArrayList<>();

        MinecraftServer server = level.getServer();
        assert server != null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isOwnContainer(player)) {
                list.add(player);
            }
        }

        cir.setReturnValue(list);
    }
}
