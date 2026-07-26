package qouteall.imm_ptl.peripheral.portal_generation;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo;
import qouteall.imm_ptl.core.portal.custom_portal_gen.form.NetherPortalLikeForm;
import qouteall.imm_ptl.core.portal.custom_portal_gen.form.PortalGenForm;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity;

import java.util.function.Predicate;

// S16 1:1 port of IP:peripheral/portal_generation/IntrinsicNetherPortalForm.java:1-102.
// encounteredVanillaPortalBlock stays STATIC volatile per IP :66-67 ("not per-player, but
// mostly fine" — IP's own comment); getCodec() throws by design (intrinsic forms are
// code-registered, never serialized).
public class IntrinsicNetherPortalForm extends NetherPortalLikeForm {
    public IntrinsicNetherPortalForm() {
        super(true);
    }

    @Override
    public void generateNewFrame(ServerLevel fromWorld, BlockPortalShape fromShape, ServerLevel toWorld, BlockPortalShape toShape) {
        for (BlockPos blockPos : toShape.frameAreaWithCorner) {
            toWorld.setBlockAndUpdate(blockPos, Blocks.OBSIDIAN.defaultBlockState());
        }
    }

    @Override
    public PortalGenInfo getNewPortalPlacement(
        ServerLevel toWorld, BlockPos toPos,
        ServerLevel fromWorld, BlockPortalShape fromShape,
        @Nullable Entity triggeringEntity
    ) {
        if (encounteredVanillaPortalBlock) {
            encounteredVanillaPortalBlock = false;
            if (IPGlobal.enableWarning) {
                if (triggeringEntity instanceof ServerPlayer player) {
                    // 26.2: displayClientMessage(chat=false) -> sendSystemMessage (the S13
                    // translation the ported form files already use, NetherPortalLikeForm:167).
                    player.sendSystemMessage(
                        Component.translatable("imm_ptl.cannot_connect_to_vanilla_portal")
                    );
                }
            }
        }

        return super.getNewPortalPlacement(toWorld, toPos, fromWorld, fromShape, triggeringEntity);
    }

    @Override
    public BreakablePortalEntity[] generatePortalEntitiesAndPlaceholder(PortalGenInfo info) {
        info.generatePlaceholderBlocks();
        BreakablePortalEntity[] portals = info.generateBiWayBiFacedPortal(NetherPortalEntity.ENTITY_TYPE);

        return portals;
    }

    // not per-player, but mostly fine
    private static volatile boolean encounteredVanillaPortalBlock = false;

    @Override
    public Predicate<BlockState> getOtherSideFramePredicate() {
        return blockState -> {
            if (O_O.isObsidian(blockState)) {
                return true;
            }
            Block block = blockState.getBlock();
            if (block == Blocks.NETHER_PORTAL) {
                encounteredVanillaPortalBlock = true;
            }
            return false;
        };
    }

    @Override
    public Predicate<BlockState> getThisSideFramePredicate() {
        return O_O::isObsidian;
    }

    @Override
    // RECORDED IP DEVIATION — RS PASSTHROUGH (a); revert with
    // -Dseamlessportals.disableAperturePassthrough=true. IP-core edit 5 of REDSTONE_A_SPEC.md §3.1.
    // IP demands a bare-air opening to find a frame at all. That makes a frame containing a
    // surviving rail line permanently un-relightable, which contradicts the user's break rule
    // (REDSTONE_RECON.md §0.4). Widened to the passthrough/support union here; the real
    // position-aware rule is applied by ApertureOccupancy.ignitionAreaAcceptable, because this
    // predicate has no coordinates to express "a support cube, but only under a rail".
    public Predicate<BlockState> getAreaPredicate() {
        return com.warwa.seamlessportals.passthrough.ApertureOccupancy.areaPredicate();
    }

    @Override
    public MapCodec<? extends PortalGenForm> getCodec() {
        throw new RuntimeException();
    }

    @Override
    public PortalGenForm getReverse() {
        return this;
    }
}
