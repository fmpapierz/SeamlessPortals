package qouteall.imm_ptl.core.portal.custom_portal_gen.form;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGeneration;
import qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.FastBlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.FrameSearching;
import qouteall.imm_ptl.core.portal.nether_portal.GeneralBreakablePortal;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalGeneration;
import qouteall.q_misc_util.my_util.IntBox;

import java.util.function.Predicate;

public abstract class NetherPortalLikeForm extends PortalGenForm {
    public final boolean generateFrameIfNotFound;

    public NetherPortalLikeForm(boolean generateFrameIfNotFound) {
        this.generateFrameIfNotFound = generateFrameIfNotFound;
    }

    @Override
    public boolean perform(
        CustomPortalGeneration cpg,
        ServerLevel fromWorld, BlockPos startingPos,
        ServerLevel toWorld,
        @Nullable Entity triggeringEntity
    ) {
        if (!NetherPortalGeneration.checkPortalGeneration(fromWorld, startingPos)) {
            return false;
        }

        Predicate<BlockState> areaPredicate = getAreaPredicate();
        Predicate<BlockState> thisSideFramePredicate = getThisSideFramePredicate();
        Predicate<BlockState> otherSideFramePredicate = getOtherSideFramePredicate();

        BlockPortalShape fromShape = NetherPortalGeneration.findFrameShape(
            fromWorld, startingPos,
            areaPredicate, thisSideFramePredicate
        );

        if (fromShape == null) {
            return false;
        }

        // RECORDED IP DEVIATION — RS PASSTHROUGH (a); IP-core edit 9 of REDSTONE_A_SPEC.md §3.1.
        // The position-aware ignition rule (REDSTONE_RECON.md §0.3): rails/redstone always admitted,
        // a support cube only when it carries a passthrough block directly above, frame still at
        // least half air. Placed HERE, immediately after the shape is found, because getAreaPredicate
        // returns Predicate<BlockState> with no coordinates and cannot express the rule. Under the
        // disable lever this reduces to IP's original all-air requirement.
        if (!com.warwa.seamlessportals.passthrough.ApertureOccupancy
                .ignitionAreaAcceptable(fromWorld, fromShape)) {
            return false;
        }

        if (!testThisSideShape(fromWorld, fromShape)) {
            return false;
        }

        if (NetherPortalGeneration.isOtherGenerationRunning(fromWorld, fromShape.innerAreaBox.getCenterVec())) {
            return false;
        }

        // clear the area
        if (generateFrameIfNotFound) {
            for (BlockPos areaPos : fromShape.area) {
                // RECORDED IP DEVIATION — RS PASSTHROUGH (a); IP-core edit 7. IP wipes the whole
                // opening to air before lighting, which would delete the very rail line the user's
                // break rule (§0.4) says must survive and be re-lit over. Survivors are skipped.
                if (com.warwa.seamlessportals.passthrough.ApertureOccupancy
                        .isSurvivor(fromWorld, areaPos)) {
                    continue;
                }
                fromWorld.setBlockAndUpdate(areaPos, Blocks.AIR.defaultBlockState());
            }
        }

        BlockPos toPos = cpg.mapPosition(fromShape.innerAreaBox.getCenter(), fromWorld, toWorld);

        FrameSearching.FrameSearchingFunc<PortalGenInfo> frameMatchingFunc =
            getFrameMatchingFunc(fromWorld, toWorld, fromShape);
        NetherPortalGeneration.startGeneratingPortal(
            fromWorld,
            toWorld,
            fromShape,
            toPos,
            IPGlobal.netherPortalFindingRadius,
            otherSideFramePredicate,
            toShape -> {
                generateNewFrame(fromWorld, fromShape, toWorld, toShape);
            },
            info -> {
                //generate portal entity
                Portal[] result = generatePortalEntitiesAndPlaceholder(info);

                cpg.onPortalsGenerated(result);
            },
            () -> {
                //place frame
                if (!generateFrameIfNotFound) {
                    return null;
                }

                return getNewPortalPlacement(toWorld, toPos, fromWorld, fromShape, triggeringEntity);
            },
            () -> {
                // check portal integrity while loading chunk
                return fromShape.frameAreaWithoutCorner.stream().allMatch(
                    bp -> !fromWorld.isEmptyBlock(bp)
                );
            },
            frameMatchingFunc
        );

        return true;
    }

    public FrameSearching.FrameSearchingFunc<PortalGenInfo> getFrameMatchingFunc(
        ServerLevel fromWorld, ServerLevel toWorld,
        BlockPortalShape fromShape
    ) {
        Predicate<BlockState> areaPredicate = getAreaPredicate();
        Predicate<BlockState> otherSideFramePredicate = getOtherSideFramePredicate();

        FastBlockPortalShape fastFromShape = FastBlockPortalShape.fromBlockPortalShape(fromShape);

        return (blockAccess, x, y, z) -> {
            boolean matches = fastFromShape.matchShape(
                x, y, z,
                (px, py, pz) -> otherSideFramePredicate.test(blockAccess.getBlockState(px, py, pz)),
                (px, py, pz) -> areaPredicate.test(blockAccess.getBlockState(px, py, pz))
            );
            if (matches) {
                boolean matchToSelf = fromWorld == toWorld
                    && x == fastFromShape.basePosX()
                    && y == fastFromShape.basePosY()
                    && z == fastFromShape.basePosZ();

                if (!matchToSelf) {
                    FastBlockPortalShape moved = fastFromShape.withNewBase(x, y, z);

                    return new PortalGenInfo(
                        fromWorld.dimension(),
                        toWorld.dimension(),
                        fastFromShape.toBlockPortalShape(), moved.toBlockPortalShape()
                    );
                }
            }
            return null;
        };
    }

    @Nullable
    public PortalGenInfo getNewPortalPlacement(
        ServerLevel toWorld, BlockPos toPos,
        ServerLevel fromWorld, BlockPortalShape fromShape,
        @Nullable Entity triggeringEntity
    ) {
        boolean canForcePlace = false;
        if (triggeringEntity instanceof ServerPlayer player) {
            if (player.isCreative()) {
                canForcePlace = true;
            }
        }

        IntBox airCubePlacement =
            NetherPortalGeneration.findAirCubePlacement(
                toWorld, toPos,
                fromShape.axis, fromShape.totalAreaBox.getSize(),
                canForcePlace
            );

        if (!canForcePlace && airCubePlacement == null) {
            if (triggeringEntity instanceof ServerPlayer player) {
                player.sendSystemMessage(
                    Component.translatable("imm_ptl.no_place_to_generate_portal")
                );
            }
        }

        if (airCubePlacement == null) {
            return null;
        }

        BlockPortalShape placedShape = fromShape.getShapeWithMovedTotalAreaBox(
            airCubePlacement
        );

        return new PortalGenInfo(
            fromWorld.dimension(),
            toWorld.dimension(),
            fromShape,
            placedShape
        );
    }

    public Portal[] generatePortalEntitiesAndPlaceholder(PortalGenInfo info) {
        info.generatePlaceholderBlocks();
        return info.generateBiWayBiFacedPortal(GeneralBreakablePortal.ENTITY_TYPE);
    }

    public abstract void generateNewFrame(
        ServerLevel fromWorld,
        BlockPortalShape fromShape,
        ServerLevel toWorld,
        BlockPortalShape toShape
    );

    public abstract Predicate<BlockState> getOtherSideFramePredicate();

    public abstract Predicate<BlockState> getThisSideFramePredicate();

    public abstract Predicate<BlockState> getAreaPredicate();

    public boolean testThisSideShape(ServerLevel fromWorld, BlockPortalShape fromShape) {
        return true;
    }
}
