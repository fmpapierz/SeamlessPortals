package qouteall.imm_ptl.core.mixin.common.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * ★ CROSS-PORTAL ENTITY HIT knockback fix (2026-09-10; live verdict on the first round: "works
 * but knockback is wrong way — towards me instead of away"). Vanilla computes both melee
 * knockback directions in the ATTACKER'S RAW FRAME:
 * <ul>
 *   <li><b>base knockback</b> — away from {@code damageSource.getSourcePosition()}, which falls
 *       back to the attacker's raw position ({@code 26.2:LivingEntity.dealDefaultKnockback:
 *       1297-1302});</li>
 *   <li><b>attack-strength / sprint / enchant knockback</b> — along the attacker's raw yaw
 *       ({@code 26.2:Player.causeExtraKnockback:1110-1138}, {@code sin/cos(getYRot())}), with a
 *       separate {@code Entity.push} branch for non-living targets (minecarts).</li>
 * </ul>
 * Through a portal both are the wrong frame: a facing-linked portal pair flips the direction
 * 180° — the observed "knocked towards me". While {@link
 * BlockManipulationServer#CROSS_PORTAL_ATTACK_CONTEXT} is armed (only inside the cross-portal
 * attack acceptor, around one synchronous {@code player.attack} call on the server thread),
 * these three wraps move the inputs into the destination frame:
 * <ol>
 *   <li>the attack's damage source gains an explicit {@code portal.transformPoint(attacker
 *       position)} via {@link IEDamageSource} — fixing base knockback, damage tilt and shield
 *       angles in one stroke;</li>
 *   <li>/<li value="3">the two {@code causeExtraKnockback} direction pairs are ROTATED through
 *       the portal ({@code transformLocalVecNonScale} of the computed horizontal direction,
 *       magnitude preserved) — a pure frame correction of whatever vanilla computed, identity
 *       for an unrotated portal.</li>
 * </ol>
 * Unarmed (every vanilla attack) each wrap is one ThreadLocal read. Sweep-victim knockback and
 * shield-deflects-attacker knockback keep the raw frame — recorded residuals, both cosmetic
 * relative to the main-target fix.
 */
@Mixin(Player.class)
public abstract class MixinPlayer_CrossPortalKnockback {

    @WrapOperation(
        method = "createAttackSource",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;getDamageSource"
                + "(Lnet/minecraft/world/entity/LivingEntity;)"
                + "Lnet/minecraft/world/damagesource/DamageSource;"
        )
    )
    private DamageSource ip_transformAttackSourcePosition(
        ItemStack stack, LivingEntity attacker, Operation<DamageSource> original
    ) {
        DamageSource source = original.call(stack, attacker);
        Portal portal = BlockManipulationServer.CROSS_PORTAL_ATTACK_CONTEXT.get();
        if (portal == null) {
            return source;
        }
        return IEDamageSource.ip_create(
            source.typeHolder(),
            source.getDirectEntity(),
            source.getEntity(),
            portal.transformPoint(attacker.position())
        );
    }

    @WrapOperation(
        method = "causeExtraKnockback",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;knockback"
                + "(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V"
        )
    )
    private void ip_rotateLivingKnockback(
        LivingEntity target, double power, double xd, double zd,
        DamageSource source, float damage, boolean comesFromEffect,
        Operation<Void> original
    ) {
        Portal portal = BlockManipulationServer.CROSS_PORTAL_ATTACK_CONTEXT.get();
        if (portal != null) {
            double[] rotated = ip_rotateHorizontal(portal, xd, zd);
            xd = rotated[0];
            zd = rotated[1];
        }
        original.call(target, power, xd, zd, source, damage, comesFromEffect);
    }

    // The non-living branch — MINECARTS, the headline target of cart-breaking through portals.
    @WrapOperation(
        method = "causeExtraKnockback",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;push(DDD)V"
        )
    )
    private void ip_rotatePushKnockback(
        Entity target, double x, double y, double z, Operation<Void> original
    ) {
        Portal portal = BlockManipulationServer.CROSS_PORTAL_ATTACK_CONTEXT.get();
        if (portal != null) {
            double[] rotated = ip_rotateHorizontal(portal, x, z);
            x = rotated[0];
            z = rotated[1];
        }
        original.call(target, x, y, z);
    }

    /**
     * Rotates a horizontal knockback direction into the portal's destination frame, preserving
     * the original horizontal magnitude ({@code knockback} normalizes anyway; {@code push} does
     * not, so magnitude matters there). A portal that rotates horizontal into vertical (a floor
     * portal) leaves a near-zero horizontal pair — passed through unscaled rather than blown up
     * by renormalization; geometrically honest, and {@code knockback}'s own {@code <1e-5} random
     * fallback handles the degenerate case.
     */
    @Unique
    private static double[] ip_rotateHorizontal(Portal portal, double xd, double zd) {
        double magnitude = Math.sqrt(xd * xd + zd * zd);
        Vec3 rotated = portal.transformLocalVecNonScale(new Vec3(xd, 0.0, zd));
        double horizontal = Math.sqrt(rotated.x * rotated.x + rotated.z * rotated.z);
        if (magnitude < 1.0E-9 || horizontal < 1.0E-9) {
            return new double[]{rotated.x, rotated.z};
        }
        return new double[]{rotated.x / horizontal * magnitude, rotated.z / horizontal * magnitude};
    }
}
