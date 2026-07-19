package qouteall.imm_ptl.peripheral.dim_stack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.Helper;

public class DimListWidget extends AbstractSelectionList<DimEntryWidget> {
    
    public static final int ROW_WIDTH = 300;
    
    public static interface DraggingCallback {
        void run(int selectedIndex, int mouseOnIndex);
    }
    
    public final Screen parent;
    private final Type type;
    @Nullable
    private final DraggingCallback draggingCallback;
    
    
    public static enum Type {
        mainDimensionList, addDimensionList
    }
    
    public DimListWidget(
        int width,
        int height,
        int top,
        int itemHeight,
        Screen parent,
        Type type,
        @Nullable DraggingCallback draggingCallback
    ) {
        super(Minecraft.getInstance(), width, height, top, itemHeight);
        this.parent = parent;
        this.type = type;
        this.draggingCallback = draggingCallback;
    }
    
    // 26.2: GuiEventListener.mouseDragged(double, double, int, double, double) became
    // mouseDragged(MouseButtonEvent, double dx, double dy) (AbstractContainerWidget:72); the pointer
    // position is now event.x()/event.y() (MouseButtonEvent record).
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (type == Type.mainDimensionList && draggingCallback != null) {
            DimEntryWidget selected = getSelected();

            if (selected != null) {
                DimEntryWidget mouseOn = getEntryAtPosition(event.x(), event.y());
                if (mouseOn != null) {
                    if (mouseOn != selected) {
                        int selectedIndex = children().indexOf(selected);
                        int mouseOnIndex = children().indexOf(mouseOn);
                        if (selectedIndex != -1 && mouseOnIndex != -1) {
                            draggingCallback.run(selectedIndex, mouseOnIndex);
                        }
                        else {
                            Helper.err("Invalid dragging");
                        }
                    }
                }
            }
        }

        return super.mouseDragged(event, dx, dy);
    }
    
    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
    
    }
    
    // make it wider
    @Override
    public int getRowWidth() {
        return ROW_WIDTH;
    }
    
    // 26.2: AbstractSelectionList.getScrollbarPosition() was renamed scrollBarX()
    // (AbstractSelectionList.java:292).
    @Override
    protected int scrollBarX() {
        return (width - ROW_WIDTH) / 2 + ROW_WIDTH;
    }

    // 26.2: renderListBackground(GuiGraphics) became extractListBackground(GuiGraphicsExtractor)
    // (AbstractSelectionList.java:229) under the extract render model.
    @Override
    protected void extractListBackground(GuiGraphicsExtractor graphics) {
        // don't render background
    }

    /**
     * S19-C 26.2-forced (see {@link
     * qouteall.imm_ptl.peripheral.mixin.client.dim_stack.IEAbstractSelectionList}): the
     * MUTABLE children view IP's controller code was written against — 1.21.3 children()
     * was the mutable TrackedList; 26.2 wraps it unmodifiable. Every mutation delegates to
     * the real TrackedList (keeping its bindEntryToSelf bookkeeping) and then repositions
     * entries (26.2 caches entry x/y; 1.21.3 computed positions per-frame). IP call sites
     * change one token: children() → portal_children().
     */
    public java.util.List<DimEntryWidget> portal_children() {
        return new MutableChildrenView();
    }

    private class MutableChildrenView extends java.util.AbstractList<DimEntryWidget> {
        @SuppressWarnings("unchecked")
        private java.util.List<DimEntryWidget> backing() {
            return (java.util.List<DimEntryWidget>) ((qouteall.imm_ptl.peripheral.mixin.client
                .dim_stack.IEAbstractSelectionList) DimListWidget.this).ip_getMutableChildren();
        }

        private void reposition() {
            ((qouteall.imm_ptl.peripheral.mixin.client.dim_stack.IEAbstractSelectionList)
                DimListWidget.this).ip_invokeRepositionEntries();
        }

        @Override
        public DimEntryWidget get(int index) {
            return backing().get(index);
        }

        @Override
        public int size() {
            return backing().size();
        }

        @Override
        public DimEntryWidget set(int index, DimEntryWidget element) {
            // Live-round fix 2026-07-18 round 2: vanilla addEntry is the ONLY height setter
            // (repositionEntries staggers y by getHeight() but sets just y/x/width,
            // AbstractSelectionList:146-155) — a raw-list insert leaves height 0, so every
            // row landed at the same y (the overlaid-text symptom). Replicate addEntry's
            // height init from the protected defaultEntryHeight (:40).
            element.setHeight(defaultEntryHeight);
            DimEntryWidget prev = backing().set(index, element);
            reposition();
            return prev;
        }

        @Override
        public void add(int index, DimEntryWidget element) {
            element.setHeight(defaultEntryHeight);  // see set() — vanilla addEntry parity
            backing().add(index, element);
            reposition();
        }

        @Override
        public DimEntryWidget remove(int index) {
            DimEntryWidget prev = backing().remove(index);
            reposition();
            return prev;
        }
    }
}
