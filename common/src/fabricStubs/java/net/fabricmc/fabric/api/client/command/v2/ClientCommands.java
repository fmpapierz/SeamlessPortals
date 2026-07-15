// S13-A loader-seam compileOnly stub (entity-portal migration; port-note S13A-u11.md). NOT IP source,
// NOT shipped. The held ClientDebugCommand builds its whole client-command tree through
// ClientCommands.literal(...) / ClientCommands.argument(...) (fabric-command-api-v2 3.1.0 renamed IP's
// 1.21.3 ClientCommandManager to ClientCommands). RUNTIME builder factory provided by the REAL
// fabric-command-api-v2 at S13 (:common compile classpath only — the real fabric-api type resolves at
// the fabric loader compile; no shadow, per S10A §6). Signatures are a verbatim copy of the real
// ClientCommands.literal/argument surface the held tree consumes. Removed at S20.
package net.fabricmc.fabric.api.client.command.v2;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

public final class ClientCommands {
    public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    public static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    private ClientCommands() {
    }
}
