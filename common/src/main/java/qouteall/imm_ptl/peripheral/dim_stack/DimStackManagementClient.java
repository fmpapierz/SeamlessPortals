package qouteall.imm_ptl.peripheral.dim_stack;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 26.3 (dedicated servers on NeoForge / MinecraftForge, which do NOT strip {@code @Environment(CLIENT)} members) — the
 * body of {@code DimStackManagement.RemoteCallables.clientOpenScreen}, moved here VERBATIM.
 *
 * <p>{@code DimStackManagement$RemoteCallables} also holds the SERVER's remote-procedure endpoints
 * ({@code serverSetupDimStack}, {@code serverRemoveDimStack}); the RPC dispatcher resolves them by class name, which
 * links the class. The JVM verifies every method body at link time, and {@code clientOpenScreen}'s body (plus its
 * lambda) needs {@code net.minecraft.client.gui.screens.Screen} to prove {@code DimStackScreen → Screen} for
 * {@code gui.setScreen(..)} — a load a dedicated server's dist cleaner refuses, so the first dimension-stack RPC from a
 * player would fail to link there. Upstream IP relies on Fabric Loader deleting the annotated method on servers. Found by
 * the static link audit (an ASM-based verifier simulation) run for the first Forge dedicated-server check. NF-PARITY rule 1.
 */
public final class DimStackManagementClient {

    private DimStackManagementClient() {}

    static void clientOpenScreen(List<String> dimensions) {
        List<ResourceKey<Level>> dimensionList =
            dimensions.stream().map(Helper::dimIdToKey).collect(Collectors.toList());

        DimStackGuiController controller = new DimStackGuiController(
            null,
            () -> dimensionList,
            dimStackInfo -> {
                if (dimStackInfo != null) {
                    McRemoteProcedureCall.tellServerToInvoke(
                        "qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement.RemoteCallables.serverSetupDimStack",
                        dimStackInfo
                    );
                }
                else {
                    McRemoteProcedureCall.tellServerToInvoke(
                        "qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement.RemoteCallables.serverRemoveDimStack"
                    );
                }
                Minecraft.getInstance().gui.setScreen(null);
            }
        );
        controller.initializeAsDefault();
        Minecraft.getInstance().gui.setScreen(controller.view);
    }
}
