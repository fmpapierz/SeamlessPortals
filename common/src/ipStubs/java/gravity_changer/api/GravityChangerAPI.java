// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. gravity_changer has no MC 26.2
// build. S04-compat.md §3 GRAVITY G1 (FLAG B): GravityChangerInterface is a compile-mandatory
// invoker base (McHelper S5 imports it) landing at S4; the plan's F21 type list omits
// gravity_changer, so this extends F21 with it (boundary-defense identical: third-party
// types, not IP source). Only GravityChangerInterface references it. Deleted at S20.
package gravity_changer.api;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class GravityChangerAPI {
    public static Vec3 getEyeOffset(Entity entity) {
        return null;
    }

    public static Direction getGravityDirection(Entity entity) {
        return null;
    }

    public static Direction getBaseGravityDirection(Entity entity) {
        return null;
    }

    public static void setBaseGravityDirection(Entity entity, Direction direction) {
    }

    public static void instantlySetClientBaseGravityDirection(Player player, Direction direction) {
    }

    public static Vec3 getWorldVelocity(Entity entity) {
        return null;
    }

    public static void setWorldVelocity(Entity entity, Vec3 velocity) {
    }
}
