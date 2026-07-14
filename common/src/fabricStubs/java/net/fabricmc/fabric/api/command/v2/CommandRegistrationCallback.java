// S10-B loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md +
// fragment S10B-init.md). NOT IP source, NOT shipped. Held tree uses
// CommandRegistrationCallback.EVENT.register((dispatcher, ctx, environment) -> ...) in
// IPModMain.init (server-command registration). This is a RUNTIME event registration that wires
// the REAL fabric-command-api-v2 event at S13 (the stub is compileOnly, :common classpath only —
// the real fabric-api type resolves at the fabric loader compile; no shadow, per S10A §6).
// The ACTUAL per-loader command-registration wiring is an S13 seam concern (world-loader-root
// api-map §3: command + argument-type registration is loader-specific). Removed at S20.
package net.fabricmc.fabric.api.command.v2;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

@FunctionalInterface
public interface CommandRegistrationCallback {
    Event<CommandRegistrationCallback> EVENT = null;

    void register(
        CommandDispatcher<CommandSourceStack> dispatcher,
        CommandBuildContext registryAccess,
        Commands.CommandSelection environment
    );
}
