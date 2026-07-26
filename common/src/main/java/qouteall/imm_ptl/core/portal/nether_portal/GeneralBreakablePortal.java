package qouteall.imm_ptl.core.portal.nether_portal;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

public class GeneralBreakablePortal extends BreakablePortalEntity {

    public static final EntityType<GeneralBreakablePortal> ENTITY_TYPE =
        createPortalEntityType(GeneralBreakablePortal::new, portalEntityTypeKey("general_breakable_portal"));

    public GeneralBreakablePortal(EntityType<?> entityType, Level world) {
        super(entityType, world);
    }

    // RECORDED IP DEVIATION — RS PASSTHROUGH (a); revert with
    // -Dseamlessportals.disableAperturePassthrough=true. IP-core edit 4 of
    // migration/REDSTONE_A_SPEC.md §3.1 — the same frame-only rule applied to NetherPortalEntity,
    // repeated here because this sibling subclass carries its own copy of the predicate. Missing it
    // would leave custom/wand-generated breakable portals still self-destructing on any block placed
    // in their opening, i.e. the feature would work on obsidian portals and silently not on others.
    @Override
    protected boolean isPortalIntactOnThisSide() {
        boolean areaIntact = true;
        if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever.DISABLED) {
            areaIntact = blockPortalShape.area.stream()
                .allMatch(blockPos ->
                    level().getBlockState(blockPos).getBlock() == PortalPlaceholderBlock.instance
                );
        }
        boolean frameIntact = blockPortalShape.frameAreaWithoutCorner.stream()
            .allMatch(blockPos -> !level().isEmptyBlock(blockPos));
        return areaIntact && frameIntact;
    }

    @Override
    protected void addSoundAndParticle() {

    }
}
