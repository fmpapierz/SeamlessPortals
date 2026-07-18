package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.PortalWorldManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ADOPT an existing client entity when an add-entity packet arrives for an id
 * that is already present with the same type and UUID — instead of vanilla's
 * discard-and-recreate ({@code ClientLevel.addEntity} unconditionally
 * {@code removeEntity(DISCARDED)}s the old instance, then
 * {@code handleAddEntity} constructs a brand-new one via
 * {@code EntityType.create}).
 *
 * <p>Why: after a seamless crossing the server re-tracks every dest-dim entity
 * (vanilla {@code teleportTo} restarts the trackers) and re-sends ADD for
 * entities our mirror sync already delivered with the SAME ids. The vanilla
 * path made each of them blink (discard now, reappear on the re-add) and
 * re-paid entity construction on the render thread — piglin Brain init alone
 * stalled ~160ms ([SEAMLESS STUCK] proven). Adoption updates the existing
 * instance in place via {@code recreateFromPacket} (position/rotation/motion/
 * id/uuid — the exact init vanilla applies to a fresh instance) — no blink,
 * no construction.
 *
 * <p>IP precedent: MixinClientPacketListener.onHandleAddEntity cancels
 * duplicate adds for passengered vehicles; ours must be broader because our
 * server (unlike IP's replaced tracker) re-sends adds for all mirrored
 * entities after a crossing.
 *
 * <p>Thread safety: HEAD runs before {@code ensureRunningOnSameThread}, so
 * off-thread invocations are guarded — vanilla re-schedules the packet onto
 * the main thread and re-enters, where the adoption check runs for real.
 * Mismatched type or uuid (a genuinely new entity reusing a recycled id)
 * falls through to the vanilla discard-and-create path.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerAddEntityAdoptMixin {

    @Shadow private ClientLevel level;

    @Inject(method = "handleAddEntity", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$adoptExistingEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (!Minecraft.getInstance().isSameThread()) return;
        // S17 sweep DEFENSIVE GATE (wf_5183007f-fee): content-triggered and previously ungated;
        // no flag-ON trigger was provable (IP mirrors dest entities into SEPARATE levels), but a
        // spurious fire would ci.cancel() vanilla's postAddEntitySideEffects (passenger/leash
        // linkage). Block-era adoption serves the block-era crossing only. Flag-OFF unchanged.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) return;
        ClientLevel lvl = this.level;
        if (lvl == null) return;
        Entity existing = lvl.getEntity(packet.getId());
        if (existing == null) return;
        if (existing.getType() != packet.getType()) return;
        if (!existing.getUUID().equals(packet.getUUID())) return;

        existing.recreateFromPacket(packet);
        // Mirror copies are position-driven with physics off; as the ACTIVE
        // dim's authoritative entity it must tick like a vanilla remote entity.
        existing.noPhysics = false;
        PortalWorldManager.noteEntityAdopted(lvl, packet.getId());
        ci.cancel();
    }
}
