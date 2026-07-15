// S13-A loader-seam compileOnly stub (entity-portal migration; port-note S13A-u11.md). NOT IP source,
// NOT shipped. The held client-command tree (ClientDebugCommand) is generic over
// FabricClientCommandSource and calls context.getSource().sendFeedback(Component) throughout. RUNTIME
// type provided by the REAL fabric-command-api-v2 at S13 (:common compile classpath only — the real
// fabric-api type resolves at the fabric loader compile; no shadow, per S10A §6). Surface is a verbatim
// subset of the real fabric-command-api-v2 3.1.0 FabricClientCommandSource (the send methods + the
// SharedSuggestionProvider super-interface the real type declares). Removed at S20.
package net.fabricmc.fabric.api.client.command.v2;

import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

public interface FabricClientCommandSource extends SharedSuggestionProvider {
    void sendFeedback(Component message);

    void sendError(Component message);
}
