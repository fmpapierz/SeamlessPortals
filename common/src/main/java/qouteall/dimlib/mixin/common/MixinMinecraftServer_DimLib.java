// F11 DimLib — S19-D LANDED (migration/port-notes/S19-peripheral-tail.md §6). The load-event mixin:
// port of the REAL DimLib MixinMinecraftServer's createLevels-HEAD hook. Opens the direct-
// registration window, fires DimensionAPI.SERVER_DIMENSIONS_LOAD_EVENT (whose only listener is
// DimStackManagement, which fans out to AlternateDimensions.addAltDimsIfUsedInDimStack ->
// DimensionAPI.addDimensionIfNotExists), then closes the window — so alt-dim level stems are
// registered before the createLevels loop instantiates ServerLevels from the LEVEL_STEM registry.
//
// 26.2-forced (pinned vs mc262-ref MinecraftServer:2005-area createLevels): 26.2's createLevels is
// NO-ARG (IP's 1.21.11 signature carried a ChunkProgressListener). Use the repo's proven bare-name
// selector — the shipping MixinMinecraftServer_Misc (createLevels RETURN) and
// MixinMinecraftServer_DimStack_CVB (createLevels setInitialSpawn INVOKE + RETURN) inject the same
// method by bare name; this is now the THIRD createLevels injector (HEAD) and coexists with them.
//
// S19-D SIMPLIFICATION (design §6): the window latch is a plain static in DimensionImpl instead of
// IP's per-server ip_canDirectlyRegisterDimension duck. try/finally guarantees it is cleared even
// if a listener escapes the SERVER_DIMENSIONS_LOAD_EVENT wrapper (that fabric Event already swallows
// per-listener exceptions, so this is defensive). Woven flag-ON only (qouteall.* gate). S20-deleted.
package qouteall.dimlib.mixin.common;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.dimlib.DimensionImpl;
import qouteall.dimlib.api.DimensionAPI;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer_DimLib {

    @Inject(method = "createLevels", at = @At("HEAD"))
    private void dimlib_onBeforeCreateWorlds(CallbackInfo ci) {
        DimensionImpl.canDirectlyRegisterDimension = true;

        try {
            DimensionAPI.SERVER_DIMENSIONS_LOAD_EVENT.invoker().run(
                (MinecraftServer) (Object) this
            );
        }
        finally {
            DimensionImpl.canDirectlyRegisterDimension = false;
        }
    }
}
