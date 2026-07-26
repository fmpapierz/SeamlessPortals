package qouteall.imm_ptl.peripheral;

import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.commands.PortalCommand;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CommandStickItem extends Item {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static final Codec<Data> DATA_CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
            Codec.STRING.fieldOf("command").forGetter(Data::command),
            Codec.STRING.fieldOf("nameTranslationKey").forGetter(Data::nameTranslationKey),
            Codec.STRING.listOf().fieldOf("descriptionTranslationKeys")
                .forGetter(Data::descriptionTranslationKeys)
        ).apply(instance, Data::new)
    );
    
    public static final DataComponentType<Data> COMPONENT_TYPE =
        DataComponentType.<Data>builder()
            .persistent(DATA_CODEC)
            .build();
    
    public record Data(
        String command, String nameTranslationKey, List<String> descriptionTranslationKeys
    ) {
        public void serialize(CompoundTag tag) {
            tag.putString("command", command);
            tag.putString("nameTranslationKey", nameTranslationKey);
            ListTag listTag = new ListTag();
            for (String descriptionTK : descriptionTranslationKeys) {
                listTag.add(StringTag.valueOf(descriptionTK));
            }
            tag.put("descriptionTranslationKeys", listTag);
        }
        
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            serialize(tag);
            return tag;
        }
        
        public static Data deserialize(CompoundTag tag) {
            // 26.2 NBT (ducks-api-misc.md C16): CompoundTag.getString → Optional<String> (use getStringOr);
            // the (name, typeId) CompoundTag.getList overload is GONE → getListOrEmpty(name); ListTag is now
            // heterogeneous and StringTag.getAsString() → asString() : Optional<String>.
            return new Data(
                tag.getStringOr("command", ""),
                tag.getStringOr("nameTranslationKey", ""),
                tag.getListOrEmpty("descriptionTranslationKeys")
                    .stream()
                    .map(tag1 -> ((StringTag) tag1).asString().orElse(""))
                    .collect(Collectors.toList())
            );
        }
    }
    
    public static final LinkedHashMap<String, Data> BUILT_IN_COMMAND_STICK_TYPES = new LinkedHashMap<>();
    
    public static void registerBuiltInCommandStick(Data data) {
        BUILT_IN_COMMAND_STICK_TYPES.put(data.command, data);
    }
    
    // 26.2: Item.Properties requires the registry id at CONSTRUCTION (Item ctor →
    // effectiveDescriptionId → itemIdOrThrow "Item id not set", Item.java:135,641) — the same
    // 26.2-forced setId pattern as PeripheralModMain's portal_helper item.
    public static final CommandStickItem instance = new CommandStickItem(
        new Item.Properties().setId(net.minecraft.resources.ResourceKey.create(
            net.minecraft.core.registries.Registries.ITEM,
            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                "immersive_portals", "command_stick")
        ))
    );
    
    public CommandStickItem(Properties settings) {
        super(settings);
    }
    
    // display enchantment glint
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
    
    // 26.2: Item.use returns InteractionResult (InteractionResultHolder<ItemStack> is GONE; Item.java:189).
    @Override
    public InteractionResult use(Level world, Player player, InteractionHand hand) {
        doUse(player, player.getItemInHand(hand));
        return super.use(world, player, hand);
    }
    
    private void doUse(Player player, ItemStack stack) {
        if (player.level().isClientSide()) {
            return;
        }

        // S20 increment 4: the S19 D3 guard here (NOT in IP) died with the flag, as its own note
        // said it would. What it protected against was the flag-OFF state, where the permission
        // gate below was WIDE OPEN — IPGlobal.easeCommandStickPermission's raw default is TRUE and
        // its normalization to false runs only inside IPModMain.init → onConfigChanged, which
        // flag-OFF never ran. IPModMain.init is now unconditional on Fabric (and this item is not
        // registered on NeoForge at all — PeripheralModMain is Fabric-wired only), so the
        // normalization always runs and canUseCommand is the real gate again, exactly as in IP.
        if (canUseCommand(player)) {
            Data data = stack.get(COMPONENT_TYPE);
            
            if (data == null) {
                LOGGER.error("Missing component in command stick item {}", stack);
                return;
            }
            
            // 26.2: Entity.createCommandSourceStack() moved to ServerPlayer only (Player no longer declares
            // it); doUse runs server-side (isClientSide guard above), so player is a ServerPlayer. And
            // CommandSourceStack.withPermission(int) → withPermission(PermissionSet); level 2 = gamemaster =
            // LevelBasedPermissionSet.GAMEMASTER (ducks-api-misc.md G6; vanilla uses this exact set for
            // command execution, e.g. ServerFunctionManager.java:91).
            CommandSourceStack commandSource = ((ServerPlayer) player).createCommandSourceStack()
                .withPermission(LevelBasedPermissionSet.GAMEMASTER);
            
            // 26.2: Entity.getServer() is GONE; reach the server via the level (Level.getServer(),
            // Level.java:168). doUse is server-side (isClientSide guard above), so the level is a ServerLevel.
            MinecraftServer server = player.level().getServer();
            assert server != null;
            Commands commandManager = server.getCommands();
            
            String command = data.command;
            
            if (command.startsWith("/")) {
                // it seems not accepting "/" in the beginning
                command = command.substring(1);
            }
            
            commandManager.performPrefixedCommand(commandSource, command);
        }
        else {
            sendMessage(player, Component.literal("No Permission"));
        }
    }
    
    private static boolean canUseCommand(Player player) {
        if (IPGlobal.easeCommandStickPermission) {
            return true;// any player regardless of gamemode can use
        }
        else {
            // 26.2: Player.hasPermissions(int) is GONE → Player.permissions() → PermissionSet
            // (Player.java:1846); level 2 = Permissions.COMMANDS_GAMEMASTER (ducks-api-misc.md G6).
            return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || player.isCreative();
        }
    }
    
    // 26.2: Item.appendHoverText gained a TooltipDisplay param and now emits lines through a
    // Consumer<Component> builder instead of a List<Component> (Item.java:322) — tooltip.add(x) → builder.accept(x).
    @Override
    public void appendHoverText(
        ItemStack stack, Item.TooltipContext tooltipContext,
        TooltipDisplay tooltipDisplay, Consumer<Component> tooltip, TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, tooltipContext, tooltipDisplay, tooltip, tooltipFlag);

        Data data = stack.get(COMPONENT_TYPE);

        if (data == null) {
            return;
        }

        Iterable<String> splitCommand = Splitter.fixedLength(40).split(data.command);

        for (String commandPortion : splitCommand) {
            tooltip.accept(Component.literal(commandPortion).withStyle(ChatFormatting.GOLD));
        }

        for (String descriptionTranslationKey : data.descriptionTranslationKeys) {
            tooltip.accept(Component.translatable(descriptionTranslationKey).withStyle(ChatFormatting.AQUA));
        }

        tooltip.accept(Component.translatable("imm_ptl.command_stick").withStyle(ChatFormatting.GRAY));
    }

    // 26.2: Item.getDescriptionId(ItemStack) is GONE (getDescriptionId() is now final/no-arg, Item.java:330).
    // Per-stack display name is Item.getName(ItemStack) : Component (Item.java:334). IP returned the
    // per-stick nameTranslationKey; the faithful remap translates that key. Null data (defensive only) falls
    // back to the vanilla default name.
    @Override
    public @NotNull Component getName(ItemStack stack) {
        Data data = stack.get(COMPONENT_TYPE);

        if (data == null) {
            return super.getName(stack);
        }

        return Component.translatable(data.nameTranslationKey);
    }
    
    public static void sendMessage(Player player, Component message) {
        ((ServerPlayer) player).sendSystemMessage(message);
    }
    
    /**
     * S19 D3 split of IP's single init(): the DataComponentType is PERSISTED ON SAVED STACKS,
     * so its registration must be identical in both flag states (world-save parity) — it rides
     * the UNCONDITIONAL registry seam. The command-signal wiring below stays in init()
     * (flag-ON behavior). Same split as PortalWandItem.
     */
    public static void registerDataComponents() {
        Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            "iportal:command_stick_data",
            COMPONENT_TYPE
        );
    }

    public static void init() {
        PortalCommand.createCommandStickCommandSignal.connect((player, command) -> {
            ItemStack itemStack = new ItemStack(instance, 1);
            Data data = new Data(
                command, command, new ArrayList<>()
            );
            
            itemStack.set(COMPONENT_TYPE, data);
            
            player.getInventory().add(itemStack);
            player.inventoryMenu.broadcastChanges();
        });
    }
    
    public static void addIntoCreativeTag(CreativeModeTab.Output entries) {
        for (Data data : BUILT_IN_COMMAND_STICK_TYPES.values()) {
            ItemStack stack = new ItemStack(instance);
            stack.set(COMPONENT_TYPE, data);
            entries.accept(stack);
        }
    }
    
    public static void registerCommandStickTypes() {
        registerPortalSubCommandStick("delete_portal");
        registerPortalSubCommandStick("remove_connected_portals");
        registerPortalSubCommandStick("eradicate_portal_cluster");
        registerPortalSubCommandStick("complete_bi_way_bi_faced_portal");
        registerPortalSubCommandStick("complete_bi_way_portal");
        registerPortalSubCommandStick("move_portal_front", "move_portal 0.5");
        registerPortalSubCommandStick("move_portal_back", "move_portal -0.5");
        registerPortalSubCommandStick("move_portal_back_a_little", "move_portal -0.001");
        registerPortalSubCommandStick(
            "move_portal_destination_front", "move_portal_destination 0.5"
        );
        registerPortalSubCommandStick(
            "move_portal_destination_back", "move_portal_destination -0.5"
        );
        registerPortalSubCommandStick(
            "rotate_x", "rotate_portal_rotation_along x 15"
        );
        registerPortalSubCommandStick(
            "rotate_y", "rotate_portal_rotation_along y 15"
        );
        registerPortalSubCommandStick(
            "rotate_z", "rotate_portal_rotation_along z 15"
        );
        registerPortalSubCommandStick(
            "make_unbreakable", "nbt {unbreakable:true}"
        );
        registerPortalSubCommandStick(
            "make_fuse_view", "nbt {fuseView:true}"
        );
        registerPortalSubCommandStick(
            "enable_pos_adjust", "nbt {adjustPositionAfterTeleport:true}"
        );
        registerPortalSubCommandStick(
            "disable_rendering_yourself", "nbt {doRenderPlayer:false}"
        );
        registerPortalSubCommandStick(
            "enable_isometric", "debug isometric_enable 50"
        );
        registerPortalSubCommandStick(
            "disable_isometric", "debug isometric_disable"
        );
        registerPortalSubCommandStick(
            "create_5_connected_rooms", "create_connected_rooms roomSize 6 4 6 roomNumber 5"
        );
        registerPortalSubCommandStick(
            "accelerate50", "debug accelerate 50"
        );
        registerPortalSubCommandStick(
            "accelerate200", "debug accelerate 200"
        );
        registerPortalSubCommandStick(
            "reverse_accelerate50", "debug accelerate -50"
        );
        registerPortalSubCommandStick(
            "enable_gravity_change", "nbt {teleportChangesGravity:true}"
        );
        registerPortalSubCommandStick(
            "make_invisible", "nbt {isVisible:false}"
        );
        registerPortalSubCommandStick(
            "make_visible", "nbt {isVisible:true}"
        );
        registerPortalSubCommandStick(
            "disable_default_animation", "nbt {defaultAnimation:{durationTicks:0}}"
        );
        
        registerPortalSubCommandStick(
            "pause_animation", "animation pause"
        );
        registerPortalSubCommandStick(
            "resume_animation", "animation resume"
        );
        
        registerPortalSubCommandStick(
            "rotate_around_y", "animation rotate_infinitely @s 0 1 0 1.0"
        );
        registerPortalSubCommandStick(
            "rotate_randomly", "animation rotate_infinitely_random"
        );
        registerBuiltInCommandStick(
            new Data(
                "execute positioned 0.0 0.0 0.0 run portal animation rotate_infinitely @p ^0.0 ^0.0 ^1.0 1.7",
                "imm_ptl.command.rotate_around_view",
                Lists.newArrayList("imm_ptl.command_desc.rotate_around_view")
            )
        );
        registerPortalSubCommandStick(
            "expand_from_center", "animation expand_from_center 20"
        );
        registerPortalSubCommandStick(
            "clear_animation", "animation clear"
        );
        
        registerPortalSubCommandStick(
            "sculpt", "shape sculpt"
        );
        registerPortalSubCommandStick(
            "reset_shape", "shape reset"
        );
        
        registerBuiltInCommandStick(new Data(
            "/attribute @s minecraft:generic.scale modifier remove iportal:scaling",
            "imm_ptl.command.reset_iportal_scale",
            Lists.newArrayList("imm_ptl.command_desc.reset_iportal_scale")
        ));
        registerBuiltInCommandStick(new Data(
            "/attribute @s minecraft:player.block_interaction_range base set 100",
            "imm_ptl.command.long_reach",
            Lists.newArrayList("imm_ptl.command_desc.long_reach")
        ));
        registerBuiltInCommandStick(new Data(
            "/effect give @s minecraft:night_vision 9999 1 true",
            "imm_ptl.command.night_vision",
            List.of()
        ));
        registerBuiltInCommandStick(new Data(
            "/setblock ~ ~ ~ minecraft:grass_block",
            "imm_ptl.command.block_on_feet",
            List.of()
        ));
        
        registerPortalSubCommandStick(
            "goback"
        );
        registerPortalSubCommandStick(
            "show_wiki", "wiki"
        );
    }
    
    private static Data registerPortalSubCommandStick(String name) {
        return registerPortalSubCommandStick(name, name);
    }
    
    private static Data registerPortalSubCommandStick(String name, String subCommand) {
        Data data = new Data(
            "/portal " + subCommand,
            "imm_ptl.command." + name,
            Lists.newArrayList("imm_ptl.command_desc." + name)
        );
        registerBuiltInCommandStick(data);
        return data;
    }
    
}
