package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEClientWorld;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.List;
import java.util.Map;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements IEClientWorld {

    private List<Portal> portal_globalPortals;

    private static final LimitedLogger limitedLogger = new LimitedLogger(100);

    @Shadow
    @Final
    @Mutable
    private ClientPacketListener connection;

    @Mutable
    @Shadow
    @Final
    private ClientChunkCache chunkSource;

    @Shadow
    public abstract Entity getEntity(int id);

    @Shadow
    @Final
    private Minecraft minecraft;

    // 26.2 (api-map world-loader-root §4 / chunk-loading.md #46): the ClientLevel render back-ref is
    // now the LevelExtractor (`ClientLevel.java:152` `private final LevelExtractor levelExtractor`),
    // NOT a LevelRenderer — the render split moved dirty-marking/extract off LevelRenderer. Shadow the
    // renamed field so `ip_resetWorldRendererRef` can null it on dimension disposal (verbatim IP role).
    @Mutable
    @Shadow
    @Final
    private LevelExtractor levelExtractor;

    @Shadow
    @Final
    private EntityTickList tickingEntities;

    @Shadow
    protected abstract Map<MapId, MapItemSavedData> getAllMapData(); // 26.2 (world-loader-root §5.2): map key String -> MapId

    @Shadow
    protected abstract void addMapData(Map<MapId, MapItemSavedData> map); // 26.2 (world-loader-root §5.2): map key String -> MapId

    @Shadow
    @Final
    private BlockStatePredictionHandler blockStatePredictionHandler;

    @Shadow
    @Final
    @Mutable
    private TickRateManager tickRateManager;

    @Shadow
    @Final
    private ClientLevel.ClientLevelData clientLevelData;

    @Override
    public List<Portal> ip_getGlobalPortals() {
        return portal_globalPortals;
    }

    @Override
    public void ip_setGlobalPortals(List<Portal> arg) {
        portal_globalPortals = arg;
    }

    //use my client chunk manager
    // 26.2 (chunk-loading.md #46): ctor descriptor changed — the profiler `Supplier` param is REMOVED,
    // the `LevelRenderer` param became a `LevelExtractor`, and a trailing `int seaLevel` was added
    // (`ClientLevel.java:238-249`). The `@Mutable @Shadow @Final chunkSource` RETURN-inject swap is
    // unchanged; `loadDistance` (serverChunkRadius) is still the 5th ctor arg, so the install call
    // (`O_O.createMyClientChunkManager(world, loadDistance)` -> `new ImmPtlClientChunkMap(...)`) is
    // verbatim IP. Covers EVERY client world including the vanilla main one (current-mod-core §5).
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    void onConstructed(
        ClientPacketListener clientPacketListener, ClientLevel.ClientLevelData clientLevelData,
        ResourceKey resourceKey, Holder holder, int loadDistance, int j,
        LevelExtractor levelExtractor, boolean bl, long l, int seaLevel, CallbackInfo ci
    ) {
        ClientLevel clientWorld = (ClientLevel) (Object) this;
        // NF-PARITY C3 (2026-08-25): O_O.createMyClientChunkManager deleted (its client-typed
        // body broke O_O's LINK on a NeoForge dedicated server); its one-line body inlined.
        ClientChunkCache myClientChunkManager =
            new qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap(clientWorld, loadDistance);
        chunkSource = myClientChunkManager;
    }

    /**
     * S14-A FIX-2 (B2, audit links clientworld+ticklight): on 26.2, {@code tickTime()} gained a
     * cross-world side effect — {@code clockManager().tick(gameTime)} writes the CONNECTION-scoped
     * {@link net.minecraft.client.ClientClockManager} shared by ALL client levels, and its tick is
     * DELTA-based (every clock += fedGameTime - lastTickGameTime). A remote-ticked secondary feeds
     * its OWN gameTime into that shared telescope, so alternating main/secondary feeds snap every
     * render-visible clock (overworld sun/moon/sky time) to authoritative-(M-S) the moment the
     * first cross-dim secondary ticks. IP 1.21.3's {@code tickTime} wrote ONLY per-level fields —
     * no shared object existed — so IP's verbatim {@code newWorld.tick(() -> true)} was cross-level
     * inert. This restores exactly those semantics for the remote loop: advance the level's own
     * gameTime, skip the shared clock. {@code isClientRemoteTicking} brackets precisely
     * {@link ClientWorldLoader#tick()}'s remote loop (main-level ticking and vanilla
     * {@code handleSetTime} are untouched).
     */
    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void onTickTime(CallbackInfo ci) {
        if (ClientWorldLoader.isClientRemoteTicking) {
            clientLevelData.setGameTime(clientLevelData.getGameTime() + 1L);
            ci.cancel();
        }
    }

    // avoid entity duplicate when an entity travels
    @Inject(
        method = "addEntity",
        at = @At("TAIL")
    )
    private void onOnEntityAdded(Entity entityIn, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                if (world != (Object) this) {
                    world.removeEntity(entityIn.getId(), Entity.RemovalReason.DISCARDED);
                }
            }
        }
    }

    /**
     * If the player goes into a portal when the other side chunk is not yet loaded
     * freeze the player so the player won't drop
     * {@link net.minecraft.client.player.LocalPlayer#tick()}
     */
    @Inject(
        method = "Lnet/minecraft/client/multiplayer/ClientLevel;hasChunk(II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onHasChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Boolean> cir) {
        if (IPGlobal.tickOnlyIfChunkLoaded) {
            LevelChunk chunk = chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null || chunk instanceof EmptyLevelChunk) {
                cir.setReturnValue(false);
            }
        }
    }

    // for debug
    @Inject(method = "Lnet/minecraft/client/multiplayer/ClientLevel;toString()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void onToString(CallbackInfoReturnable<String> cir) {
        ClientLevel this_ = (ClientLevel) (Object) this;
        cir.setReturnValue("ClientWorld " + this_.dimension().identifier()); // 26.2 (S5): ResourceKey.location() -> identifier()
    }

    @Inject(
        method = "tickNonPassenger",
        at = @At("HEAD")
    )
    private void onTickNonPassenger(Entity entity, CallbackInfo ci) {
        // this should be done right before setting last tick pos to this tick pos
        ((IEEntity) entity).ip_tickCollidingPortal();
    }

    @Override
    public void ip_resetWorldRendererRef() {
        levelExtractor = null; // 26.2: null the renamed render back-ref (was `levelRenderer`)
    }

    @Override
    public EntityTickList ip_getEntityList() {
        return tickingEntities;
    }

    @Override
    public Map<MapId, MapItemSavedData> ip_getAllMapData() {
        return getAllMapData();
    }

    @Override
    public void ip_addMapData(Map<MapId, MapItemSavedData> map) {
        addMapData(map);
    }

    @Override
    public BlockStatePredictionHandler ip_getBlockStatePredictionHandler() {
        return blockStatePredictionHandler;
    }

    @Override
    public void ip_setTickRateManager(TickRateManager cond) {
        tickRateManager = cond;
    }
}
