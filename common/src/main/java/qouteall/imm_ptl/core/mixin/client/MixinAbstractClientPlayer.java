package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IEAbstractClientPlayer;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §1).
 *
 * <p><b>26.2 retarget.</b> IP 1.21.3 wrote the client-side level via a {@code @Shadow @Mutable}
 * {@code AbstractClientPlayer.clientLevel} field. That field is GONE on 26.2 — {@code AbstractClientPlayer}
 * has no {@code clientLevel} (only the ctor {@code (ClientLevel, GameProfile)} passing to super,
 * {@code 26.2:AbstractClientPlayer.java:27}); the entity's level now lives solely in the private
 * {@code Entity.level} ({@code Entity.java:215}), mutated through the inherited {@code protected void
 * setLevel(Level)} ({@code Entity.java:3962}, body {@code this.level = level;}). So the duck
 * {@code ip_setClientLevel} re-sites onto the inherited {@code setLevel} — the exact api-map prescription
 * ("Duck ip_setClientLevel re-sites to Entity.setLevel ... No client-side shadow field left to mutate").
 *
 * <p>The mixin is {@code abstract} so it may {@code @Shadow} the inherited (concrete, protected) superclass
 * method. This preserves IP's role verbatim: {@code ClientWorldLoader} re-points the client player onto
 * another dimension's {@code ClientLevel} by calling {@code ip_setClientLevel}. Held/unregistered until S13.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer implements IEAbstractClientPlayer {
    // 26.2: no `clientLevel` field — the level pointer is Entity.level, set via the inherited
    // protected Entity.setLevel(Level). @Shadow the superclass method (mixin resolves inherited members).
    @Shadow
    protected abstract void setLevel(Level level);

    @Override
    public void ip_setClientLevel(ClientLevel clientWorld) {
        setLevel(clientWorld);
    }
}
