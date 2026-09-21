// FORGE 26.3: the scrolling option list of the native config screen (NativeConfigScreen's header says why that screen
// exists). Vanilla 26.3 widgets only. The shape — a ContainerObjectSelectionList whose rows draw a label on the left
// and position ONE value widget on the right every frame, with a per-row tooltip pushed from the list — is vanilla's
// own net.minecraft.client.gui.screens.worldselection.AbstractGameRulesScreen (RuleList / BooleanRuleEntry /
// IntegerRuleEntry), including its red-text treatment of a box that does not parse.
package me.shedaniel.autoconfig.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The option list: one {@link Row} per shown config field of the selected category. The rows (and with them the
 * widgets and every staged edit) belong to the screen and outlive a category switch; this list only displays the set
 * it is handed through {@link #showRows}.
 *
 * <p>Scrolling: the wheel always scrolls the LIST, even over a cycle button — {@code AbstractScrollArea.mouseScrolled}
 * is a class method and so wins over {@code ContainerEventHandler}'s child-forwarding default, which means
 * {@code CycleButton.mouseScrolled} (it would cycle the value) is never reached from here.
 */
final class NativeConfigList extends ContainerObjectSelectionList<NativeConfigList.Row> {
    // Vanilla's game-rule list uses 24 too: a 20-high widget plus the entry's 2px content padding on either side.
    private static final int ROW_HEIGHT = 24;
    private static final int MAX_ROW_WIDTH = 420;
    // AbstractSelectionList.scrollBarX() puts the 6-wide scroll bar 8px right of the row, so 20px keeps it on screen.
    private static final int SIDE_MARGIN = 20;

    NativeConfigList(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, ROW_HEIGHT);
    }

    /** Swaps in another category's rows, from the top. */
    void showRows(List<Row> rows) {
        // clearEntries() forgets the selection but not the focused entry, and a focused entry that is no longer a
        // child would go on receiving key and character events.
        this.setFocused((GuiEventListener) null);
        this.replaceEntries(rows);
        this.setScrollAmount(0.0);
    }

    @Override
    public int getRowWidth() {
        return Math.max(0, Math.min(this.getWidth() - 2 * SIDE_MARGIN, MAX_ROW_WIDTH));
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
        // The tooltip belongs to the whole ROW (label included), as it does under Cloth — not just to the widget.
        Row hovered = this.getHovered();
        if (hovered != null && hovered.tooltip != null) {
            graphics.setTooltipForNextFrame(hovered.tooltip, mouseX, mouseY);
        }
    }

    /** Label on the left, the option's one value widget on the right. */
    static final class Row extends ContainerObjectSelectionList.Entry<Row> {
        private static final int WIDGET_HEIGHT = 20;
        private static final int VALUE_WIDTH = 150;
        private static final int MIN_VALUE_WIDTH = 90;
        private static final int LABEL_GAP = 8;
        private static final int TOOLTIP_WIDTH = 200;
        private static final int LABEL_COLOR = -1;
        // The colour vanilla's IntegerRuleEntry gives a game-rule box that does not parse.
        private static final int INVALID_TEXT_COLOR = -65536;

        private final NativeConfigOption option;
        private final Font font;
        private final Component label;
        private final List<FormattedCharSequence> tooltip;
        private final AbstractWidget widget;
        private final List<AbstractWidget> widgets;
        private List<FormattedCharSequence> labelLines = List.of();
        private int labelLinesWidth = -1;

        Row(Font font, NativeConfigOption option) {
            this.option = option;
            this.font = font;
            this.label = Component.translatable(option.labelKey());
            this.tooltip = splitTooltip(font, option.tooltipKeys());
            this.widget = createWidget(font, this.label, option);
            this.widgets = List.of(this.widget);
        }

        NativeConfigOption option() {
            return this.option;
        }

        private static List<FormattedCharSequence> splitTooltip(Font font, List<String> keys) {
            if (keys.isEmpty()) {
                return null;
            }
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (String key : keys) {
                lines.addAll(font.split(Component.translatable(key), TOOLTIP_WIDTH));
            }
            return lines;
        }

        private static AbstractWidget createWidget(Font font, Component label, NativeConfigOption option) {
            return switch (option.kind()) {
                case BOOLEAN -> createToggle(label, option);
                case ENUM -> createCycle(label, option);
                case SLIDER -> new RangeSlider(label, option);
                case WHOLE_NUMBER, DECIMAL_NUMBER, TEXT -> createBox(font, label, option);
            };
        }

        private static AbstractWidget createToggle(Component label, NativeConfigOption option) {
            // Cloth's boolean entry: a button reading Yes (green) / No (red). CycleButton's "default value" IS its
            // initial value (Builder.create: initialValue = defaultValueSupplier.get()).
            Component yes = CommonComponents.GUI_YES.copy().withStyle(ChatFormatting.GREEN);
            Component no = CommonComponents.GUI_NO.copy().withStyle(ChatFormatting.RED);
            return CycleButton.booleanBuilder(yes, no, Boolean.TRUE.equals(option.staged()))
                .displayOnlyValue()
                .create(0, 0, VALUE_WIDTH, WIDGET_HEIGHT, label, (button, value) -> option.stage(value));
        }

        private static AbstractWidget createCycle(Component label, NativeConfigOption option) {
            // Cloth's BUTTON enum handler shows the constant's toString(); so does this. The Builder CONSTRUCTOR (one
            // signature) is used rather than CycleButton.builder(..): that has two overloads, (Function, Supplier<T>)
            // and (Function, T), and with a lambda as the second argument and T = Object the pick is left to inference.
            CycleButton.ValueListSupplier<Object> values =
                CycleButton.ValueListSupplier.create(Arrays.asList(option.enumConstants()));
            Object initial = option.staged();
            return new CycleButton.Builder<Object>(
                constant -> Component.literal(String.valueOf(constant)), () -> initial)
                .withValues(values)
                .displayOnlyValue()
                .create(0, 0, VALUE_WIDTH, WIDGET_HEIGHT, label, (button, value) -> option.stage(value));
        }

        private static AbstractWidget createBox(Font font, Component label, NativeConfigOption option) {
            EditBox box = new EditBox(font, 0, 0, VALUE_WIDTH, WIDGET_HEIGHT, label);
            // Before setValue, which truncates to the limit (EditBox's default is 32).
            box.setMaxLength(option.kind() == NativeConfigOption.Kind.TEXT ? 32500 : 64);
            box.setValue(option.text());
            box.moveCursorToStart(false);
            // After setValue, so the initial text is not reported as an edit. A value that does not parse is NOT
            // staged (stageText keeps the last valid one) and the box says so in red.
            box.setResponder(text ->
                box.setTextColor(option.stageText(text) ? EditBox.DEFAULT_TEXT_COLOR : INVALID_TEXT_COLOR));
            return box;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            // Entries own no layout of their own: like every vanilla list row, the widget is placed here each frame,
            // and clicks are tested against wherever the last frame left it.
            int valueWidth = Math.min(VALUE_WIDTH, Math.max(MIN_VALUE_WIDTH, this.getContentWidth() / 2));
            this.widget.setWidth(valueWidth);
            this.widget.setX(this.getContentRight() - valueWidth);
            this.widget.setY(this.getContentY());

            int labelWidth = Math.max(20, this.getContentWidth() - valueWidth - LABEL_GAP);
            if (labelWidth != this.labelLinesWidth) {
                this.labelLines = this.font.split(this.label, labelWidth);
                this.labelLinesWidth = labelWidth;
            }
            int top = this.getContentY();
            if (this.labelLines.size() == 1) {
                graphics.text(this.font, this.labelLines.get(0), this.getContentX(), top + 6, LABEL_COLOR);
            }
            else if (this.labelLines.size() >= 2) {
                graphics.text(this.font, this.labelLines.get(0), this.getContentX(), top + 1, LABEL_COLOR);
                graphics.text(this.font, this.labelLines.get(1), this.getContentX(), top + 11, LABEL_COLOR);
            }

            this.widget.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.widgets;
        }
    }

    /**
     * {@code @ConfigEntry.BoundedDiscrete}: a whole-number slider over [min, max] that shows the number it stands on.
     * The arrow-key stepping and the snap on release are {@code OptionInstance.OptionInstanceSliderButton}'s — the
     * base class alone moves one PIXEL per key press, which on a short range changes nothing for several presses.
     */
    private static final class RangeSlider extends AbstractSliderButton {
        private final NativeConfigOption option;
        private final Component label;

        RangeSlider(Component label, NativeConfigOption option) {
            super(0, 0, Row.VALUE_WIDTH, Row.WIDGET_HEIGHT, CommonComponents.EMPTY,
                position(option, option.sliderValue()));
            this.option = option;
            this.label = label;
            this.updateMessage();
        }

        private static double position(NativeConfigOption option, long value) {
            return (double) (value - option.min()) / (double) (option.max() - option.min());
        }

        private long current() {
            return this.option.min() + Math.round(this.value * (double) (this.option.max() - this.option.min()));
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(Long.toString(this.current())));
        }

        @Override
        protected void applyValue() {
            this.option.stageSlider(this.current());
        }

        @Override
        public void onRelease(MouseButtonEvent event) {
            super.onRelease(event);
            this.value = position(this.option, this.current());
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (this.canChangeValue && (event.isLeft() || event.isRight())) {
                long next = this.current() + (event.isLeft() ? -1L : 1L);
                this.setValue(position(this.option, Math.max(this.option.min(), Math.min(this.option.max(), next))));
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        protected MutableComponent createNarrationMessage() {
            // The visible message is the bare number (the label is drawn by the row), so narration adds the label.
            return Component.translatable(
                "gui.narrate.slider", CommonComponents.optionNameValue(this.label, this.getMessage()));
        }
    }
}
