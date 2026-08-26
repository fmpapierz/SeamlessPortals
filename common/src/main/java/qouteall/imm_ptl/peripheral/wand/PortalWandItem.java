package qouteall.imm_ptl.peripheral.wand;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.Codec;
import com.warwa.seamlessportals.platform.ClientPlatform;
import com.warwa.seamlessportals.platform.Platform;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class PortalWandItem extends Item {
    // 26.2: Item.Properties requires the registry id at CONSTRUCTION (Item ctor →
    // effectiveDescriptionId → itemIdOrThrow "Item id not set", Item.java:135,641) — the same
    // 26.2-forced setId pattern as PeripheralModMain's portal_helper item.
    public static final PortalWandItem instance = new PortalWandItem(
        new Properties().setId(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.ITEM,
            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "immersive_portals", "portal_wand")
        ))
    );

    /**
     * S19 D3 split of IP's single init(): the DataComponentType is PERSISTED ON SAVED STACKS,
     * so its registration must be identical in both flag states (a world saved flag-ON with a
     * wand in a chest must load flag-OFF without the stack failing to parse) — it rides the
     * UNCONDITIONAL registry seam, same discipline as the item/block registrations. The event
     * registrations below stay in init() (flag-ON behavior).
     */
    public static void registerDataComponents() {
        Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            "iportal:portal_wand_data",
            COMPONENT_TYPE
        );
    }

    public static void init() {
        // NF-PARITY W9 (handler returns false to cancel)
        Platform.get().onAttackBlock((player, world, hand, pos, direction) -> {
            // cannot break block using the wand
            return player.getMainHandItem().getItem() != instance;
        });
        
        BlockManipulationServer.canDoCrossPortalInteractionEvent.register(p -> {
            return p.getMainHandItem().getItem() != instance;
        });
    }
    
    public static void initClient() {
        ClientPlatform.get().onClientTickEnd(client -> { // NF-PARITY W9
            if (client.player != null) {
                ItemStack itemStack = client.player.getMainHandItem();
                if (itemStack.getItem() == instance) {
                    updateDisplay(itemStack);
                }
                else {
                    ClientPortalWandPortalCreation.clearCursorPointing();
                }
            }
            ClientPortalWandPortalDrag.tick();
        });
        
        
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(ClientPortalWandPortalCreation::reset);
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(ClientPortalWandPortalDrag::reset);
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(ClientPortalWandPortalCopy::reset);
    }
    
    public static void addIntoCreativeTag(CreativeModeTab.Output entries) {
        ItemStack w1 = new ItemStack(instance);
        w1.set(COMPONENT_TYPE, Mode.CREATE_PORTAL);
        entries.accept(w1);
        
        ItemStack w2 = new ItemStack(instance);
        w2.set(COMPONENT_TYPE, Mode.DRAG_PORTAL);
        entries.accept(w2);
        
        ItemStack w3 = new ItemStack(instance);
        w3.set(COMPONENT_TYPE, Mode.COPY_PORTAL);
        entries.accept(w3);
    }
    
    public static enum Mode {
        CREATE_PORTAL,
        DRAG_PORTAL,
        COPY_PORTAL;
        
        public static final Mode FALLBACK = CREATE_PORTAL;
        
        public static Mode fromTag(CompoundTag tag) {
            // 26.2: CompoundTag.getString(name) now returns Optional<String>; getStringOr(name, default)
            // is the direct String-returning form (CompoundTag.java:331,335). fromStr's default branch
            // already maps "" (and any unknown) to FALLBACK, so "" is the faithful empty-tag default.
            String mode = tag.getStringOr("mode", "");

            return fromStr(mode);
        }
        
        public static Mode fromStr(String mode) {
            return switch (mode) {
                case "create_portal" -> CREATE_PORTAL;
                case "drag_portal" -> DRAG_PORTAL;
                case "copy_portal" -> COPY_PORTAL;
                default -> FALLBACK;
            };
        }
        
        public Mode next() {
            return switch (this) {
                case CREATE_PORTAL -> DRAG_PORTAL;
                case DRAG_PORTAL -> COPY_PORTAL;
                case COPY_PORTAL -> CREATE_PORTAL;
            };
        }
        
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            String modeString = toStr();
            tag.putString("mode", modeString);
            return tag;
        }
        
        public String toStr() {
            return switch (this) {
                case CREATE_PORTAL -> "create_portal";
                case DRAG_PORTAL -> "drag_portal";
                case COPY_PORTAL -> "copy_portal";
            };
        }
        
        public MutableComponent getText() {
            return switch (this) {
                case CREATE_PORTAL -> Component.translatable("imm_ptl.wand.mode.create_portal");
                case DRAG_PORTAL -> Component.translatable("imm_ptl.wand.mode.drag_portal");
                case COPY_PORTAL -> Component.translatable("imm_ptl.wand.mode.copy_portal");
            };
        }
        
    }
    
    public static final Codec<Mode> MODE_CODEC = Codec.STRING.xmap(Mode::fromStr, Mode::toStr);
    
    public static final DataComponentType<Mode> COMPONENT_TYPE =
        DataComponentType.<Mode>builder()
            .persistent(MODE_CODEC)
            .build();
    
    public PortalWandItem(Properties properties) {
        super(properties);
    }
    
    @Environment(EnvType.CLIENT)
    public static void onClientLeftClick(LocalPlayer player, ItemStack itemStack) {
        if (player.isShiftKeyDown()) {
            showSettings(player);
        }
        else {
            Mode mode = itemStack.getOrDefault(COMPONENT_TYPE, Mode.FALLBACK);
            
            switch (mode) {
                case CREATE_PORTAL -> {
                    ClientPortalWandPortalCreation.onLeftClick();
                }
                case DRAG_PORTAL -> {
                    ClientPortalWandPortalDrag.onLeftClick();
                }
                case COPY_PORTAL -> {
                    ClientPortalWandPortalCopy.onLeftClick();
                }
            }
        }
    }
    
    // 26.2: Item.use returns plain InteractionResult (InteractionResultHolder<ItemStack> is GONE;
    // Item.java:189 — api-map platform-compat-peripheral §2). InteractionResultHolder.success(stack)
    // -> InteractionResult.SUCCESS; the held ItemStack payload is dropped (26.2 no longer carries it).
    @Override
    public InteractionResult use(Level world, Player player, InteractionHand hand) {
        // S19 D3 guard (NOT in IP; dies with the flag at S20): the item is registered in BOTH
        // flag states (world-save parity), so flag-OFF a /give'd wand must be INERT — its client
        // state machines + RPC chain would otherwise create entity portals on the block-era
        // substrate (an untested hybrid that persists into the save). The server-side mirror
        // guard lives in PortalWandInteraction.checkPermission.
        if (!com.warwa.seamlessportals.EntityPortalsFlag.isOn()) {
            return InteractionResult.PASS;
        }

        ItemStack itemStack = player.getItemInHand(hand);
        Mode mode = itemStack.getOrDefault(COMPONENT_TYPE, Mode.FALLBACK);

        if (player.isShiftKeyDown()) {
            if (!world.isClientSide()) {
                if (!PortalWandInteraction.isDragging(((ServerPlayer) player))) {
                    Mode nextMode = mode.next();
                    itemStack.set(COMPONENT_TYPE, nextMode);
                    return InteractionResult.SUCCESS;
                }
            }
        }
        
        if (!player.isShiftKeyDown()) {
            if (world.isClientSide()) {
                onUseClient(mode);
            }
        }
        
        return super.use(world, player, hand);
    }
    
    @Environment(EnvType.CLIENT)
    private void onUseClient(Mode mode) {
        switch (mode) {
            case CREATE_PORTAL -> {
                ClientPortalWandPortalCreation.onRightClick();
            }
            case DRAG_PORTAL -> {
                ClientPortalWandPortalDrag.onRightClick();
            }
            case COPY_PORTAL -> {
                ClientPortalWandPortalCopy.onRightClick();
            }
        }
    }
    
    // 26.2: Item.appendHoverText gained a TooltipDisplay param and emits through a Consumer<Component>
    // builder instead of List<Component> (Item.java:322 — api-map platform-compat-peripheral §2).
    // tooltip.add(x) -> tooltip.accept(x).
    @Environment(EnvType.CLIENT)
    @Override
    public void appendHoverText(
        ItemStack stack, Item.TooltipContext tooltipContext,
        TooltipDisplay tooltipDisplay, Consumer<Component> tooltip, TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, tooltipContext, tooltipDisplay, tooltip, tooltipFlag);

        tooltip.accept(Component.translatable(
            "imm_ptl.wand.item_desc_1",
            Minecraft.getInstance().options.keyShift.getTranslatedKeyMessage(),
            Minecraft.getInstance().options.keyUse.getTranslatedKeyMessage()
        ));
        tooltip.accept(Component.translatable(
            "imm_ptl.wand.item_desc_2",
            Minecraft.getInstance().options.keyShift.getTranslatedKeyMessage(),
            Minecraft.getInstance().options.keyAttack.getTranslatedKeyMessage()
        ));
    }
    
    @Override
    public Component getName(ItemStack stack) {
        Mode mode = stack.getOrDefault(COMPONENT_TYPE, Mode.FALLBACK);
        
        MutableComponent baseText = Component.translatable("item.immersive_portals.portal_wand");
        
        return baseText
            .append(Component.literal(" : "))
            .append(mode.getText().withStyle(ChatFormatting.GOLD));
    }
    
    public static void showSettings(Player player) {
        player.sendSystemMessage(Component.translatable("imm_ptl.wand.settings_1"));
        player.sendSystemMessage(Component.translatable("imm_ptl.wand.settings_alignment"));
        
        int[] alignments = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 16, 32, 64};
        
        List<MutableComponent> alignmentSettingTexts = new ArrayList<>();
        for (int alignment : alignments) {
            MutableComponent textWithCommand = IPMcHelper.getTextWithCommand(
                Component.literal("1/" + alignment),
                "/imm_ptl_client_debug wand set_cursor_alignment " + alignment
            );
            alignmentSettingTexts.add(textWithCommand);
        }
        
        alignmentSettingTexts.add(IPMcHelper.getTextWithCommand(
            Component.translatable("imm_ptl.wand.no_alignment"),
            "/imm_ptl_client_debug wand set_cursor_alignment 0"
        ));
        
        player.sendSystemMessage(
            alignmentSettingTexts.stream().reduce(Component.literal(""), (a, b) -> a.append(" ").append(b))
        );
        
        player.sendSystemMessage(Component.translatable(
            "imm_ptl.wand.settings_2", Minecraft.getInstance().options.keyChat.getTranslatedKeyMessage()
        ));
    }
    
    private static boolean instructionInformed = false;
    
    @Environment(EnvType.CLIENT)
    private static void updateDisplay(ItemStack itemStack) {
        Mode mode = itemStack.getOrDefault(COMPONENT_TYPE, Mode.FALLBACK);
        
        switch (mode) {
            case CREATE_PORTAL -> ClientPortalWandPortalCreation.updateDisplay();
            case DRAG_PORTAL -> ClientPortalWandPortalDrag.updateDisplay();
            case COPY_PORTAL -> ClientPortalWandPortalCopy.updateDisplay();
        }
    }
    
    // S19-A2 — IP's dispatcher restored (the S13-A shell is closed). 26.2-forced (F6): IP's
    // MultiBufferSource.BufferSource param becomes the ONE VertexConsumer the caller
    // (MixinLevelRenderer_PortalWand's single submitCustomGeometry on RenderTypes.lines())
    // hands us at execute time — the per-mode bodies' RenderType.lines()/debugLineStrip(1)
    // getBuffer fetches collapse onto it (debugLineStrip is GONE on 26.2; strip primitives
    // re-expressed as discrete lines — see WireRenderingHelper.renderCircle + the
    // renderPlane isLineStrip=false flips). Dispatch + instructionInformed are IP-verbatim.
    @Environment(EnvType.CLIENT)
    public static void clientRender(
        LocalPlayer player, ItemStack itemStack, PoseStack poseStack,
        VertexConsumer vertexConsumer,
        double camX, double camY, double camZ
    ) {
        if (!instructionInformed) {
            instructionInformed = true;
        }

        Mode mode = itemStack.getOrDefault(COMPONENT_TYPE, Mode.FALLBACK);

        switch (mode) {
            case CREATE_PORTAL -> ClientPortalWandPortalCreation.render(
                poseStack, vertexConsumer, camX, camY, camZ
            );
            case DRAG_PORTAL -> ClientPortalWandPortalDrag.render(
                poseStack, vertexConsumer, camX, camY, camZ
            );
            case COPY_PORTAL -> ClientPortalWandPortalCopy.render(
                poseStack, vertexConsumer, camX, camY, camZ
            );
        }
    }
    
}
