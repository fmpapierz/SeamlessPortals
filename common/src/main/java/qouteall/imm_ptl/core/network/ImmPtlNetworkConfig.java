package qouteall.imm_ptl.core.network;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.platform.ClientPlatform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.mixin.common.other_sync.IEServerConfigurationPacketListenerImpl;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.platform_specific.O_O;

import java.util.function.Consumer;

public class ImmPtlNetworkConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static record ModVersion(
        int major, int minor, int patch
    ) {
        // for dev env
        public static final ModVersion OTHER = new ModVersion(0, 0, 0);
        
        public static ModVersion read(FriendlyByteBuf buf) {
            int major = buf.readVarInt();
            int minor = buf.readVarInt();
            int patch = buf.readVarInt();
            return new ModVersion(major, minor, patch);
        }
        
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(major);
            buf.writeVarInt(minor);
            buf.writeVarInt(patch);
        }
        
        @Override
        public String toString() {
            return "%d.%d.%d".formatted(major, minor, patch);
        }
        
        public boolean isNormalVersion() {
            return !OTHER.equals(this);
        }
        
        public boolean isCompatibleWith(ModVersion another) {
            return major == another.major && minor == another.minor;
        }
    }
    
    public static ModVersion immPtlVersion;
    
    public static record ImmPtlConfigurationTask(
    ) implements ConfigurationTask {
        public static final ConfigurationTask.Type TYPE =
            new ConfigurationTask.Type("iportal:config");
        
        @Override
        public void start(Consumer<Packet<?>> consumer) {
            consumer.accept(
                new ClientboundCustomPayloadPacket(new S2CConfigStartPacket(
                    immPtlVersion
                ))
            );
        }
        
        @Override
        public @NotNull Type type() {
            return TYPE;
        }
    }
    
    public static record S2CConfigStartPacket(
        ModVersion versionFromServer
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<S2CConfigStartPacket> TYPE =
            new CustomPacketPayload.Type<>(
                McHelper.newResourceLocation("iportal:config_packet")
            );
        
        public static final StreamCodec<FriendlyByteBuf, S2CConfigStartPacket> CODEC = StreamCodec.of(
            (b, p) -> p.write(b), S2CConfigStartPacket::read
        );
        
        public static S2CConfigStartPacket read(FriendlyByteBuf buf) {
            ModVersion info = ModVersion.read(buf);
            return new S2CConfigStartPacket(info);
        }
        
        public void write(FriendlyByteBuf buf) {
            versionFromServer.write(buf);
        }
        
        // handled on client side
        // NF-PARITY W12: loader Context -> facade replySender (PlatformHelper.
        // ClientConfigPayloadHandler). The @Environment(CLIENT) annotation is DROPPED: the
        // new signature references no client type (the old one took Fabric's client-only
        // Context), and init() now forms a method ref to this from COMMON code — on a
        // stripped Fabric dedicated server an annotated method would NoSuchMethodError at
        // the lambda bootstrap. The body is dist-safe (logger + statics + the reply sender);
        // only clients ever RECEIVE the payload.
        public void handle(Consumer<CustomPacketPayload> replySender) {
            LOGGER.info(
                "Client received ImmPtl config packet. Server mod version: {}", versionFromServer
            );

            serverVersion = versionFromServer;
            replySender.accept(new C2SConfigCompletePacket(
                immPtlVersion, IPConfig.getConfig().clientTolerantVersionMismatchWithServer
            ));
        }
        
        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    
    public record C2SConfigCompletePacket(
        ModVersion versionFromClient,
        boolean clientTolerantVersionMismatch
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<C2SConfigCompletePacket> TYPE =
            new CustomPacketPayload.Type<>(
                McHelper.newResourceLocation("iportal:configure_complete")
            );
        public static final StreamCodec<FriendlyByteBuf, C2SConfigCompletePacket> CODEC = StreamCodec.of(
            (b, p) -> p.write(b), C2SConfigCompletePacket::read
        );
        
        public static C2SConfigCompletePacket read(FriendlyByteBuf buf) {
            ModVersion info = ModVersion.read(buf);
            boolean clientTolerantVersionMismatch = buf.readBoolean();
            return new C2SConfigCompletePacket(info, clientTolerantVersionMismatch);
        }
        
        public void write(FriendlyByteBuf buf) {
            versionFromClient.write(buf);
            buf.writeBoolean(clientTolerantVersionMismatch);
        }
        
        // handled on server side
        // NF-PARITY W12: loader Context -> facade ServerConfigContext. The gameProfile
        // accessor cast and the addTask/completeTask interface-injection casts moved into the
        // loader bindings (FabricPlatformHelper / NeoForgePlatformHelper), unchanged in
        // behavior; the version-gate logic below is byte-identical.
        public void handle(
            PlatformHelper.ServerConfigContext context
        ) {
            GameProfile gameProfile = context.gameProfile();

            LOGGER.info(
                "Server received ImmPtl config packet. Mod version: {} Player: {} {}",
                versionFromClient, gameProfile.name(), gameProfile.id()
            );

            if (versionFromClient.isNormalVersion() && immPtlVersion.isNormalVersion()) {
                if ((versionFromClient.major != immPtlVersion.major ||
                    versionFromClient.minor != immPtlVersion.minor) &&
                    !IPConfig.getConfig().serverTolerantVersionMismatchWithClient &&
                    !clientTolerantVersionMismatch
                ) {
                    context.disconnect(Component.translatable(
                        "imm_ptl.mod_major_minor_version_mismatch",
                        immPtlVersion.toString(),
                        versionFromClient.toString()
                    ));
                    LOGGER.info(
                        """
                            Disconnecting client because of ImmPtl version difference (only patch version difference is tolerated).
                            Game Profile: {}
                            Client ImmPtl version: {}
                            Server ImmPtl version: {}""",
                        gameProfile, versionFromClient, immPtlVersion
                    );
                    return;
                }
            }

            context.finishTask(ImmPtlConfigurationTask.TYPE);
        }
        
        @Override
        public @NotNull Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    
    public static void init() {
        immPtlVersion = O_O.getImmPtlVersion();

        LOGGER.info("Immersive Portals Core version {}", immPtlVersion);

        // NF-PARITY W12: type registration + receivers ride the loader-neutral configuration
        // seams (Fabric: PayloadTypeRegistry.clientboundConfiguration()/
        // serverboundConfiguration() + registerGlobalReceiver; NeoForge:
        // PayloadRegistrar.configurationToClient/ToServer, NETWORK thread, .optional() — see
        // the bindings). BOTH payloads register HERE, in common init, exactly like the old
        // PayloadTypeRegistry calls — the S2C TYPE must exist on a dedicated server (it SENDS
        // the packet); the Fabric binding guards the client-only receiver half internally.
        PlatformHelper.getInstance().registerConfigClientboundPayload(
            S2CConfigStartPacket.TYPE, S2CConfigStartPacket.CODEC,
            S2CConfigStartPacket::handle
        );
        PlatformHelper.getInstance().registerConfigServerboundPayload(
            C2SConfigCompletePacket.TYPE, C2SConfigCompletePacket.CODEC,
            C2SConfigCompletePacket::handle
        );

        PlatformHelper.getInstance().onServerConfigurationStart((control, server) -> {
            if (control.canSend(S2CConfigStartPacket.TYPE)) {
                control.addTask(new ImmPtlConfigurationTask());
            }
            else {
                if (server != null && server.isDedicatedServer()) {
                    if (IPConfig.getConfig().serverRejectClientWithoutImmPtl) {
                        // cannot use translation key here
                        // because the translation does not exist on client without the mod
                        control.disconnect(Component.literal(
                            """
                                The server detected that client does not install Immersive Portals mod.
                                A server with Immersive Portals mod only works with the clients that have it.

                                (Note: The networking sync may be interfered by Essential mod or other mods. When you are using these mods, the detection may malfunction. In this case, you can disable networking check in the server side by changing `serverRejectClientWithoutImmPtl` to `false` in the server's config file `config/immersive_portals.json` and restart.)
                                """
                        ));
                    }
                    else {
                        GameProfile gameProfile = control.gameProfile();

                        LOGGER.warn(
                            "Channel sync detected that client does not install ImmPtl. {} {}",
                            gameProfile.name(), gameProfile.id()
                        );
                    }
                }
                else {
                    LOGGER.error("ImmPtl configuration channel is non-sendable in integrated server. Sendable channel sync is interfered.");
                }
            }
        });
    }

    @Environment(EnvType.CLIENT)
    public static void initClient() {
        // NF-PARITY W12: the S2C payload registration (type + client receiver) moved to
        // init() — one combined seam call on both dists; the binding guards the client half.

        // NF-PARITY W9: NeoForge binding fires this reset at LoggingOut instead of login-INIT (see ClientPlatform.onNewConnectionStateReset javadoc)
        ClientPlatform.get().onNewConnectionStateReset(
            () -> {
                LOGGER.info("Client login init");
                // if the config packet is not received,
                // serverProtocolInfo will always be nul
                // it will become not null when receiving ImmPtl config packet
                serverVersion = null;
            }
        );

        ClientPlatform.get().onClientPlayJoin(handler -> { // NF-PARITY W9
            onClientJoin();
        });
    }
    
    private static void onClientJoin() {
        if (serverVersion == null) {
            warnServerMissingImmPtl();
        }
        else {
            if (serverVersion.isNormalVersion() &&
                immPtlVersion.isNormalVersion() &&
                !serverVersion.equals(immPtlVersion)
            ) {
                if (IPConfig.getConfig().shouldDisplayWarning("mod_version_mismatch")) {
                    MutableComponent text =
                        Component.translatable(
                            "imm_ptl.mod_patch_version_mismatch",
                            Component.literal(serverVersion.toString())
                                .withStyle(ChatFormatting.GOLD),
                            Component.literal(immPtlVersion.toString())
                                .withStyle(ChatFormatting.GOLD)
                        ).append(
                            IPMcHelper.getDisableWarningText("mod_version_mismatch")
                        );
                    CHelper.printChat(text);
                }
            }
        }
    }
    
    // used on client
    private static @Nullable ImmPtlNetworkConfig.ModVersion serverVersion = null;
    
    // should be called from client
    public static boolean doesServerHaveImmPtl() {
        return serverVersion != null;
    }
    
    private static void warnServerMissingImmPtl() {
        Minecraft.getInstance().execute(() -> {
            CHelper.printChat(Component.translatable("imm_ptl.server_missing_immptl"));
        });
    }
}
