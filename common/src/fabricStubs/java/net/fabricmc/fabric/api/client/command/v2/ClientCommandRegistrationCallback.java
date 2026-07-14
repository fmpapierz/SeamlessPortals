// S10-B loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md +
// fragment S10B-init.md). NOT IP source, NOT shipped. Held tree uses
// ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> ...) in
// IPModMainClient.init (client-command registration). RUNTIME event registration that wires the
// REAL fabric-command-api-v2 client event at S13 (compileOnly, :common classpath only — the real
// fabric-api type resolves at the fabric loader compile; no shadow, per S10A §6). The dispatcher
// source generic is a wildcard here: the sole consumer of the arg is the held ClientDebugCommand
// (U11 forward-ref, unresolved on this probe by design), and the real fabric type
// (CommandDispatcher<FabricClientCommandSource>) applies at S13. Removed at S20.
package net.fabricmc.fabric.api.client.command.v2;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.commands.CommandBuildContext;

@FunctionalInterface
public interface ClientCommandRegistrationCallback {
    Event<ClientCommandRegistrationCallback> EVENT = null;

    void register(CommandDispatcher<?> dispatcher, CommandBuildContext registryAccess);
}
