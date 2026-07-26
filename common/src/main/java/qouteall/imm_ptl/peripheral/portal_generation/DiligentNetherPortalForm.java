package qouteall.imm_ptl.peripheral.portal_generation;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo;
import qouteall.imm_ptl.core.portal.custom_portal_gen.form.AbstractDiligentForm;
import qouteall.imm_ptl.core.portal.custom_portal_gen.form.PortalGenForm;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity;

import java.util.function.Predicate;

// S16 1:1 port of IP:peripheral/portal_generation/DiligentNetherPortalForm.java:1-63 (the
// default-adaptive-mode form — tries to match/reuse existing frames before fabricating).
public class DiligentNetherPortalForm extends AbstractDiligentForm {
    public DiligentNetherPortalForm() {
        super(true);
    }

    @Override
    public void generateNewFrame(ServerLevel fromWorld, BlockPortalShape fromShape, ServerLevel toWorld, BlockPortalShape toShape) {
        for (BlockPos blockPos : toShape.frameAreaWithCorner) {
            toWorld.setBlockAndUpdate(blockPos, Blocks.OBSIDIAN.defaultBlockState());
        }
    }

    @Override
    public BreakablePortalEntity[] generatePortalEntitiesAndPlaceholder(PortalGenInfo info) {
        info.generatePlaceholderBlocks();
        BreakablePortalEntity[] portals = info.generateBiWayBiFacedPortal(NetherPortalEntity.ENTITY_TYPE);

        return portals;
    }

    @Override
    public Predicate<BlockState> getOtherSideFramePredicate() {
        return O_O::isObsidian;
    }

    @Override
    public Predicate<BlockState> getThisSideFramePredicate() {
        return O_O::isObsidian;
    }

    @Override
    // RECORDED IP DEVIATION — RS PASSTHROUGH (a); IP-core edit 6, the sibling of edit 5 in
    // IntrinsicNetherPortalForm. Both forms must widen or a frame containing a rail is findable by
    // one ignition path and not the other.
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
