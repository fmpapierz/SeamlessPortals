package qouteall.imm_ptl.peripheral.dim_stack;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// extending EntryListWidget.Entry is also fine
public class DimEntryWidget extends ContainerObjectSelectionList.Entry<DimEntryWidget> {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static enum ArrowType {
        none, enabled, conflicting
    }
    
    public final ResourceKey<Level> dimension;
    public final DimListWidget parent;
    private final Consumer<DimEntryWidget> selectCallback;
    @Nullable
    private final Identifier dimIconPath;
    private final Component dimensionName;
    
    // if null, it's in select dimension screen
    // if not null, it's in dim stack screen
    @Nullable
    public final DimStackEntry entry;
    
    public int entryIndex;
    
    ArrowType arrowToPrevious = ArrowType.none;
    ArrowType arrowToNext = ArrowType.none;
    
    public final static int widgetHeight = 50;
    
    @Override
    public @NotNull List<? extends NarratableEntry> narratables() {
        return List.of();
    }
    
    public DimEntryWidget(
        ResourceKey<Level> dimension,
        DimListWidget parent,
        Consumer<DimEntryWidget> selectCallback,
        @Nullable DimStackEntry entry
    ) {
        this.dimension = dimension;
        this.parent = parent;
        this.selectCallback = selectCallback;
        
        this.dimIconPath = CHelper.getDimensionIconPath(this.dimension);
        
        this.dimensionName = McHelper.getDimensionName(dimension);
        
        this.entry = entry;
    }
    
    private final List<GuiEventListener> children = new ArrayList<>();
    
    @Override
    public @NotNull List<? extends GuiEventListener> children() {
        return children;
    }
    
    // 26.2 GUI SHELL (S19-C1 — body live): AbstractSelectionList.Entry.render(GuiGraphics, index,
    // y, x, rowWidth, itemHeight, mouseX, mouseY, hovered, delta) became
    // extractContent(GuiGraphicsExtractor, mouseX, mouseY, hovered, a) (AbstractSelectionList.java:455)
    // — the entry now knows its own bounds via getX()/getY()/getWidth()/getHeight(), and the
    // GuiGraphics immediate-draw API became the GuiGraphicsExtractor extract model (2D Matrix3x2fStack
    // pose + RenderPipeline blit; drawString -> text). The entry's draw (dimension label/id, rotatable
    // dim icon, arrow glyphs) is restored below (x=getX(), y=getY(), rowWidth=getWidth()).
    // S19-C1 — IP body restored (26.2 extract-model re-expression; see port-note S19 §5)
    @Override
    public void extractContent(
        @NotNull GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY,
        boolean hovered,
        float a
    ) {
        Minecraft client = Minecraft.getInstance();

        graphics.text(
            client.font, dimensionName.getString(),
            this.getX() + widgetHeight + 3, (int) (this.getY()),
            0xFFFFFFFF
        );

        graphics.text(
            client.font, dimension.identifier().toString(),
            this.getX() + widgetHeight + 3, (int) (this.getY() + 10),
            0xFF999999
        );

        if (dimIconPath != null) {
            graphics.pose().pushMatrix();
            graphics.pose().translate((float) this.getX(), (float) this.getY());

            int iconLen = widgetHeight - 4;

            if (entry != null && entry.flipped) {
                // 180-degree Z rotation about the icon centre
                // (was DQuaternion.rotationByDegrees(new Vec3(0, 0, 1), 180) on the 3D pose)
                graphics.pose().rotateAbout(
                    (float) Math.PI,
                    iconLen / 2.0f, iconLen / 2.0f
                );
            }

            graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                dimIconPath, 0, 0, 0.0F, 0.0F,
                iconLen, iconLen,
                iconLen, iconLen
            );

            graphics.pose().popMatrix();
        }

        if (entry != null) {
            graphics.text(
                client.font, getText1(),
                this.getX() + widgetHeight + 3, (int) (this.getY() + 20),
                0xFF999999
            );
            graphics.text(
                client.font, getText2(),
                this.getX() + widgetHeight + 3, (int) (this.getY() + 30),
                0xFF999999
            );

            if (arrowToPrevious != ArrowType.none) {
                graphics.pose().pushMatrix();
                graphics.pose().translate((float) (this.getX() + this.getWidth() - 13), (float) this.getY());
                graphics.pose().scale(1.5f, 1.5f);
                graphics.text(
                    client.font, Component.literal("↑"),
                    0, 0,
                    arrowToPrevious == ArrowType.enabled ? 0xFF999999 : 0xFFFF0000
                );
                graphics.pose().popMatrix();
            }

            if (arrowToNext != ArrowType.none) {
                graphics.pose().pushMatrix();
                graphics.pose().translate((float) (this.getX() + this.getWidth() - 13), this.getY() + widgetHeight - 14.5f);
                graphics.pose().scale(1.5f, 1.5f);
                graphics.text(
                    client.font, Component.literal("↓"),
                    0, 0,
                    arrowToNext == ArrowType.enabled ? 0xFF999999 : 0xFFFF0000
                );
                graphics.pose().popMatrix();
            }
        }
    }
    
    private Component getText1() {
        MutableComponent scaleText = entry.scale != 1.0 ?
            Component.translatable("imm_ptl.scale")
                .append(Component.literal(":" + Double.toString(entry.scale)))
            : Component.literal("");
        
        return scaleText;
    }
    
    private Component getText2() {
        MutableComponent horizontalRotationText = entry.horizontalRotation != 0 ?
            Component.translatable("imm_ptl.horizontal_rotation")
                .append(Component.literal(":" + Double.toString(entry.horizontalRotation)))
                .append(Component.literal(" "))
            : Component.literal("");
        
        return horizontalRotationText;
    }
    
    // 26.2: GuiEventListener.mouseClicked(double, double, int) became
    // mouseClicked(MouseButtonEvent, boolean doubleClick) (ContainerObjectSelectionList.Entry:128).
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        selectCallback.accept(this);
        super.mouseClicked(event, doubleClick);
        return true;//allow outer dragging
    }
    
}
