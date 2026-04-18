package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.mixin.EntityLevelAccessorMixin;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import com.warwa.seamlessportals.render.PortalContextSwitch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * Client-side half of the IP-style client-initiated seamless teleport.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #performCrossing(PortalLink)} — called synchronously from
 *       {@code LocalPlayerMixin.tick} HEAD the instant the client detects
 *       its own crossing of a portal plane. Does the visual swap (promote
 *       cached renderer, re-level player, demote outgoing) and sends
 *       {@link ModPayloads.ClientPortalCrossingPayload} to the server.</li>
 *   <li>{@link #handleServerReconcile} — called when
 *       {@link ModPayloads.ClientboundSeamlessMovePayload} arrives from the
 *       server. Hot path: confirms our client-first swap was correct.
 *       Fallback path: if client never detected the crossing (no cached
 *       renderer for the dest dim yet, or portal link not synced), the
 *       visual swap is deferred to the packet handler and runs now.</li>
 * </ul>
 *
 * <p>Equivalent to what {@code HandleRespawnMixin.seamlessportals$redirectSetLevel}
 * does inside {@code handleRespawn}, but independent of the respawn-packet
 * flow. The old mixin remains in place for non-seamless cross-dim respawns
 * (death, command teleport, etc.) but is no longer exercised for portal
 * crossings once both ends of this pipeline are in place.
 */
public final class SeamlessClientTeleport {

    private SeamlessClientTeleport() {}

    /**
     * Set to {@code true} the frame we perform a client-first visual swap.
     * Cleared when the server reconciliation packet arrives or the player
     * steps out of any portal bounding box. Guards against re-entering the
     * crossing detector on the next client tick while still inside the
     * destination portal.
     */
    public static volatile boolean justTeleportedClient = false;

    /**
     * Record of the last client-first crossing's destination, used by
     * {@link #handleServerReconcile} to detect whether the arriving packet
     * matches what we already did.
     */
    private static volatile ResourceKey<Level> lastClientSwapDim = null;

    /**
     * Client-first path. We already know the link (detected locally); perform
     * the visual swap immediately. The server-side teleport will follow when
     * the server receives {@link ModPayloads.ClientPortalCrossingPayload}; its
     * reconciliation packet will confirm position.
     */
    public static boolean performCrossing(PortalLink link) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;

        Vec3 srcPos = player.position();
        Vec3 destPos = link.transformTeleportPosition(srcPos);
        Vec3 destVel = link.transformVelocity(player.getDeltaMovement());
        float destYaw = link.transformYaw(player.getYRot());
        float destPitch = player.getXRot();

        boolean swapped = doVisualSwap(link.getDestination().getDimension(),
            destPos, destVel, destYaw, destPitch);
        if (!swapped) return false;

        // Tell the server to perform its authoritative teleport. Using the
        // source portal id so the server can validate + look up the same
        // PortalLink on its side.
        PlatformHelper.getInstance().sendToServer(new ModPayloads.ClientPortalCrossingPayload(
            link.getSource().getPortalId().toString()));

        justTeleportedClient = true;
        lastClientSwapDim = link.getDestination().getDimension();

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS CLIENT-CROSSING] client-first swap → {} at ({},{},{})",
            link.getDestination().getDimension().identifier(),
            String.format("%.2f", destPos.x),
            String.format("%.2f", destPos.y),
            String.format("%.2f", destPos.z));
        return true;
    }

    /**
     * Fallback / reconcile path. Called when
     * {@link ModPayloads.ClientboundSeamlessMovePayload} arrives.
     *
     * <p>If we already swapped client-first to this dim: reconcile position
     * against server's authoritative value (position delta should be very
     * small — same transform on both sides, same pre-cross position within
     * one tick). We treat tiny delta as no-op.
     *
     * <p>If we did NOT already swap (client missed detection — portal link
     * wasn't synced, player moved through in a weird way, etc.): do the
     * visual swap now. This reintroduces the old round-trip flash for that
     * one crossing but is still correct.
     */
    public static void handleServerReconcile(ModPayloads.ClientboundSeamlessMovePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        ResourceKey<Level> payloadDim = parseDim(payload.destDimension());
        if (payloadDim == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS RECONCILE] Unknown dimension in payload: {}", payload.destDimension());
            return;
        }

        ResourceKey<Level> currentDim = mc.level.dimension();
        Vec3 destPos = new Vec3(payload.x(), payload.y(), payload.z());
        Vec3 destVel = new Vec3(payload.vx(), payload.vy(), payload.vz());

        if (currentDim.equals(payloadDim)) {
            // Hot path: we already swapped (either client-first via
            // performCrossing, or server-first via vanilla handleRespawn).
            // Reconcile position if it drifted.
            double dx = player.getX() - destPos.x;
            double dy = player.getY() - destPos.y;
            double dz = player.getZ() - destPos.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > 0.05) {
                player.setPos(destPos.x, destPos.y, destPos.z);
                player.xo = destPos.x; player.yo = destPos.y; player.zo = destPos.z;
                player.xOld = destPos.x; player.yOld = destPos.y; player.zOld = destPos.z;
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS RECONCILE] Pos drift corrected: d={} blocks", Math.sqrt(d2));
            } else {
                SeamlessPortalsConstants.LOGGER.debug(
                    "[SEAMLESS RECONCILE] Pos match (d²={})", d2);
            }
            // Block the client-first detector from re-firing while the
            // player is still inside the dest portal's bounding box. This
            // matters for the server-first path (vanilla handleRespawn ran,
            // client-first never did, so performCrossing never set the flag).
            justTeleportedClient = true;
            lastClientSwapDim = payloadDim;
            return;
        }

        // Fallback: server-first detection. Client never did visual swap.
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS RECONCILE] Fallback — deferred visual swap to {} at ({},{},{})",
            payloadDim.identifier(),
            String.format("%.2f", destPos.x),
            String.format("%.2f", destPos.y),
            String.format("%.2f", destPos.z));

        // Look up the link by portal id so we can use the same swap logic.
        // If the link isn't available client-side (e.g. portal data never
        // synced to this client for this dim), we still need to do the swap.
        // The portalId gives us the source portal; we can look up the link
        // directly.
        UUID portalId;
        try {
            portalId = UUID.fromString(payload.portalId());
        } catch (IllegalArgumentException e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS RECONCILE] Invalid portalId in payload: {}", payload.portalId());
            return;
        }
        Optional<PortalLink> linkOpt = PortalManager.getClientInstance().getLinkForPortal(portalId);
        // Link may be unavailable on the client — still do the swap using
        // the payload's destDim. Demote/promote only needs the dim key.
        doVisualSwap(payloadDim, destPos, destVel, payload.yaw(), payload.pitch());
        justTeleportedClient = true;
        lastClientSwapDim = payloadDim;
    }

    /**
     * Core swap: promote cached renderer+level for the dest dim, re-level
     * the local player, move the player to dest position, demote the
     * outgoing primary.
     *
     * @return {@code true} if the swap happened; {@code false} if there is
     *     no cached renderer for the dest dim (nothing to promote).
     */
    private static boolean doVisualSwap(ResourceKey<Level> destDim,
            Vec3 destPos, Vec3 destVel, float destYaw, float destPitch) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;

        if (mc.level.dimension().equals(destDim)) {
            // Already on dest dim — nothing to do visually. Just snap player.
            player.setPos(destPos.x, destPos.y, destPos.z);
            player.setDeltaMovement(destVel);
            player.setYRot(destYaw);
            player.setXRot(destPitch);
            return true;
        }

        LevelRenderState sharedState = mc.gameRenderer.getGameRenderState().levelRenderState;
        PortalWorldManager.Promotion promotion = PortalWorldManager.promoteToMain(destDim, sharedState);
        if (promotion == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS CLIENT-CROSSING] No cached renderer for {} — cannot perform seamless swap",
                destDim.identifier());
            return false;
        }

        LevelRenderer oldRenderer = mc.levelRenderer;
        ClientLevel oldLevel = mc.level;
        ResourceKey<Level> oldDim = oldLevel.dimension();

        // 1. Install promoted renderer + level as new primary.
        ((MinecraftAccessorMixin) mc).seamlessportals$setLevelRenderer(promotion.renderer());
        mc.level = promotion.level();

        // 2. Seed lastCameraSection* on promoted renderer so the first
        // cullTerrain doesn't wipe ViewArea meshes (matches HandleRespawnMixin
        // line ~218 rationale: viewarea_reposition_mesh_loss).
        int csx = SectionPos.posToSectionCoord(destPos.x);
        int csy = SectionPos.posToSectionCoord(destPos.y);
        int csz = SectionPos.posToSectionCoord(destPos.z);
        LevelRendererAccessorMixin rAcc = (LevelRendererAccessorMixin) (Object) promotion.renderer();
        rAcc.seamlessportals$setLastCameraSectionX(csx);
        rAcc.seamlessportals$setLastCameraSectionY(csy);
        rAcc.seamlessportals$setLastCameraSectionZ(csz);

        // 3. Demote outgoing primary.
        if (oldRenderer != null && oldRenderer != promotion.renderer()) {
            PortalWorldManager.demoteFromMain(oldDim, oldRenderer, oldLevel);
        }

        // 4. Replay minimal mc.setLevel side-effects (match HandleRespawnMixin).
        mc.particleEngine.setLevel(promotion.level());
        mc.gameRenderer.setLevel(promotion.level());

        // 5. Transfer the LocalPlayer between ClientLevel entity-lists and
        // re-point its level field to the destination.
        try {
            oldLevel.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS CLIENT-CROSSING] oldLevel.removeEntity failed (ok): {}", e.getMessage());
        }
        ((EntityLevelAccessorMixin) player).seamlessportals$invokeSetLevel(promotion.level());
        ((EntityLevelAccessorMixin) player).seamlessportals$invokeUnsetRemoved();
        try {
            promotion.level().addEntity(player);
        } catch (Exception e) {
            // addEntity may throw if id collision; benign for us since we
            // re-point the existing instance. Log and continue.
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS CLIENT-CROSSING] newLevel.addEntity warning: {}", e.getMessage());
        }

        // 6. Move the player to the authoritative dest position/rotation.
        player.setPos(destPos.x, destPos.y, destPos.z);
        player.xo = destPos.x; player.yo = destPos.y; player.zo = destPos.z;
        player.xOld = destPos.x; player.yOld = destPos.y; player.zOld = destPos.z;
        player.setDeltaMovement(destVel);
        player.setYRot(destYaw);
        player.setXRot(destPitch);
        player.yRotO = destYaw;
        player.xRotO = destPitch;

        // 7. Reset portal-view cache for the new dim + request portal data
        // (mirrors HandleRespawnMixin.afterRespawn).
        PortalContextSwitch.resetChunkFedState(destDim);
        String dimId = destDim.identifier().toString();
        PlatformHelper.getInstance().sendToServer(new ModPayloads.RequestPortalDataPayload(dimId));

        return true;
    }

    private static ResourceKey<Level> parseDim(String id) {
        return switch (id) {
            case "minecraft:overworld" -> Level.OVERWORLD;
            case "minecraft:the_nether" -> Level.NETHER;
            case "minecraft:the_end" -> Level.END;
            default -> null;
        };
    }
}
