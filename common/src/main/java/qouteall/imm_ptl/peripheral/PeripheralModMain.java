package qouteall.imm_ptl.peripheral;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration;

import java.util.function.BiConsumer;

/**
 * S16 MINIMAL-SUBSET port of IP:peripheral/PeripheralModMain.java — ONLY the
 * portal-generation cargo (portal-helper block/item + {@link IntrinsicPortalGeneration}).
 *
 * <p>DELIBERATELY HELD OUT to S19 per S13B §7.2 + the C1 decision (2026-07-18: S19 WILL be
 * built, so every held item has a guaranteed landing): FormulaGenerator, DimStackManagement,
 * AlternateDimensions init, DimensionAPI.suppressExperimentalWarningForNamespace,
 * PortalWandItem, CommandStickItem, PortalWandInteraction, the creative-mode TAB (IP :40-51 —
 * the portal_helper item is /give-only until S19), initClient (IPOuterClientMisc + wand
 * client), and IP's remaining peripheral mixins — explicitly including
 * {@code MixinEnderEyeItem_CVB} (end-portal CVB; verify fold: named here so end-portal
 * behavior isn't dropped silently) + the alternate_dimension/dfu/dim_stack commons and the 8
 * peripheral client mixins. Each omission is a port-structure deviation from IP's single-init
 * shape, plan-sanctioned (EXECUTION_PLAN §S16(a) scopes S16 to the generation pipeline),
 * named in port-note S16. IP's client {@code BlockRenderLayerMap...cutout()} registration for
 * the helper block is 26.2-OBSOLETE (render layers are sprite-derived:
 * {@code ChunkSectionLayer} picks CUTOUT from texture transparency; ItemBlockRenderTypes is
 * gone) — the assets alone carry it.
 *
 * <p>Named 26.2-forced adaptations (port-note S16): FabricBlockSettings.of() is GONE →
 * {@code BlockBehaviour.Properties.of()...setId(...)} per the shipped PortalPlaceholderBlock
 * pattern (26.2 requires the id ON the Properties or the Block ctor throws "Block id not
 * set"); the BlockItem's Item.Properties likewise needs setId + useBlockDescriptionPrefix
 * (vanilla Items.registerBlock pattern, mc262 Items.java:2116).
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

    public static void init() {
        IntrinsicPortalGeneration.init();
    }

    public static void registerItems(BiConsumer<Identifier, Item> regFunc) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "portal_helper"),
            portalHelperBlockItem
        );
    }

    public static void registerBlocks(BiConsumer<Identifier, Block> regFunc) {
        regFunc.accept(
            McHelper.newResourceLocation("immersive_portals", "portal_helper"),
            portalHelperBlock
        );
    }
}
