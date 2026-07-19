package qouteall.imm_ptl.peripheral.mixin.client.dim_stack;

import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * S19-C 26.2-forced NEW accessor mixin (recon wf_9aecac4e-330 landmine): on 1.21.3
 * {@code AbstractSelectionList.children()} returned the MUTABLE TrackedList and IP's
 * dim-stack controller mutated it by index (add/remove/set); on 26.2 {@code children()} is
 * final + {@code Collections.unmodifiableList} (AbstractSelectionList.java:72-74) — every IP
 * mutation site would throw UnsupportedOperationException. The private {@code children}
 * field is still the index-capable TrackedList (its add/set run {@code bindEntryToSelf} —
 * the same bookkeeping the protected addEntry path uses, :554-563), so exposing IT restores
 * IP's exact semantics. {@code repositionEntries} must run after raw mutation (26.2 caches
 * entry x/y; 1.21.3 computed row positions per-frame) — {@code DimListWidget.portal_children()}
 * wraps both. No vanilla behavior change (accessor/invoker only).
 */
@Mixin(AbstractSelectionList.class)
public interface IEAbstractSelectionList {
    @Accessor("children")
    List<?> ip_getMutableChildren();

    @Invoker("repositionEntries")
    void ip_invokeRepositionEntries();
}
