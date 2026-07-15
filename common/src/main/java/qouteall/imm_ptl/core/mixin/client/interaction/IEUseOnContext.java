package qouteall.imm_ptl.core.mixin.client.interaction;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * S12-B addition (26.2 translation for {@link MixinMultiPlayerGameMode}, mixin-client.md §4).
 *
 * <p>IP 1.21.3 constructed {@code new UseOnContext(level, player, hand, stack, hit)} directly — the 5-arg
 * explicit-level ctor was accessible. On 26.2 that ctor is <b>{@code protected}</b>
 * ({@code 26.2:UseOnContext.java:24}: {@code protected UseOnContext(Level, @Nullable Player, InteractionHand,
 * ItemStack, BlockHitResult)}), so it cannot be called from the {@code qouteall} package. The api-map
 * sanctions "AT/AW or invoker"; this uses a Mixin constructor {@code @Invoker} (no shared accesswidener/AT
 * edit, self-contained held mixin) so {@code MixinMultiPlayerGameMode.redirectNewUseOnContext} can build a
 * {@code UseOnContext} against the switched {@code mc.level}. Held/unregistered until S13.
 */
@Mixin(UseOnContext.class)
public interface IEUseOnContext {
    @Invoker("<init>")
    static UseOnContext ip_create(
        Level level, Player player, InteractionHand hand, ItemStack itemStack, BlockHitResult hitResult
    ) {
        throw new AssertionError();
    }
}
