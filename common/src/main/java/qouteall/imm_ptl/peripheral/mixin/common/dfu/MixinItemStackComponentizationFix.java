package qouteall.imm_ptl.peripheral.mixin.common.dfu;

import com.google.common.collect.ImmutableMap;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.OptionalDynamic;
import net.minecraft.util.datafix.fixes.ItemStackComponentizationFix;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// S19 commons tail — 1:1 port of IP's MixinItemStackComponentizationFix (no source delta). 26.2 target
// verified: the private static fixItemStack(ItemStackData, Dynamic<?>) still exists
// (ItemStackComponentizationFix.java:106), so the @Inject(RETURN) anchor + 2-arg descriptor are
// unchanged; ItemStackData.is/removeTag/setComponent/moveTagToComponent remain public
// (ItemStackComponentizationFix.java:799,734,740,760). ItemStackData is still a `private static class`
// (:709) — the compile-time access is restored by the paired seamlessportals.accesswidener /
// accesstransformer.cfg `accessible class ...$ItemStackData` entries (IP carries the same widen).
//
// D3 GATING: this datafix WEAVES IN BOTH FLAG STATES via the D3_UNCONDITIONAL_ITEM_DATAFIX carve-out
// in SeamlessMixinConfigPlugin — it is the on-load counterpart of the unconditionally-registered
// wand/command-stick DataComponentTypes (port-note §1.1). See that carve-out's javadoc for the full
// rationale (shipping default is flag-OFF, so gating it flag-ON would skip the datafix on the primary
// legacy-migration path).
@Mixin(ItemStackComponentizationFix.class)
public class MixinItemStackComponentizationFix {
    @Unique
    private static final Logger LOGGER = LogManager.getLogger("iPortal_DFU");

    @Inject(method = "fixItemStack", at = @At("RETURN"))
    private static void onFixItemStack(
        ItemStackComponentizationFix.ItemStackData itemStackData, Dynamic<?> tag, CallbackInfo ci
    ) {
        if (itemStackData.is("immersive_portals:command_stick")) {
            var commandTag = itemStackData.removeTag("command").result();
            var nameTranslationKeyTag =
                itemStackData.removeTag("nameTranslationKey").result();
            var descriptionTranslationKeysTag =
                itemStackData.removeTag("descriptionTranslationKeys").result();

            if (commandTag.isEmpty() || nameTranslationKeyTag.isEmpty() || descriptionTranslationKeysTag.isEmpty()) {
                LOGGER.error("Broken command stick item data {} {} {}", commandTag, nameTranslationKeyTag, descriptionTranslationKeysTag);
            } else {
                Dynamic<?> componentData = tag.createMap(
                    ImmutableMap.of(
                        tag.createString("command"),
                        commandTag.get(),
                        tag.createString("nameTranslationKey"),
                        nameTranslationKeyTag.get(),
                        tag.createString("descriptionTranslationKeys"),
                        descriptionTranslationKeysTag.get()
                    )
                );
                itemStackData.setComponent(
                    "iportal:command_stick_data", componentData
                );
                LOGGER.info("Fixed command stick item data {}", componentData);
            }
        }

        if (itemStackData.is("immersive_portals:portal_wand")) {
            itemStackData.moveTagToComponent("mode", "iportal:portal_wand_data");
        }
    }
}
