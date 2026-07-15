// S13 loader-seam compileOnly stub (entity-portal migration; port-note S13A-u11.md). NOT IP source,
// NOT shipped. The held tree registers its three synced argument types through
// ArgumentTypeRegistry.registerArgumentType(...) — AxisArgumentType.init (:63),
// SubCommandArgumentType.init (:78), TimingFunctionArgumentType.init (:60). RUNTIME registration
// that wires the REAL fabric-command-api-v2 type at S13 (:common compile classpath only — the real
// fabric-api type resolves at the fabric loader compile; no shadow, per S10A §6). Signature is a
// verbatim copy of the real fabric-command-api-v2 3.1.0 ArgumentTypeRegistry surface the held tree
// consumes (registerArgumentType only). Removed at S20.
package net.fabricmc.fabric.api.command.v2;

import com.mojang.brigadier.arguments.ArgumentType;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.resources.Identifier;

public final class ArgumentTypeRegistry {
    public static <A extends ArgumentType<?>, T extends ArgumentTypeInfo.Template<A>> void registerArgumentType(
        Identifier id, Class<? extends A> clazz, ArgumentTypeInfo<A, T> serializer
    ) {
    }

    private ArgumentTypeRegistry() {
    }
}
