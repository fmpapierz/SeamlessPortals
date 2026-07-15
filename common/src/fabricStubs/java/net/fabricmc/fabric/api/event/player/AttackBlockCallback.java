// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md +
// S13A-peripheral.md). NOT IP source, NOT shipped. Faithful shell of Fabric's AttackBlockCallback as
// far as the HELD tree consumes it: the static EVENT field (Event<AttackBlockCallback>) and the
// functional interact(...) method (first held consumer: PortalWandItem.init registers on it so the
// wand cannot break blocks). NeoForge routes this through PlayerInteractEvent.LeftClickBlock at the
// PlatformHelper seam (api-map platform-compat-peripheral §4); the loader wiring is C1/S19. :common
// compile classpath only; the REAL fabric AttackBlockCallback resolves at S13-B on :fabric. Removed
// at S20.
package net.fabricmc.fabric.api.event.player;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public interface AttackBlockCallback {
    Event<AttackBlockCallback> EVENT = new Event<>();

    InteractionResult interact(Player player, Level world, InteractionHand hand, BlockPos pos, Direction direction);
}
