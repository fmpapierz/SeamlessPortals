package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * (e) DEFECT-B INSTRUMENT — the CLIENT side of a ridden cross-dimension crossing
 * ({@code -Dseamlessportals.seamRideProbe=true}, DEFAULT-OFF for the trace; the three summary
 * records at the bottom are ALWAYS ON so a gametest can assert them without arming the log).
 *
 * <p><b>Why this exists at all.</b> The user's 2026-07-28 report is that riding a minecart through
 * a CROSS-DIM portal breaks — forced dismount, or the player spazzing in place. The existing
 * {@link SeamCartProbe} channel and the RS-CART-D leg are entirely SERVER-side, so they report
 * {@code stillRidden=true onRails=true} for the very crossing the user watches fail. A server-side
 * assertion cannot see a client-side dismount. This probe watches the only state that decides what
 * the user sees: the CLIENT's {@code player.getVehicle()} link, every write to it, and every packet
 * that could destroy the client's cart object out from under it.
 *
 * <p><b>What the recon predicts, so the log can confirm or kill it in one round.</b> The crossing
 * player is never sent a {@code ClientboundSetPassengersPacket} for the recreated vehicle (three
 * stacked suppressions — {@code ip_startRidingWithoutTeleportRequest} bypasses the explicit send;
 * {@code addDuringTeleport} runs BEFORE the re-seat so the spawn bundle sees an empty passenger
 * list; and 26.2's {@code ServerEntity.sendChanges} filters the broadcast with a predicate that
 * excludes exactly the player whose membership changed). So the client's ride survives only while
 * nothing destroys its cart object — and {@code handleRemoveEntities} has no guard anywhere in this
 * tree, unlike {@code handleAddEntity}, which IP does guard. If that is right, this log shows a
 * {@code REMOVE-ENTITIES} event naming the vehicle id, immediately followed by
 * {@code REMOVE-VEHICLE} on the local player, and NO {@code SET-PASSENGERS} ever.
 *
 * <p><b>Limiter (mandatory, and stated rather than assumed).</b> The 2026-07-28 live round caught
 * the previous instrument emitting 96% of a 46k-line log because it shipped without a rate limit.
 * This one is armed ONLY by a crossing, only for {@link #WINDOW_TICKS} ticks, only for the local
 * player's own vehicle cluster, and hard-capped at per-channel ceilings. Outside
 * that window every entry point is a single boolean test.
 *
 * <p>CLIENT-thread confined. Note the house hazard: client and integrated server are different
 * threads, so nothing here may be read from server code.
 */
public final class SeamRideProbe {

    private SeamRideProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[RS-RIDE] ";

    /** How long a crossing stays interesting. A ridden crossing settles well inside this. */
    public static final int WINDOW_TICKS = 80;

    /**
     * PER-CHANNEL ceilings, deliberately NOT one shared budget.
     *
     * <p>The first design had a single 400-event budget covering both the per-tick SAMPLE stream
     * and the rare decision-relevant events. On 2026-08-01 a same-dim ride produced 1,351 SAMPLE
     * lines, exhausted the budget, and silently dropped the ONE {@code SET-PASSENGERS} line the
     * whole round existed to capture — the anti-flood limiter ate the evidence. Rare events now
     * have their own reserve that routine noise cannot touch.
     */
    public static final int MAX_SAMPLE_LINES = 240;
    public static final int MAX_EVENT_LINES = 240;
    public static final int MAX_CRITICAL_LINES = 160;

    /** Separate ceiling for the always-on dismount channel, which is not window-bounded. */
    public static final int MAX_DISMOUNT_LINES = 60;

    /**
     * A position write moving the watched vehicle further than this counts as a JUMP and is worth
     * a line with its caller. Ordinary rolling is ~0.4 blocks/tick, the observed defect is 40,000,
     * so anything in between separates them comfortably.
     */
    public static final double JUMP_BLOCKS = 8.0;

    private static int jumpLines;
    private static final int MAX_JUMP_LINES = 40;

    // ---- window state (client thread) ----------------------------------------------------------
    private static boolean armed;
    private static int ticksLeft;
    private static int sampleLines;
    private static int eventLines;
    private static int criticalLines;
    private static int watchedVehicleId = -1;
    private static int tickInWindow;

    /**
     * IDENTITY handle on the watched vehicle, and the reason it exists rather than an id compare.
     *
     * <p>{@code Entity.<init>} calls {@code setPos} -> {@code setPosRaw} BEFORE the entity id is
     * assigned, and {@code Entity.getId()} throws {@code IllegalStateException: Tried to access
     * entity ID before ID assignment} in that window. The first build of the jump hook compared
     * ids there, so the first mob constructed inside an open crossing window (a zombified piglin
     * arriving in the nether, 2026-08-01 11:40:04) threw inside the packet handler and
     * DISCONNECTED THE CLIENT. Identity is safe during construction; ids are not. Weak so a probe
     * left armed can never retain an entity.
     */
    private static java.lang.ref.WeakReference<Entity> watchedVehicleRef;

    // ---- ALWAYS-ON records the gate asserts on (no lever) ---------------------------------------
    private static volatile int crossings;
    private static volatile Boolean lastRemountReturn;
    private static volatile int ejectsAfterRemount;
    private static volatile String lastEjectCause = "NONE";
    private static volatile int setPassengersSeen;
    private static volatile int removeEntitiesHits;
    private static volatile int addEntityHits;
    private static volatile boolean remountAttempted;
    private static boolean lastSampleRiding;
    private static volatile int totalLocalDismounts;
    private static volatile int setPassengersEjectingLocal;
    private static volatile int serverDismounts;
    private static volatile int riddenVehicleRemovals;
    private static volatile String lastRemovalReason = "NONE";
    private static volatile double lastCarryBaseDrift = -1.0;
    private static volatile int carrySamples;

    /** Base drift latched AT the most recent client-side portal carry. -1 if none recorded. */
    public static double lastCarryBaseDrift() {
        return lastCarryBaseDrift;
    }

    /** Coverage: how many client-side carries this arm actually observed. */
    public static int carrySamples() {
        return carrySamples;
    }

    /**
     * Arm the window. Called at the top of a client-side dimension change, BEFORE anything is
     * mutated, so the trace covers the whole crossing including its own preconditions.
     */
    public static void armForCrossing(String fromDim, String toDim, Entity vehicle) {
        // Already inside a window: EXTEND it rather than resetting. A cross-dim crossing arms
        // twice (once at the teleportPlayer entry, once inside changePlayerDimension), and
        // forceTeleportPlayer can re-enter changePlayerDimension on its own; resetting here would
        // zero the very counters the second arm is supposed to be measuring.
        if (armed) {
            ticksLeft = WINDOW_TICKS;
            event("RE-ARM", fromDim + " -> " + toDim
                + " vehicle=" + (vehicle == null ? "null" : String.valueOf(vehicle.getId())));
            return;
        }
        crossings++;
        armed = true;
        ticksLeft = WINDOW_TICKS;
        sampleLines = 0;
        eventLines = 0;
        criticalLines = 0;
        tickInWindow = 0;
        remountAttempted = false;
        lastRemountReturn = null;
        watchedVehicleId = vehicle == null ? -1 : vehicle.getId();
        watchedVehicleRef = vehicle == null ? null : new java.lang.ref.WeakReference<>(vehicle);
        if (AperturePassthroughLever.SEAM_RIDE_PROBE) {
            LOGGER.info(TAG + "★ ARM crossing #{} {} -> {} vehicle={} ({})",
                crossings, fromDim, toDim, watchedVehicleId,
                vehicle == null ? "none" : vehicle.getType().toString());
        }
    }

    /** True while a crossing window is open — the cheap guard every call site uses first. */
    public static boolean windowOpen() {
        return armed;
    }

    public static int watchedVehicleId() {
        return watchedVehicleId;
    }

    /**
     * Identity test for the carried vehicle. MUST be used instead of an id compare by anything
     * that can run during {@code Entity.<init>} — see {@link #watchedVehicleRef}.
     */
    public static boolean isWatchedVehicle(Entity entity) {
        java.lang.ref.WeakReference<Entity> ref = watchedVehicleRef;
        return ref != null && ref.get() == entity;
    }

    /** Record one ordinary event. No-op outside the window or past this channel's ceiling. */
    public static void event(String kind, String detail) {
        if (!armed || eventLines >= MAX_EVENT_LINES) {
            return;
        }
        eventLines++;
        if (AperturePassthroughLever.SEAM_RIDE_PROBE) {
            LOGGER.info(TAG + "t+{} {} :: {}", tickInWindow, kind, detail);
            if (eventLines == MAX_EVENT_LINES) {
                LOGGER.warn(TAG + "event channel ceiling {} reached — ordinary events dropped;"
                    + " the CRITICAL channel is unaffected", MAX_EVENT_LINES);
            }
        }
    }

    /**
     * Record a CRITICAL event — one that can decide the investigation. Never window-gated, and on
     * its own reserve so per-tick noise can never starve it. This exists because the shared budget
     * dropped the one line that mattered; see {@link #MAX_CRITICAL_LINES}.
     */
    public static void critical(String kind, String detail) {
        if (criticalLines >= MAX_CRITICAL_LINES) {
            return;
        }
        criticalLines++;
        if (AperturePassthroughLever.SEAM_RIDE_PROBE) {
            LOGGER.info(TAG + "★ {} (t+{} window={}) :: {}", kind, tickInWindow, armed, detail);
            if (criticalLines == MAX_CRITICAL_LINES) {
                LOGGER.warn(TAG + "CRITICAL channel ceiling {} reached", MAX_CRITICAL_LINES);
            }
        }
    }

    /**
     * The client-side dismount write, hooked at {@code Entity.removeVehicle} HEAD rather than read
     * off the settled state — physics and prediction both paper over an outcome within a tick, and
     * this engagement has paid three times for reading the aftermath instead of the write.
     */
    public static void onLocalPlayerRemoveVehicle(Entity player, Entity vehicle) {
        // DELIBERATELY NOT WINDOW-GATED. The 2026-08-01 round proved why: the failure the user sees
        // does not have to land inside a crossing window at all — it appeared only after the view
        // distance went 2 -> 32, i.e. plausibly some ticks later, driven by entity-tracking churn
        // rather than by the crossing itself. A dismount of the LOCAL PLAYER is rare and cheap to
        // record, so it is always recorded; only the emission is rate-capped. The CAUSE is the
        // whole point — it names the caller that ejected the rider (ClientLevel.addEntity,
        // ClientPacketListener.handleRemoveEntities, a genuine user dismount, ...), which is
        // exactly the discriminator this defect has been missing.
        String cause = shortCause();
        totalLocalDismounts++;
        if (remountAttempted) {
            ejectsAfterRemount++;
        }
        lastEjectCause = cause;
        if (AperturePassthroughLever.SEAM_RIDE_PROBE
            && totalLocalDismounts <= MAX_DISMOUNT_LINES) {
            LOGGER.info(TAG + "★ DISMOUNT #{} (windowOpen={} t+{}) vehicle={} afterRemount={}"
                    + " cause={}",
                totalLocalDismounts, armed, tickInWindow,
                vehicle == null ? "null" : String.valueOf(vehicle.getId()),
                remountAttempted, cause);
            if (totalLocalDismounts == MAX_DISMOUNT_LINES) {
                LOGGER.warn(TAG + "dismount line ceiling {} reached — counters keep running",
                    MAX_DISMOUNT_LINES);
            }
        }
    }

    /**
     * A position write on the carried vehicle. Only jumps are reported — and the CALLER is the
     * point: it distinguishes "a stale source-dimension packet was applied" from "the client's own
     * physics moved it" from "the crossing itself placed it", which the per-tick sampler cannot.
     */
    public static void onWatchedVehicleMoved(Entity vehicle, double x, double y, double z) {
        double dx = x - vehicle.getX();
        double dy = y - vehicle.getY();
        double dz = z - vehicle.getZ();
        if (dx * dx + dy * dy + dz * dz < JUMP_BLOCKS * JUMP_BLOCKS) {
            return;
        }
        if (!AperturePassthroughLever.SEAM_RIDE_PROBE || jumpLines >= MAX_JUMP_LINES) {
            return;
        }
        jumpLines++;
        LOGGER.info(TAG + "★ VEHICLE JUMP t+{} id={} from=({}, {}, {}) to=({}, {}, {})"
                + " dist={} cause={}",
            tickInWindow, vehicle.getId(),
            vehicle.getX(), vehicle.getY(), vehicle.getZ(), x, y, z,
            String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(dx * dx + dy * dy + dz * dz)),
            shortCause());
    }

    /**
     * A dismount of the SERVER-side player. The client trace alone cannot tell whether an ejecting
     * {@code SetPassengers} reflects a genuine server-side dismount or a stale packet describing a
     * ride the server still believes in — and those are different defects with different fixes.
     * Always recorded, rate-capped, with the calling stack.
     */
    public static void onServerPlayerRemoveVehicle(Entity serverPlayer, Entity vehicle) {
        serverDismounts++;
        if (AperturePassthroughLever.SEAM_RIDE_PROBE
            && serverDismounts <= MAX_DISMOUNT_LINES) {
            LOGGER.info(TAG + "★ SERVER-DISMOUNT #{} (windowOpen={}) player={} vehicle={} cause={}",
                serverDismounts, armed, serverPlayer.getId(),
                vehicle == null ? "null" : String.valueOf(vehicle.getId()), shortCause());
        }
    }

    /**
     * A RIDDEN vehicle being removed — the event that ejects the rider, with the reason that says
     * which fix this needs. Critical channel: rare, decisive, never window-gated.
     */
    public static void onRiddenVehicleRemoved(
        Entity entity, String reason, boolean clientSide, String which
    ) {
        riddenVehicleRemovals++;
        lastRemovalReason = reason;
        critical("RIDE-BROKEN-BY-REMOVAL", "side=" + (clientSide ? "CLIENT" : "SERVER")
            + " " + which
            + " entity=" + entity.getId()
            + " type=" + entity.getType()
            + " pos=" + entity.position()
            + " reason=" + reason
            + " vehicle=" + (entity.getVehicle() == null
                ? "-" : String.valueOf(entity.getVehicle().getId()))
            + " passengers=" + entity.getPassengers().size()
            + " cause=" + shortCause());
    }

    /**
     * LATCH the carried vehicle's relative-move base drift AT THE CARRY — always on, client only.
     *
     * <p>The gate's first version read {@code getPositionCodec().getBase()} 30-40 ticks after the
     * crossing and called it "the mechanism the fix installs". It is not: vanilla rebases that base
     * on EVERY position packet ({@code ClientPacketListener.handleMoveEntity} and
     * {@code handleEntityPositionSync} both call {@code VecDeltaCodec.setBase} unconditionally, and
     * a large carry forces an absolute position-sync within ~3 ticks because the delta overflows
     * the packet's short range). So the post-hoc read measured ~0 whether the fix was on or off —
     * vacuous in the default arm, and a FALSE RED in the inversion arm, which is exactly what the
     * 2026-08-01 matrix produced: {@code baseDrift=0.103} alongside {@code riderGap=13863.613},
     * i.e. the defect reproducing perfectly while the gate announced "the lever is dead code".
     *
     * <p>Latching here fixes it at the source. This is the same lesson this engagement has now paid
     * for four times — <em>hook the last thing the engine mutates, not what it looks like
     * afterwards</em> — and the gate that was supposed to enforce it broke it.
     */
    public static void recordCarryBaseDrift(Entity vehicle, double x, double y, double z) {
        if (!vehicle.level().isClientSide()) {
            return;
        }
        net.minecraft.world.phys.Vec3 base = vehicle.getPositionCodec().getBase();
        lastCarryBaseDrift = Math.sqrt(
            (base.x - x) * (base.x - x) + (base.y - y) * (base.y - y) + (base.z - z) * (base.z - z));
        carrySamples++;
        critical("CARRY-BASE-DRIFT", "vehicle=" + vehicle.getId()
            + " carriedTo=(" + x + ", " + y + ", " + z + ")"
            + " codecBase=" + base
            + " drift=" + String.format(java.util.Locale.ROOT, "%.3f", lastCarryBaseDrift));
    }

    /** The client-side re-mount write, with the boolean return IP discards at the call site. */
    public static void onRemount(Entity vehicle, boolean result) {
        remountAttempted = true;
        lastRemountReturn = result;
        event("START-RIDING", "vehicle=" + (vehicle == null ? "null" : vehicle.getId())
            + " returned=" + result);
    }

    /**
     * A {@code ClientboundSetPassengersPacket} arriving on the client.
     *
     * <p>The first version of this took a boolean the CALLER computed as
     * {@code vehicleId == watchedVehicleId()} and labelled it {@code localPlayerAmong} — which is
     * not that question at all, and would have made every reading of this channel meaningless.
     * It now takes the packet's actual passenger list, because the decisive fact is whether the
     * local player is IN it: vanilla's handler ejects every passenger and then re-seats only those
     * named, so a list that omits the rider dismounts them permanently.
     */
    public static void onSetPassengers(
        int vehicleId, int[] passengerIds, int localPlayerId, int localPlayerVehicleId
    ) {
        // ALWAYS-ON when the packet concerns the vehicle the local player is CURRENTLY riding —
        // not merely when a crossing window happens to be open.
        //
        // Third time this engagement that a window gate has hidden the very event under
        // investigation, and the pattern is now explicit enough to name: the failure does not have
        // to happen inside the window I opened for it. The 2026-08-01 same-dim break ejected the
        // rider at windowOpen=false, so the ejecting packet went unrecorded while the dismount it
        // caused was recorded — a half-blind trace that shows the effect and hides the cause.
        boolean isWatchedVehicle = vehicleId == watchedVehicleId;
        boolean concernsMyRide = localPlayerVehicleId != -1 && vehicleId == localPlayerVehicleId;
        if (!armed && !concernsMyRide) {
            return;
        }
        setPassengersSeen++;
        boolean localAmong = false;
        StringBuilder ids = new StringBuilder();
        if (passengerIds != null) {
            for (int i = 0; i < passengerIds.length; i++) {
                if (i > 0) {
                    ids.append(',');
                }
                ids.append(passengerIds[i]);
                if (passengerIds[i] == localPlayerId) {
                    localAmong = true;
                }
            }
        }
        boolean ejectsRider = concernsMyRide && !localAmong;
        if (ejectsRider) {
            setPassengersEjectingLocal++;
        }
        critical("SET-PASSENGERS", "vehicle=" + vehicleId
            + " isWatchedVehicle=" + isWatchedVehicle
            + " concernsMyRide=" + concernsMyRide
            + " passengers=[" + ids + "] localPlayer=" + localPlayerId
            + " localAmong=" + localAmong
            + (ejectsRider ? "  ★★ THIS EJECTS THE RIDER" : ""));
    }

    public static void onRemoveEntities(int entityId, boolean isWatchedVehicle, boolean hadPassengers) {
        if (!armed) {
            return;
        }
        if (isWatchedVehicle) {
            removeEntitiesHits++;
        }
        event("REMOVE-ENTITIES", "id=" + entityId + " isWatchedVehicle=" + isWatchedVehicle
            + " hadPassengers=" + hadPassengers);
    }

    public static void onAddEntity(int entityId, boolean isWatchedVehicle, boolean guardCancelled) {
        if (!armed) {
            return;
        }
        if (isWatchedVehicle) {
            addEntityHits++;
        }
        event("ADD-ENTITY", "id=" + entityId + " isWatchedVehicle=" + isWatchedVehicle
            + " ipGuardCancelled=" + guardCancelled);
    }

    /**
     * Per-tick sample while the window is open. Registered on the client tick event; closes the
     * window and emits the verdict line when it expires.
     */
    public static void onEndClientTick(
        Entity localPlayer, Entity vehicle, String dim, boolean vehiclePresentInLevel
    ) {
        if (!armed) {
            return;
        }
        tickInWindow++;
        // Emit only when the ride STATE changes (mounted <-> dismounted, or the vehicle moves
        // appreciably), plus a periodic heartbeat. The previous unconditional per-tick emission
        // produced 1,351 lines in one ride and was what exhausted the shared budget.
        boolean ridingNow = vehicle != null;
        boolean changed = ridingNow != lastSampleRiding
            || tickInWindow % 20 == 0
            || (ridingNow && localPlayer != null
                && Math.abs(vehicle.getY() - localPlayer.getY() - 0.4125) > 0.05);
        lastSampleRiding = ridingNow;
        if (AperturePassthroughLever.SEAM_RIDE_PROBE && changed && sampleLines < MAX_SAMPLE_LINES) {
            sampleLines++;
            LOGGER.info(TAG + "t+{} SAMPLE dim={} playerPos={} vehicle={} vehiclePos={}"
                    + " vehicleInLevel={} delta={}",
                tickInWindow, dim,
                localPlayer == null ? "null" : localPlayer.position(),
                vehicle == null ? "NULL(dismounted)" : String.valueOf(vehicle.getId()),
                vehicle == null ? "-" : vehicle.position().toString(),
                vehiclePresentInLevel,
                vehicle == null || localPlayer == null
                    ? "-" : vehicle.position().subtract(localPlayer.position()).toString());
        }
        ticksLeft--;
        if (ticksLeft <= 0) {
            armed = false;
            LOGGER.info(TAG + "★ VERDICT crossing #{}: remountAttempted={} remountReturned={}"
                    + " stillRidingAtWindowEnd={} ejectsAfterRemount={} lastEjectCause={}"
                    + " setPassengersSeen={} removeEntitiesOnVehicle={} addEntityOnVehicle={}",
                crossings, remountAttempted, lastRemountReturn,
                vehicle != null, ejectsAfterRemount, lastEjectCause,
                setPassengersSeen, removeEntitiesHits, addEntityHits);
        }
    }

    /**
     * A compact caller attribution for a dismount. Only computed inside an open window and only on
     * an actual dismount of the local player, so the stack walk is rare by construction.
     */
    private static String shortCause() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            String mn = e.getMethodName();
            // Skip the probe and the plumbing that is the same on EVERY hit. The first build
            // wasted its whole budget printing
            // "Entity.handler$...traceLocalPlayerDismount < Entity.removeVehicle <
            //  Player.removeVehicle < LocalPlayer.removeVehicle" — four frames that identify the
            // hook, not the caller. The caller is the entire point of this string.
            if (cn.startsWith("java.lang.Thread")
                || cn.equals(SeamRideProbe.class.getName())
                || mn.startsWith("handler$")
                || mn.startsWith("seamlessportals$")
                || cn.endsWith(".Entity") && (mn.equals("removeVehicle") || mn.equals("setPosRaw"))
                || cn.endsWith(".Player") && mn.equals("removeVehicle")
                || cn.endsWith(".LocalPlayer") && mn.equals("removeVehicle")
                || cn.endsWith(".Entity") && mn.equals("setPos")
                || cn.endsWith(".Entity") && mn.equals("stopRiding")) {
                continue;
            }
            if (shown > 0) {
                sb.append('<');
            }
            sb.append(cn.substring(cn.lastIndexOf('.') + 1)).append('.').append(mn)
                .append(':').append(e.getLineNumber());
            shown++;
            // 14, not 6. At 6 the handleMoveEntity trace stopped exactly one frame short of the
            // decisive fact: a REDIRECTED packet carries
            // `PacketRedirectionClient.lambda$handleRedirectedPacket$0` just below the packet's own
            // handle(), and an unredirected one carries the raw PacketProcessor. Those are
            // different defects with different fixes, and the cutoff hid which one this is.
            if (shown >= 14) {
                break;
            }
        }
        return sb.length() == 0 ? "(only-plumbing-frames)" : sb.toString();
    }

    // ---- gametest surface (ALWAYS ON — the gate must not need the log lever) --------------------

    public static int crossings() {
        return crossings;
    }

    public static Boolean lastRemountReturn() {
        return lastRemountReturn;
    }

    public static int ejectsAfterRemount() {
        return ejectsAfterRemount;
    }

    public static String lastEjectCause() {
        return lastEjectCause;
    }

    public static int setPassengersSeen() {
        return setPassengersSeen;
    }

    public static int removeEntitiesHits() {
        return removeEntitiesHits;
    }

    public static int addEntityHits() {
        return addEntityHits;
    }

    public static int totalLocalDismounts() {
        return totalLocalDismounts;
    }

    public static String counters() {
        return "crossings=" + crossings + " remountReturn=" + lastRemountReturn
            + " localDismounts=" + totalLocalDismounts
            + " ejectsAfterRemount=" + ejectsAfterRemount + " lastEjectCause=" + lastEjectCause
            + " setPassengers=" + setPassengersSeen + " removeEntities=" + removeEntitiesHits
            + " addEntity=" + addEntityHits;
    }

    /** Gametest leg teardown — inherited counters make a coverage assertion meaningless. */
    public static void reset() {
        armed = false;
        ticksLeft = 0;
        sampleLines = 0;
        eventLines = 0;
        criticalLines = 0;
        tickInWindow = 0;
        watchedVehicleId = -1;
        watchedVehicleRef = null;
        crossings = 0;
        lastRemountReturn = null;
        ejectsAfterRemount = 0;
        lastEjectCause = "NONE";
        setPassengersSeen = 0;
        removeEntitiesHits = 0;
        addEntityHits = 0;
        remountAttempted = false;
        totalLocalDismounts = 0;
        setPassengersEjectingLocal = 0;
        serverDismounts = 0;
        jumpLines = 0;
        lastCarryBaseDrift = -1.0;
        carrySamples = 0;
    }

    public static int setPassengersEjectingLocal() {
        return setPassengersEjectingLocal;
    }

    public static int serverDismounts() {
        return serverDismounts;
    }
}
