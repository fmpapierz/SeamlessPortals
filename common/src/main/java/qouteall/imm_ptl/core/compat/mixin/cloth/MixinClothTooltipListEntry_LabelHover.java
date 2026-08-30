package qouteall.imm_ptl.core.compat.mixin.cloth;

import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * CONFIG-SCREEN TOOLTIP HOVER SCOPE (2026-08-30, user order): show a config entry's tooltip only
 * while the cursor is over the SETTING NAME text, never over the rest of the row (buttons,
 * sliders, text boxes, reset).
 *
 * <p>javap-verified against cloth-config 26.2.155: {@code TooltipListEntry.extractRenderState
 * (GuiGraphicsExtractor, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta)}
 * queues the tooltip iff {@code isMouseInside(mouseX, mouseY, x, y, entryWidth, entryHeight)} —
 * a WHOLE-ROW test (the invokevirtual at offset 33; owner {@code TooltipListEntry}, descriptor
 * {@code (IIIIII)Z}). This redirect keeps that row test and narrows the X range to the rendered
 * label width ({@code getDisplayedFieldName()}, public on {@code AbstractConfigEntry}) plus a
 * small slack. Subclass overrides that call {@code super.extractRenderState} inherit the scoping.
 *
 * <p>{@code remap = false}: every name in the target descriptor is cloth's own (unobfuscated).
 * Gated by {@code IPCompatMixinPlugin}'s Cloth arm (simple name carries {@code "Cloth"} — the
 * config's substring footgun) + the entity-portal master switch; the tooltip-bearing screen is
 * the flag-ON IP cloth screen, so the gate matches the feature exactly.
 */
@Mixin(value = TooltipListEntry.class, remap = false)
public abstract class MixinClothTooltipListEntry_LabelHover {

    @Redirect(
        method = "extractRenderState",
        at = @At(
            value = "INVOKE",
            target = "Lme/shedaniel/clothconfig2/gui/entries/TooltipListEntry;isMouseInside(IIIIII)Z"
        )
    )
    private boolean seamlessportals$tooltipOnlyOverLabel(
        TooltipListEntry<?> self,
        int mouseX, int mouseY, int x, int y, int entryWidth, int entryHeight
    ) {
        if (!self.isMouseInside(mouseX, mouseY, x, y, entryWidth, entryHeight)) {
            return false;
        }
        int labelWidth = Minecraft.getInstance().font.width(self.getDisplayedFieldName());
        return mouseX >= x && mouseX < x + labelWidth + 4;
    }
}
