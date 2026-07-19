package qouteall.imm_ptl.peripheral;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration;
import qouteall.imm_ptl.peripheral.wand.ClientPortalWandPortalDrag;
import qouteall.imm_ptl.peripheral.wand.PortalWandInteraction;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

import java.util.function.BiConsumer;

/**
 * S16 landed the MINIMAL portal-generation subset; S19-A extends it with IP's WAND cargo:
 * portal_wand + command_stick item registration, the creative-mode TAB (retires the
 * portal-helper /give-only state), PortalWandItem/CommandStickItem/PortalWandInteraction
 * init, initClient (IPOuterClientMisc + wand client). S19-C adds the dim-stack runtime
 * (DimStackManagement.init + the dim_stack common/client mixins + the create-world entry).
 * Still held per S13B §7.2 + the C1 decision: FormulaGenerator, AlternateDimensions init +
 * DimensionAPI.suppressExperimentalWarningForNamespace + registerChunkGenerators/
 * registerBiomeSources (S19-D — R13g-gated alternate dims), and the dfu common
 * (MixinItemStackComponentizationFix — the wand/stick legacy datafixer, lands with the
 * peripheral-commons tail). Each remaining omission stays a plan-sanctioned port-structure
 * deviation named in the S19 port-note.
 *
 * <p>Named 26.2-forced adaptations (port-note S16 + S19): FabricBlockSettings.of() is GONE →
 * {@code BlockBehaviour.Properties.of()...setId(...)} per the shipped PortalPlaceholderBlock
 * pattern (26.2 requires the id ON the Properties or the Block ctor throws "Block id not
 * set"); the BlockItem's Item.Properties likewise needs setId + useBlockDescriptionPrefix
 * (vanilla Items.registerBlock pattern, mc262 Items.java:2116). IP's client
 * {@code BlockRenderLayerMap...cutout()} registration for the helper block is 26.2-OBSOLETE
 * (render layers are sprite-derived: {@code ChunkSectionLayer} picks CUTOUT from texture
 * transparency; ItemBlockRenderTypes is gone) — the assets alone carry it. The creative TAB:
 * IP's {@code FabricItemGroup.builder()} (fabric-item-group-api-v1) does not exist on 26.2 —
 * fabric-api 0.152.1+26.2 replaces it with {@code FabricCreativeModeTab.builder()}
 * (fabric-creative-tab-api-v1), the 1:1 successor (same builder contract, Fabric-managed
 * row/column placement).
 *
 * <p>Registration seams (D3): blocks/items/data-components register UNCONDITIONALLY
 * (world-save parity — saved stacks carry the data components); the TAB registers FLAG-ON
 * only (tabs are not world state, and flag-OFF must not surface entity-portal features);
 * init()/initClient() run flag-ON only. See SeamlessPortalsModFabric.
 */
public class PeripheralModMain {

    public static final Block portalHelperBlock = new Block(
        BlockBehaviour.Properties.of()
            .noOcclusion()
            .isRedstoneConductor((a, b, c) -> false)
            .setId(ResourceKey.create(
                Registries.BLOCK,
                Identifier.fromNamespaceAndPath("immersive_portals", "portal_helper")
            ))
    );

    public static final BlockItem portalHelperBlockItem = new PortalHelperItem(
        portalHelperBlock,
        new Item.Properties()
            .setId(ResourceKey.create(
                Registries.ITEM,
                Identifier.fromNamespaceAndPath("immersive_portals", "portal_helper")
            ))
            .useBlockDescriptionPrefix()
    );

    // IP PeripheralModMain:40-51 — icon = wand, title = imm_ptl.item_group (lang key already
    // shipped), contents = 3 wand mode-variants, the built-in command sticks, portal helper.
    // FabricCreativeModeTab.builder() = the 26.2 successor of FabricItemGroup.builder().
    public static final CreativeModeTab TAB = FabricCreativeModeTab.builder()
        .icon(() -> new ItemStack(PortalWandItem.instance))
        .title(Component.translatable("imm_ptl.item_group"))
        .displayItems((parameters, entries) -> {
            PortalWandItem.addIntoCreativeTag(entries);
            CommandStickItem.addIntoCreativeTag(entries);
            entries.accept(PeripheralModMain.portalHelperBlockItem);
        })
        .build();

    public static void init() {
        // S19-D: IP init order restored (IP PeripheralModMain:62-71) — FormulaGenerator
        // FIRST (seeds the chaos-terrain expression selectors before any generator use).
        qouteall.imm_ptl.peripheral.alternate_dimension.FormulaGenerator.init();

        IntrinsicPortalGeneration.init();

        // S19-C: dim-stack runtime (IP init order — DimStackManagement sits between
        // IntrinsicPortalGeneration and the wand inits, IP PeripheralModMain:66-67; the
        // dimension-load-event registration + the dedicated-server preset path live inside).
        qouteall.imm_ptl.peripheral.dim_stack.DimStackManagement.init();

        // S19-D: alternate dims (IP :69) — registers the dim-stack candidate list, the
        // PRE_UPDATE fan-out that creates referenced alt dims inside the dimlib load window,
        // the weather-sync tick, and the dimension templates. Then IP :71 — the
        // experimental-warning namespace suppression (consumed by the dimlib
        // MixinWorldDimensions chain).
        qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions.init();
        qouteall.dimlib.api.DimensionAPI.suppressExperimentalWarningForNamespace(
            "immersive_portals");

        // S19-A: IP's init order (PeripheralModMain:76-80) — wand + command stick + wand
        // interaction; registerCommandStickTypes LAST (displayItems runs lazily at GUI-open,
        // so the tab always sees the populated map). The DataComponentType halves of the IP
        // init() bodies ride the unconditional seam instead (registerDataComponents — D3).
        PortalWandItem.init();
        CommandStickItem.init();
        PortalWandInteraction.init();
        CommandStickItem.registerCommandStickTypes();
    }

    /**
     * S19-A / IP PeripheralModMain.initClient() (:53-60) minus nothing — all three entries
     * landed: IPOuterClientMisc, the wand client-tick driver, the drag animation signal.
     */
    @Environment(EnvType.CLIENT)
    public static void initClient() {
        IPOuterClientMisc.initClient();
        PortalWandItem.initClient();
        ClientPortalWandPortalDrag.init();
    }

    /** D3-unconditional: stack-persisted DataComponentTypes must exist in both flag states. */
    public static void registerDataComponents() {
        PortalWandItem.registerDataComponents();
        CommandStickItem.registerDataComponents();
    }

    public static void registerItems(BiConsumer<Identifier, Item> regFunc) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "portal_helper"),
            portalHelperBlockItem
        );
        // S19-A: the remaining two IP items (IP PeripheralModMain:89-97) — registered
        // unconditionally (D3 world-save parity), obtainable only via the flag-ON TAB or /give.
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "command_stick"),
            CommandStickItem.instance
        );
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "portal_wand"),
            PortalWandItem.instance
        );
    }

    public static void registerBlocks(BiConsumer<Identifier, Block> regFunc) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "portal_helper"),
            portalHelperBlock
        );
    }

    // IP PeripheralModMain:129-136 (id immersive_portals:general). FLAG-ON only (see header).
    public static void registerCreativeTabs(BiConsumer<Identifier, CreativeModeTab> regFunc) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "general"),
            TAB
        );
    }

    // S19-D / IP PeripheralModMain:107-127 — the alt-dim generator/biome-source codecs.
    // UNCONDITIONAL seam (D3 save-parity): level.dat serializes each dimension's generator
    // through these codecs; a flag-ON-created alt-dim world must deserialize flag-OFF.
    // 26.2: the registries dispatch on MapCodec (ChunkGenerator.codec()/BiomeSource.codec()
    // return MapCodec — recon wf_528b5c3b-7d1).
    public static void registerChunkGenerators(
        BiConsumer<Identifier, com.mojang.serialization.MapCodec<
            ? extends net.minecraft.world.level.chunk.ChunkGenerator>> regFunc
    ) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "error_terrain_generator"),
            qouteall.imm_ptl.peripheral.alternate_dimension.ErrorTerrainGenerator.MAP_CODEC
        );
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "normal_skyland_generator"),
            qouteall.imm_ptl.peripheral.alternate_dimension.NormalSkylandGenerator.MAP_CODEC
        );
    }

    public static void registerBiomeSources(
        BiConsumer<Identifier, com.mojang.serialization.MapCodec<
            ? extends net.minecraft.world.level.biome.BiomeSource>> regFunc
    ) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "chaos_biome_source"),
            qouteall.imm_ptl.peripheral.alternate_dimension.ChaosBiomeSource.MAP_CODEC
        );
    }
}
