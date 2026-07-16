package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.ducks.IEAbstractClientPlayer;
import qouteall.imm_ptl.core.ducks.IEEntity;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §1).
 *
 * <p><b>26.2 retarget.</b> IP 1.21.3 wrote the client-side level via a {@code @Shadow @Mutable}
 * {@code AbstractClientPlayer.clientLevel} field. That field is GONE on 26.2 — {@code AbstractClientPlayer}
 * has no {@code clientLevel} (only the ctor {@code (ClientLevel, GameProfile)} passing to super,
 * {@code 26.2:AbstractClientPlayer.java:27}); the entity's level now lives solely in the private
 * {@code Entity.level} ({@code Entity.java:215}), mutated through {@code protected void setLevel(Level)}
 * ({@code Entity.java:3962}, body {@code this.level = level;}). So the duck {@code ip_setClientLevel}
 * re-sites onto that {@code Entity.level} write — the exact api-map prescription
 * ("Duck ip_setClientLevel re-sites to Entity.setLevel ... No client-side shadow field left to mutate").
 *
 * <p><b>S13-C weave fix.</b> A {@code @Shadow} of the inherited {@code Entity.setLevel} on
 * {@code @Mixin(AbstractClientPlayer)} does NOT resolve at weave time — mixin only locates members
 * DECLARED in the target class, and neither {@code AbstractClientPlayer} nor {@code Player}/
 * {@code LivingEntity} declares {@code setLevel}; it is inherited from {@code Entity}. Weaving the shadow
 * failed with "{@code @Shadow method setLevel ... NOT located in ... AbstractClientPlayer}". Instead this
 * routes the write through the existing {@link IEEntity#ip_setWorld(net.minecraft.world.level.Level)}
 * duck: the {@code @Mixin(Entity.class)} {@code MixinEntity} shadows {@code Entity.level} and writes it
 * directly ({@code this.level = world;}), which is byte-for-byte the body of {@code Entity.setLevel}.
 * Behaviourally identical to the api-map's {@code Entity.setLevel} prescription (setLevel is a pure field
 * write, no side effects); IP's role is preserved verbatim ({@code ClientWorldLoader} re-points the client
 * player onto another dimension's {@code ClientLevel}). Held/unregistered until S13.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer implements IEAbstractClientPlayer {
    @Override
    public void ip_setClientLevel(ClientLevel clientWorld) {
        // 26.2: no `clientLevel` field — the entity level pointer is Entity.level. Cast to the IEEntity
        // duck (Entity implements it via MixinEntity) and write the field through ip_setWorld, which is
        // the exact body of the inherited Entity.setLevel(Level) (pure `this.level = level;`).
        ((IEEntity) (Object) this).ip_setWorld(clientWorld);
    }
}
