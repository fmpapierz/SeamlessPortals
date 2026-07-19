package qouteall.imm_ptl.peripheral.dim_stack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.Consumer;

public class SelectDimensionScreen extends Screen {
    public final DimStackScreen parent;
    private DimListWidget dimListWidget;
    private Button confirmButton;
    private final Consumer<ResourceKey<Level>> outerCallback;
    private final List<ResourceKey<Level>> dimensionList;
    
    protected SelectDimensionScreen(
        DimStackScreen parent,
        Consumer<ResourceKey<Level>> callback,
        List<ResourceKey<Level>> dimensionList
    ) {
        super(Component.translatable("imm_ptl.select_dimension"));
        this.parent = parent;
        this.outerCallback = callback;
        this.dimensionList = dimensionList;
    }
    
    @Override
    protected void init() {
        dimListWidget = new DimListWidget(
            width,
            height - 20 - 40,
            20,
            DimEntryWidget.widgetHeight,
            this,
            DimListWidget.Type.addDimensionList,
            null
        );
        addWidget(dimListWidget);
        
        Consumer<DimEntryWidget> callback = w -> dimListWidget.setSelected(w);
        
        for (ResourceKey<Level> dim : dimensionList) {
            // S19-C 26.2: children() is unmodifiable now — portal_children() is the mutable view
            dimListWidget.portal_children().add(new DimEntryWidget(dim, dimListWidget, callback, new DimStackEntry(dim)));
        }
    
        confirmButton = (Button) addRenderableWidget(Button
            .builder(
                Component.translatable("imm_ptl.confirm_select_dimension"),
                (buttonWidget) -> {
                    DimEntryWidget selected = dimListWidget.getSelected();
                    if (selected == null) {
                        return;
                    }
                    // 26.2: Minecraft.setScreen moved to Minecraft.gui.setScreen (Minecraft.java:290).
                    Minecraft.getInstance().gui.setScreen(parent);
                    outerCallback.accept(selected.dimension);
                }
            )
            .pos(this.width / 2 - 75, this.height - 28)
            .size(150, 20)
            .build());
    }
    
    @Override
    public void onClose() {
        // When `esc` is pressed return to the parent screen rather than setting screen to `null` which returns to the main menu.
        // 26.2: Minecraft.setScreen moved to Minecraft.gui.setScreen (Minecraft.java:290).
        this.minecraft.gui.setScreen(this.parent);
    }

    // 26.2 GUI SHELL (S19-C1 — body live): Screen.render(GuiGraphics, mouseX, mouseY, delta) became
    // extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, a) (Screen.java:116) under the extract
    // render model (drawCenteredString -> centeredText; widget draw via extractWidgetRenderState). The
    // original body (super render + dim list + centered title) is restored below, re-expressed against
    // the 26.2 extractor.
    // S19-C1 — IP body restored (26.2 extract-model re-expression; see port-note S19 §5)
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        dimListWidget.extractRenderState(graphics, mouseX, mouseY, a);

        graphics.centeredText(
            this.font, this.title.getString(), this.width / 2, 10, -1
        );
    }
}
