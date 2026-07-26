package qouteall.imm_ptl.core.portal.nether_portal;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.q_misc_util.my_util.DQuaternion;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.AperturePassthroughProbe;

public class NetherPortalEntity extends BreakablePortalEntity {
    private static final OverlayInfo overlay_x = new OverlayInfo(
        Blocks.NETHER_PORTAL.defaultBlockState().setValue(
            NetherPortalBlock.AXIS,
            Direction.Axis.Z
        ),
        0.5,
        0,
        null
    );
    private static final OverlayInfo overlay_y_up = new OverlayInfo(
        Blocks.NETHER_PORTAL.defaultBlockState().setValue(
            NetherPortalBlock.AXIS,
            Direction.Axis.X
        ),
        0.5,
        1,
        DQuaternion.rotationByDegrees(new Vec3(1, 0, 0), 90)
    );
    private static final OverlayInfo overlay_y_down = new OverlayInfo(
        Blocks.NETHER_PORTAL.defaultBlockState().setValue(
            NetherPortalBlock.AXIS,
            Direction.Axis.X
        ),
        0.5,
        -1,
        DQuaternion.rotationByDegrees(new Vec3(1, 0, 0), 90)
    );
    private static final OverlayInfo overlay_z = new OverlayInfo(
        Blocks.NETHER_PORTAL.defaultBlockState().setValue(
            NetherPortalBlock.AXIS,
            Direction.Axis.X
        ),
        0.5,
        0,
        null
    );


    public static final EntityType<NetherPortalEntity> ENTITY_TYPE =
        createPortalEntityType(NetherPortalEntity::new, portalEntityTypeKey("nether_portal_new"));

    public NetherPortalEntity(EntityType<?> entityType, Level world) {
        super(entityType, world);
    }

    @Override
    public void tick() {
        super.tick();
    }

    @Override
    protected boolean isPortalIntactOnThisSide() {

        // RECORDED IP DEVIATION — RS PASSTHROUGH (a); revert with
        // -Dseamlessportals.disableAperturePassthrough=true. IP-core edit 3 of
        // migration/REDSTONE_A_SPEC.md §3.1.
        //
        // Blocker 3, and the one confirmed live: IP requires EVERY opening cell to still hold the
        // placeholder, so a single block in the aperture kills this portal AND its cross-dimension
        // twin. Measured (RS-TEARDOWN-TEST, commit 26cd59d): the kill lands on the SAME TICK as the
        // block change via the notify path — the 233-tick sweep is only the backstop — and takes all
        // four entities, both coincident near-side portals and both twins.
        //
        // Under (a) this predicate becomes FRAME-ONLY: the user's decision is that the integrity
        // check survives for IGNITION only, and that after lighting, opening contents never tear the
        // portal down (REDSTONE_RECON.md §0.3). Breaking the obsidian frame still tears down, which
        // is why the frame half below is untouched.
        //
        // IP's original opening scan is retained verbatim under the disable lever, so
        // -Dseamlessportals.disableAperturePassthrough=true restores stock behaviour exactly. The
        // old SUPPRESS_TEARDOWN diagnostic lever is retired here: it existed only to make the seam
        // observable before this edit existed, and the frame-only rule subsumes it.
        boolean openingIntact = true;
        if (AperturePassthroughLever.DISABLED) {
            for (net.minecraft.core.BlockPos blockPos : blockPortalShape.area) {
                net.minecraft.world.level.block.state.BlockState state = level().getBlockState(blockPos);
                if (state.getBlock() != PortalPlaceholderBlock.instance) {
                    AperturePassthroughProbe.intactFailure(getId(), blockPos, state);
                    openingIntact = false;
                    break;
                }
            }
        }

        return openingIntact &&
            blockPortalShape.frameAreaWithoutCorner.stream()
                .allMatch(blockPos ->
                    O_O.isObsidian(level().getBlockState(blockPos))
                );
    }

    @Override
    @Environment(EnvType.CLIENT)
    protected void addSoundAndParticle() {
        if (!IPGlobal.enableNetherPortalEffect) {
            return;
        }

        RandomSource random = level().getRandom();

        for (int i = 0; i < (int) Math.ceil(getWidth() * getHeight() / 20); i++) {
            if (random.nextInt(10) == 0) {
                double px = (random.nextDouble() * 2 - 1) * (getWidth() / 2);
                double py = (random.nextDouble() * 2 - 1) * (getHeight() / 2);

                Vec3 pos = getPointInPlane(px, py);

                double speedMultiplier = 20;

                double vx = speedMultiplier * ((double) random.nextFloat() - 0.5D) * 0.5D;
                double vy = speedMultiplier * ((double) random.nextFloat() - 0.5D) * 0.5D;
                double vz = speedMultiplier * ((double) random.nextFloat() - 0.5D) * 0.5D;

                level().addParticle(
                    ParticleTypes.PORTAL,
                    pos.x, pos.y, pos.z,
                    vx, vy, vz
                );
            }
        }

        if (random.nextInt(800) == 0) {
            level().playLocalSound(
                getX(),
                getY(),
                getZ(),
                SoundEvents.PORTAL_AMBIENT,
                SoundSource.BLOCKS,
                0.5F,
                random.nextFloat() * 0.4F + 0.8F,
                false
            );
        }
    }

    @Override
    public OverlayInfo getActualOverlay() {
        if (IPGlobal.netherPortalOverlay) {
            switch (blockPortalShape.axis) {
                case X -> {return overlay_x;}
                case Y -> {return getNormal().y > 0 ? overlay_y_up : overlay_y_down;}
                case Z -> {return overlay_z;}
            }
        }

        return super.getActualOverlay();
    }
}
