package com.warwa.seamlessportals.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;

import java.util.function.Predicate;

/**
 * APERTURE OCCUPANCY POLICY — what is allowed to be in a portal opening, and when.
 *
 * <p>Two different questions live here and they have deliberately different answers:
 *
 * <ul>
 *   <li><b>After ignition</b> the aperture is ORDINARY BUILDING SPACE — any block, any cell, any
 *       height (user decision, {@code REDSTONE_RECON.md} §0.2). Nothing in this class restricts that;
 *       the frame-only integrity predicate from step 3 already permits it.</li>
 *   <li><b>At ignition</b> the check survives (§0.3): you must not be able to light a portal through
 *       a wall. But it must tolerate a track that is already there, because breaking a frame leaves
 *       the player's blocks behind (§0.4) and the frame has to be re-lightable over them.</li>
 * </ul>
 *
 * <p><b>The ignition rule</b> (user-confirmed 2026-07-25, {@code REDSTONE_A_SPEC.md} §4.1):
 * passthrough blocks — rails and redstone — are always admitted; a SUPPORT cube is admitted only when
 * the cell directly above it holds a passthrough block, which is exactly the "build a line of blocks,
 * lay rail on it" case and nothing else; everything else is refused; and the opening must still be at
 * least half air, so a mostly-filled frame is not a portal.
 *
 * <p><b>Why the position-aware check is separate from the predicate.</b> IP's
 * {@code getAreaPredicate()} returns {@code Predicate<BlockState>} with no position
 * ({@code NetherPortalLikeForm.java:205}), so "a support cube, but only under a rail" is not
 * expressible in it. Making it position-aware would mean changing {@code findFrameShape},
 * {@code findAreaBreadthFirst} and {@code FastBlockPortalShape.matchShape} — a much larger IP
 * deviation. Instead the predicate is widened to admit the union, and
 * {@link #ignitionAreaAcceptable} applies the real rule afterwards, where coordinates exist.
 */
public final class ApertureOccupancy {

    private ApertureOccupancy() {}

    /** Rails and redstone — the blocks the passthrough feature exists to carry. */
    public static final TagKey<net.minecraft.world.level.block.Block> PASSTHROUGH =
        TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("seamlessportals", "aperture_passthrough"));

    /**
     * Solid blocks admitted at ignition ONLY as a floor directly beneath a passthrough block.
     *
     * <p><b>MUST NEVER CONTAIN A FRAME MATERIAL.</b> This tag feeds {@link #areaPredicate()}, which
     * IP's flood fill uses to decide what counts as INSIDE the opening. Listing obsidian here made
     * the fill unable to tell frame from opening, and leg 6a failed with
     * "onFireLitOnObsidian returned false" on a perfectly valid empty frame — the shape search no
     * longer had a boundary. Adding any block that people build portal frames from will reproduce it.
     */
    public static final TagKey<net.minecraft.world.level.block.Block> SUPPORT =
        TagKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath("seamlessportals", "aperture_support"));

    /** True for a block that may sit in an aperture and be carried across the seam. */
    public static boolean isPassthrough(BlockState state) {
        return state.is(PASSTHROUGH);
    }

    /** True for a block that survives the generation clear/fill loops rather than being wiped. */
    public static boolean isSurvivor(BlockGetter world, BlockPos pos) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SURVIVOR_SKIP) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        return state.is(PASSTHROUGH) || state.is(SUPPORT);
    }

    /**
     * The widened block-state predicate handed to IP's frame search. Admits air plus the union of
     * passthrough and support, so a frame containing an existing track is still FOUND; the real
     * position-aware rule is applied afterwards by {@link #ignitionAreaAcceptable}.
     *
     * <p>Under the disable lever this is IP's original {@code isAir}, exactly.
     */
    public static Predicate<BlockState> areaPredicate() {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_IGNITION_WHITELIST) {
            return BlockState::isAir;
        }
        // DELIBERATELY EXCLUDES PortalPlaceholderBlock. Including it was a real defect, caught by
        // the seam gate: placeholders in an opening mean the portal is ALIVE (teardown wipes the
        // opening to air), so their presence is exactly the signal that a frame must NOT be matched.
        // Admitting them made a live, lit portal's frame look like an empty matchable one, and leg
        // 6b's destination search linked to leg 6a's LIVE portal — two portal pairs then claimed the
        // same aperture cell, with different destinations.
        return state -> state.isAir()
            || state.is(PASSTHROUGH)
            || state.is(SUPPORT);
    }

    /**
     * The position-aware ignition rule. Applied after the frame shape is found, where coordinates
     * exist and "a support cube under a rail" becomes expressible.
     *
     * @return false to refuse ignition
     */
    public static boolean ignitionAreaAcceptable(BlockGetter world, BlockPortalShape shape) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_IGNITION_WHITELIST) {
            return shape.area.stream().allMatch(p -> world.getBlockState(p).isAir());
        }

        int airOrPlaceholder = 0;
        for (BlockPos pos : shape.area) {
            BlockState state = world.getBlockState(pos);

            if (state.isAir() || state.getBlock() == PortalPlaceholderBlock.instance) {
                airOrPlaceholder++;
                continue;
            }
            if (state.is(PASSTHROUGH)) {
                continue;   // rails and redstone are always admitted
            }
            if (state.is(SUPPORT)) {
                // Admitted ONLY as a floor: the cell directly above must be inside this same opening
                // and hold a passthrough block. That is the "build a line of blocks, lay rail on it"
                // case and nothing else — a stray stone cube, or a stack of them forming a wall, is
                // refused because nothing sits on it.
                BlockPos above = pos.above();
                if (shape.area.contains(above) && world.getBlockState(above).is(PASSTHROUGH)) {
                    continue;
                }
                return false;
            }
            return false;   // anything else in the opening refuses ignition
        }

        // A frame that is mostly filled is not a portal, whatever it is filled with.
        return airOrPlaceholder * 2 >= shape.area.size();
    }
}
