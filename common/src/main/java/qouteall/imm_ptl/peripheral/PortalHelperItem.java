package qouteall.imm_ptl.peripheral;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

// S16 1:1 port of IP:peripheral/PortalHelperItem.java:1-51. Named 26.2 adaptation:
// appendHoverText signature gained TooltipDisplay + Consumer<Component> (was List<Component>,
// mc262 Item.java:322) — list.add becomes builder.accept, behavior identical.
public class PortalHelperItem extends BlockItem {
    private static boolean deprecationInformed = false;

    public PortalHelperItem(Block block, Properties settings) {
        super(block, settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) {
            if (context.getPlayer() != null) {
                if (!deprecationInformed) {
                    deprecationInformed = true;
                    context.getPlayer().sendSystemMessage(
                        Component.translatable(
                            "imm_ptl.portal_helper_deprecated",
                            Component.literal("/portal shape sculpt")
                                .withStyle(ChatFormatting.GOLD)
                        )
                    );
                }
            }
        }

        return super.useOn(context);
    }

    @Override
    public void appendHoverText(
        ItemStack itemStack, Item.TooltipContext tooltipContext,
        TooltipDisplay tooltipDisplay, Consumer<Component> builder, TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(itemStack, tooltipContext, tooltipDisplay, builder, tooltipFlag);

        builder.accept(Component.translatable("imm_ptl.portal_helper_tooltip"));
    }
}
