// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. S04-compat.md §3 GRAVITY G2
// (FLAG B). getWorldRotationQuaternion returns org.joml.Quaternionf so IP's
// DQuaternion.fromMcQuaternion(Quaternionf) overload resolves (DQuaternion.java:45).
// Deleted at S20.
package gravity_changer.util;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public class RotationUtil {
    public static Quaternionf getWorldRotationQuaternion(Direction direction) {
        return null;
    }

    public static Vec3 vecPlayerToWorld(Vec3 vec, Direction gravity) {
        return null;
    }

    public static Vec3 vecWorldToPlayer(Vec3 vec, Direction gravity) {
        return null;
    }

    public static Direction dirPlayerToWorld(Direction direction, Direction gravity) {
        return null;
    }

    public static Direction dirWorldToPlayer(Direction direction, Direction gravity) {
        return null;
    }
}
