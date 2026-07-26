package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * REDSTONE/RAIL/MINECART PASSTHROUGH — (a) DIAGNOSE-FIRST PROBE.
 *
 * <p>Answers the live-only questions from {@code migration/REDSTONE_RECON.md} §5 that no amount of
 * static reading settles:
 *
 * <ol>
 *   <li><b>Census</b> ({@link #census}) — one-shot per portal entity: what actually occupies the
 *       opening cells in a live world, and at what coordinates. Emits real {@code /setblock}
 *       targets so the round drives exact cells instead of eyeballed ones. Also reports the
 *       aperture's low corner, which is the bottom opening row.</li>
 *   <li><b>Integrity trigger</b> ({@link #integrityCheck}) — does the placeholder-update notify
 *       path fire same-tick on an aperture change, or does it fall through to the 233-tick sweep?
 *       ({@code BreakablePortalEntity.tick} checks {@code isNotified || gameTime % 233 == id % 233}.)</li>
 *   <li><b>Predicate failure</b> ({@link #intactFailure}) — WHICH cell failed and with what state.
 *       Confirms the kill is attributable to the cell that was touched and nothing else.</li>
 *   <li><b>Teardown</b> ({@link #teardown}) — one-shot when a portal actually breaks, so a portal
 *       vanishing during a round is never mistaken for a render bug.</li>
 * </ol>
 *
 * <p>All four sites are on the SERVER thread ({@code checkPortalIntegrity} validates
 * {@code !isClientSide}), which is exempt from the render-thread log4j rule — but every emitter is
 * still event-rate or one-shot, never per-tick. Self-disarms on any throw so a diagnostic can never
 * take down a tick. Byte-inert without {@code -Dseamlessportals.apertureCensusProbe=true}.
 */
public final class AperturePassthroughProbe {

    private AperturePassthroughProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Cap on cells listed per census dump, so an oversized custom portal cannot flood the log. */
    private static final int MAX_CELLS = 32;

    private static boolean disarmed = false;

    /**
     * One-shot per portal entity. Dumps the opening cells and their current block states.
     *
     * @param portalId    the portal entity id (matches the ids in the other three emitters)
     * @param dimension   the level the portal lives in
     * @param level       the level to read block states from
     * @param area        the opening cells ({@code blockPortalShape.area})
     */
    public static void census(int portalId, String dimension, Level level, Set<BlockPos> area) {
        if (!AperturePassthroughLever.CENSUS_ENABLED || disarmed) {
            return;
        }
        try {
            List<BlockPos> sorted = area.stream()
                .sorted(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ))
                .toList();

            int bottomY = sorted.isEmpty() ? Integer.MIN_VALUE : sorted.get(0).getY();

            StringBuilder sb = new StringBuilder(512);
            int shown = 0;
            for (BlockPos pos : sorted) {
                if (shown >= MAX_CELLS) {
                    sb.append("\n  … ").append(sorted.size() - shown).append(" more cell(s) elided");
                    break;
                }
                shown++;
                BlockState state = level.getBlockState(pos);
                sb.append("\n  ").append(pos.getY() == bottomY ? "BOTTOM-ROW " : "           ")
                    .append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                    .append("  = ").append(state.getBlock().toString())
                    .append("   /setblock ").append(pos.getX()).append(' ')
                    .append(pos.getY()).append(' ').append(pos.getZ()).append(' ');
            }

            LOGGER.info(
                "[RS-APERTURE-CENSUS] portal id={} dim={} cells={} bottomRowY={} — opening contents "
                    + "(BOTTOM-ROW marks the lowest opening row; the obsidian sill is one block below):{}",
                portalId, dimension, sorted.size(), bottomY, sb);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Emitted whenever the integrity predicate runs, naming which of the two triggers fired. */
    public static void integrityCheck(int portalId, long gameTime, boolean viaNotify, boolean intact) {
        if (!AperturePassthroughLever.CENSUS_ENABLED || disarmed) {
            return;
        }
        try {
            LOGGER.info(
                "[RS-INTEGRITY] portal id={} tick={} trigger={} intact={}",
                portalId, gameTime, viaNotify ? "NOTIFY" : "SWEEP", intact);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Emitted with the FIRST opening cell that failed the predicate, and the state that failed it. */
    public static void intactFailure(int portalId, BlockPos pos, BlockState state) {
        if (!AperturePassthroughLever.CENSUS_ENABLED || disarmed) {
            return;
        }
        try {
            LOGGER.warn(
                "[RS-INTEGRITY] portal id={} FAILED opening predicate at {} {} {} — state={} "
                    + "(expected the portal placeholder; this is the cell that will break the portal "
                    + "AND its cross-dimension twin)",
                portalId, pos.getX(), pos.getY(), pos.getZ(), state.getBlock());
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    /** Emitted when a portal actually tears down, so a mid-round vanish is never misattributed. */
    public static void teardown(int portalId, String dimension, long gameTime) {
        if (!AperturePassthroughLever.CENSUS_ENABLED || disarmed) {
            return;
        }
        try {
            LOGGER.warn(
                "[RS-TEARDOWN] portal id={} dim={} tick={} — opening wiped to AIR and entity killed",
                portalId, dimension, gameTime);
        }
        catch (Throwable t) {
            disarm(t);
        }
    }

    private static void disarm(Throwable t) {
        disarmed = true;
        try {
            LOGGER.warn("[RS-APERTURE-PROBE] disarmed after a throw (diagnostic only, tick unaffected)", t);
        }
        catch (Throwable ignored) {
            // never let the probe's own failure escape
        }
    }
}
