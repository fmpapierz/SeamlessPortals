package qouteall.imm_ptl.core.platform_specific;

import com.warwa.seamlessportals.platform.ClientPlatform;
import com.warwa.seamlessportals.platform.ModVersionInfo;
import com.warwa.seamlessportals.platform.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.custom_portal_gen.PortalGenInfo;
import qouteall.q_misc_util.Helper;

import java.nio.file.Path;
import java.util.List;

public class O_O {
    public static boolean isDimensionalThreadingPresent = false;
    
    public static boolean isForge() {
        return Platform.get().isForgeLike(); // NF-PARITY W8
    }
    
    @Environment(EnvType.CLIENT)
    public static void onPlayerChangeDimensionClient(
        ResourceKey<Level> from, ResourceKey<Level> to
    ) {
        // NF-PARITY C3: the client half of RequiemCompat is a separate class now (dist-split).
        // This method's own signature/body are dist-safe (invokestatic needs no callee load).
        RequiemCompatClient.onPlayerTeleportedClient();
    }
    
    public static void onPlayerTravelOnServer(
        ServerPlayer player,
        ServerLevel fromWorld, ServerLevel toWorld
    ) {
        RequiemCompat.onPlayerTeleportedServer(player);
    }
    
    public static Path getGameDir() {
        return Platform.get().getGameDir(); // NF-PARITY W8
    }
    
    private static final BlockState obsidianState = Blocks.OBSIDIAN.defaultBlockState();
    
    public static boolean isObsidian(BlockState blockState) {
        return blockState == obsidianState;
    }
    
    public static void postClientChunkLoadEvent(LevelChunk chunk) {
        // NF-PARITY W8
        ClientPlatform.get().postClientChunkLoadEvent(
            ((ClientLevel) chunk.getLevel()), chunk
        );
    }

    public static void postClientChunkUnloadEvent(LevelChunk chunk) {
        // NF-PARITY W8
        ClientPlatform.get().postClientChunkUnloadEvent(
            ((ClientLevel) chunk.getLevel()), chunk
        );
    }

    public static boolean isDedicatedServer() {
        return Platform.get().isDedicatedServer(); // NF-PARITY W8
    }
    
    public static void postPortalSpawnEventForge(PortalGenInfo info) {
    
    }
    
    // NF-PARITY C3 dist-split (2026-08-25, E0 measured): createMyClientChunkManager DELETED.
    // Verifying its body (ImmPtlClientChunkMap -> ClientChunkCache assignability) force-loaded
    // client classes when O_O LINKED on a NeoForge dedicated server — the measured
    // NoClassDefFoundError at IPModMain.loadConfig's O_O.getGameDir() call. Fabric never hit
    // this only because its loader physically strips @Environment members on servers; NeoForge
    // has no stripping, so client-typed bodies cannot live in server-linked classes. The single
    // caller (MixinClientLevel:122, client-only) now constructs ImmPtlClientChunkMap directly —
    // the method body was exactly that one constructor call.
    
    // NF-PARITY W2 (2026-08-25): getIsPehkuiPresent() deleted — zero callers in-tree
    // (pehkui compat was never ported; grep "getIsPehkuiPresent" = declaration only).

    @Nullable
    public static String getImmPtlModInfoUrl() {
        String gameVersion = SharedConstants.getCurrentVersion().name();
        
        if (O_O.isForge()) {
            return "https://qouteall.fun/immptl_info/forge-%s.json".formatted(gameVersion);
        }
        else {
            // it's in github pages
            // https://github.com/qouteall/immptl_info
            return "https://qouteall.fun/immptl_info/%s.json".formatted(gameVersion);
        }
    }
    
    public static boolean isModLoadedWithinVersion(String modId, @Nullable String startVersion, @Nullable String endVersion) {
        // NF-PARITY W8: empty compare = unparseable bound -> ignore that bound (old print-and-pass path)
        if (!Platform.get().isModLoaded(modId)) {
            return false;
        }

        if (startVersion != null) {
            var c = Platform.get().compareModVersionTo(modId, startVersion);
            if (c.isPresent() && c.getAsInt() < 0) {
                return false;
            }
        }

        if (endVersion != null) {
            var c = Platform.get().compareModVersionTo(modId, endVersion);
            if (c.isPresent() && c.getAsInt() > 0) {
                return false;
            }
        }

        return true;
    }
    
    public static @NotNull ImmPtlNetworkConfig.ModVersion getImmPtlVersion() {
        // SELF-IDENTITY RE-HOST (S13 first-light fix): "iportal" -> the host mod id.
        // NF-PARITY W8: dev-placeholder + wrong-component-count fallbacks merged into one isRegularSemantic() branch
        ModVersionInfo info = Platform.get().getModVersion("seamlessportals").orElseThrow();

        if (!info.isRegularSemantic()) {
            Helper.LOGGER.error(
                "immersive portals version {} is not in regular form", info.raw()
            );
            return ImmPtlNetworkConfig.ModVersion.OTHER;
        }

        return new ImmPtlNetworkConfig.ModVersion(
            info.major(),
            info.minor(),
            info.patch()
        );
    }
    
    public static String getImmPtlVersionStr() {
        // SELF-IDENTITY RE-HOST (S13 first-light fix): "iportal" -> the host mod id.
        return Platform.get().getModVersion("seamlessportals").orElseThrow().raw(); // NF-PARITY W8
    }

    public static boolean shouldUpdateImmPtl(String latestReleaseVersion) {
        if (Platform.get().isDevelopmentEnvironment()) { // NF-PARITY W8
            return false;
        }

        // SELF-IDENTITY RE-HOST (S13 first-light fix): "iportal" -> the host mod id.
        // NF-PARITY W8: installed-vs-latest compare on the facade (empty = unparseable -> false)
        var c = Platform.get().compareModVersionTo("seamlessportals", latestReleaseVersion);
        return c.isPresent() && c.getAsInt() < 0;
    }
    
    public static String getModDownloadLink() {
        return "https://modrinth.com/mod/immersiveportals";
    }
    
    public static String getIssueLink() {
        return "https://github.com/iPortalTeam/ImmersivePortalsMod/discussions";
    }
    
    @Nullable
    public static Identifier getModIconLocation(String modid) {
        String path = Platform.get().getModIconPath(modid) // NF-PARITY W8
            .orElse(null);
        if (path == null) {
            return null;
        }
        
        // for example, if the icon path is "assets/modid/icon.png"
        // then the result should be modid:icon.png
        
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.startsWith("assets")) {
            path = path.substring("assets".length());
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String[] parts = path.split("/");
        if (parts.length != 2) {
            return null;
        }
        return McHelper.newResourceLocation(parts[0], parts[1]);
    }
    
    @Nullable
    public static String getModName(String modid) {
        return Platform.get().getModDisplayName(modid).orElse(null); // NF-PARITY W8
    }
    
    // most quilt installations use quilted fabric api
    public static boolean isQuilt() {
        return Platform.get().isModLoaded("quilted_fabric_api"); // NF-PARITY W8
    }
    
    public static List<String> getLoadedModIds() {
        return Platform.get().getLoadedModIds(); // NF-PARITY W8
    }
    
    public static boolean allowTeleportingEntity(Entity entity, Portal portal) {
        // ForgeHooks.onTravelToDimension() on Forge
        return true;
    }
    
    public static boolean isDevEnv() {
        return Platform.get().isDevelopmentEnvironment(); // NF-PARITY W8
    }
}
