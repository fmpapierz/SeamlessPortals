package qouteall.imm_ptl.core.portal;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

public class PortalPlaceholderBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
    public static final VoxelShape X_AABB = Block.box(
        6.0D,
        0.0D,
        0.0D,
        10.0D,
        16.0D,
        16.0D
    );
    public static final VoxelShape Y_AABB = Block.box(
        0.0D,
        6.0D,
        0.0D,
        16.0D,
        10.0D,
        16.0D
    );
    public static final VoxelShape Z_AABB = Block.box(
        0.0D,
        0.0D,
        6.0D,
        16.0D,
        16.0D,
        10.0D
    );
    
    public static final PortalPlaceholderBlock instance = new PortalPlaceholderBlock(
        BlockBehaviour.Properties.of()
            .noCollision()
            .sound(SoundType.GLASS)
            .strength(1.0f, 0)
            .noOcclusion()
            .noLootTable()
            .lightLevel((s) -> 15)
            .setId(ResourceKey.create(
                Registries.BLOCK,
                Identifier.fromNamespaceAndPath("immersive_portals", "nether_portal_block")
            ))
    );
    
    public PortalPlaceholderBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(
            (BlockState) ((BlockState) this.getStateDefinition().any()).setValue(
                AXIS, Direction.Axis.X
            )
        );
    }
    
    @Override
    public VoxelShape getShape(
        BlockState state, BlockGetter world, BlockPos blockPos, CollisionContext shapeContext
    ) {
        switch ((Direction.Axis) state.getValue(AXIS)) {
            case Z:
                return Z_AABB;
            case Y:
                return Y_AABB;
            case X:
            default:
                return X_AABB;
        }
    }
    
    // RECORDED IP DEVIATION — RS PASSTHROUGH (a); revert with
    // -Dseamlessportals.disableAperturePassthrough=true. IP-core edits 1 and 2 of
    // migration/REDSTONE_A_SPEC.md §3.1.
    //
    // Blocker 1 of the three that make the aperture unbuildable today: this block has no
    // .replaceable() in its Properties (:60-72), so BlockPlaceContext.canPlace() is false and
    // BlockItem.place fails outright — you cannot put a rail, or anything else, into a portal
    // opening. Making it hand-replaceable is what turns the aperture into ordinary building space.

    /**
     * ITEM placement may replace the placeholder (the whole point of sub-feature (a)).
     *
     * <p>Deliberately NOT delegating to {@code super}: {@code BlockBehaviour}'s default consults
     * {@code state.canBeReplaced()}, which is driven by the block's own properties and would stay
     * false. The lever check is the entire body so the disable path restores stock IP exactly.
     */
    @Override
    protected boolean canBeReplaced(BlockState state, net.minecraft.world.item.context.BlockPlaceContext context) {
        return !com.warwa.seamlessportals.passthrough.AperturePassthroughLever.DISABLED;
    }

    /**
     * FLUIDS may NOT replace the placeholder, even with (a) active.
     *
     * <p>Adversarial-verifier finding (lens B, C4/F5): {@code canBeReplaced(BlockState, Fluid)} is a
     * SEPARATE overload with its own default ({@code state.canBeReplaced() || !state.isSolid()} —
     * REF BlockBehaviour.java:254-255). The placeholder is {@code noCollision}, so
     * {@code !isSolid()} is already true and water or lava adjacent to a portal would flood the
     * aperture and destroy the portal — with no player action at all. Overriding only the
     * BlockPlaceContext form would have left that wide open. Under the disable lever this defers to
     * stock behaviour so the deviation is fully reversible.
     */
    @Override
    protected boolean canBeReplaced(BlockState state, net.minecraft.world.level.material.Fluid fluid) {
        return com.warwa.seamlessportals.passthrough.AperturePassthroughLever.DISABLED
            && super.canBeReplaced(state, fluid);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }
    
    @Override
    public BlockState updateShape(
        BlockState blockState, LevelReader levelReader, ScheduledTickAccess scheduledTickAccess, BlockPos blockPos, Direction direction, BlockPos blockPos2, BlockState blockState2, RandomSource randomSource
    ) {
        if (!levelReader.isClientSide()) {
            if (levelReader instanceof Level world) {
                Direction.Axis axis = blockState.getValue(AXIS);
                if (direction.getAxis() != axis) {
                    McHelper.findEntitiesRough(
                        BreakablePortalEntity.class,
                        world,
                        Vec3.atLowerCornerOf(blockPos),
                        2,
                        e -> true
                    ).forEach(
                        portal -> {
                            ((BreakablePortalEntity) portal).notifyPlaceholderUpdate();
                        }
                    );
                }
            }
        }
        
        return super.updateShape(
            blockState, levelReader, scheduledTickAccess, blockPos, direction, blockPos2, blockState2, randomSource
        );
    }
    
    public static boolean isHitOnPlaceholder(HitResult hitResult, Level world) {
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            if (hitResult instanceof BlockHitResult blockHitResult) {
                Block hittingBlock = world.getBlockState(blockHitResult.getBlockPos()).getBlock();
                return hittingBlock == PortalPlaceholderBlock.instance;
            }
        }
        return false;
    }
    
    //---------Similar to BarrierBlock
    @Override
    public boolean propagatesSkylightDown(
        BlockState blockState
    ) {
        return true;
    }
    
    @Override
    public @NotNull RenderShape getRenderShape(BlockState blockState) {
        return RenderShape.INVISIBLE;
    }
    
    @Environment(EnvType.CLIENT)
    @Override
    public float getShadeBrightness(
        BlockState blockState_1,
        BlockGetter blockView_1,
        BlockPos blockPos_1
    ) {
        return 1.0F;
    }
}
