// FORGE 26.3: the config screen behind the Forge mod list's "Config" button. Cloth Config has NO MinecraftForge build
// for any 26.x version, so this module ships its own me.shedaniel.autoconfig (see ../ConfigData.java's header); its
// AutoConfigClient.getConfigScreen used to hand the PARENT screen back, i.e. the button opened the screen the player
// was already on. This is the missing screen — vanilla 26.3 GUI classes only, driven by the same annotations and the
// same lang keys as AutoConfig's Cloth screen, so :common's IPConfig and its en_us.json entries serve it unchanged.
package me.shedaniel.autoconfig.gui;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AutoConfig's config screen, rebuilt from vanilla widgets.
 *
 * <p><b>What it mirrors</b> (cloth-config 26.x, {@code me.shedaniel.autoconfig.gui.ConfigScreenProvider} and the two
 * {@code DefaultGui*} registries): the title is {@code text.autoconfig.<name>.title}; every
 * {@code @ConfigEntry.Category} is a page labelled {@code text.autoconfig.<name>.category.<category>}, in
 * first-appearance order with {@code default} for unannotated fields; options are labelled
 * {@code text.autoconfig.<name>.option.<field>} and tooltipped from {@code ...option.<field>.@Tooltip} (or
 * {@code @Tooltip[i]}); {@code @Excluded} fields are absent. The field-to-widget table lives in
 * {@link NativeConfigOption}.
 *
 * <p><b>Shape.</b> Vanilla's {@code AbstractGameRulesScreen}: a {@link HeaderAndFooterLayout} holding the title, a
 * scrolling list of label + value rows ({@link NativeConfigList}) and a Done / Cancel footer. With more than one
 * category the header also carries one button per category, the open one disabled — which is how Cloth's own category
 * bar reads. Vanilla's {@code MenuTabBar} is not used for that: its {@code arrangeElements} pins the bar to y = 0, the
 * top of the screen, which is where the title goes.
 *
 * <p><b>Staging.</b> Every edit lands in its row's {@link NativeConfigOption}, never in the config. Done writes the
 * changed values into the holder's live config object and calls {@link ConfigHolder#save()}, whose save listeners are
 * what apply a config to the running game ({@code IPConfig.onConfigChanged} through {@code IPModMain.loadConfig});
 * Cancel and Esc write nothing. Both return to the parent screen the way Forge's own {@code ModListScreen} opens this
 * one and leaves itself: {@code Minecraft.gui.setScreen}. (Forge patches {@code Screen.onClose} into
 * {@code gui.popLayer()}, which is for layered screens; this one is opened with {@code setScreen}.) A {@code null}
 * parent — what the {@code /imm_ptl_client_debug config} command passes — closes to the game.
 */
public final class NativeConfigScreen extends Screen {
    private static final int HEADER_HEIGHT = 33;
    // title (9) + 6 spacing + a 20-high button row = 35, which HeaderAndFooterLayout centres in the header
    private static final int HEADER_HEIGHT_WITH_CATEGORIES = 52;
    private static final int FOOTER_HEIGHT = 33;
    private static final int CATEGORY_BUTTON_WIDTH = 150;
    private static final int MIN_CATEGORY_BUTTON_WIDTH = 40;
    private static final int CATEGORY_BUTTON_SPACING = 4;
    private static final int CATEGORY_BAR_MARGIN = 20;

    private final Screen parent;
    private final ConfigHolder<?> holder;
    private final List<Category> categories = new ArrayList<>();
    private final List<Button> categoryButtons = new ArrayList<>();
    private HeaderAndFooterLayout layout;
    private NativeConfigList list;
    private int selectedCategory;

    /**
     * @param holder the registered holder of {@code configClass} ({@code AutoConfig.getConfigHolder}). The values shown
     *               are read from its config NOW; Done writes into whatever config object it holds THEN.
     * @param parent the screen to return to, or {@code null} to close to the game
     */
    public NativeConfigScreen(Class<? extends ConfigData> configClass, ConfigHolder<?> holder, Screen parent) {
        super(Component.translatable(i18n(configClass) + ".title"));
        this.parent = parent;
        this.holder = holder;

        // The rows are built ONCE, here, and kept for the life of the screen: they carry the widgets and every staged
        // edit, so neither a category switch nor a resize can lose what the player typed. (Screen's constructor has
        // already set this.font.)
        String i18n = i18n(configClass);
        Map<String, List<NativeConfigOption>> byCategory =
            NativeConfigOption.scan(configClass, holder.getConfig(), i18n);
        for (Map.Entry<String, List<NativeConfigOption>> entry : byCategory.entrySet()) {
            if (entry.getValue().isEmpty()) {
                // Named only by fields that are not shown (@Excluded, or a type this screen does not edit). Cloth would
                // put up an empty page for it; there is nothing to gain from one.
                continue;
            }
            List<NativeConfigList.Row> rows = new ArrayList<>();
            for (NativeConfigOption option : entry.getValue()) {
                rows.add(new NativeConfigList.Row(this.font, option));
            }
            this.categories.add(new Category(Component.translatable(i18n + ".category." + entry.getKey()), rows));
        }
    }

    /** {@code text.autoconfig.<name>}; the class name stands in for a missing {@code @Config}, as in the serializer. */
    private static String i18n(Class<?> configClass) {
        Config definition = configClass.getAnnotation(Config.class);
        return "text.autoconfig." + (definition != null ? definition.name() : configClass.getSimpleName());
    }

    @Override
    protected void init() {
        // A fresh layout per init(): Screen.rebuildWidgets() may call this again, and a reused layout would collect a
        // second set of children. (Resizes do not come through here — see repositionElements.)
        boolean categorized = this.categories.size() > 1;
        this.layout = new HeaderAndFooterLayout(
            this, categorized ? HEADER_HEIGHT_WITH_CATEGORIES : HEADER_HEIGHT, FOOTER_HEIGHT);

        LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(6));
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(this.title, this.font));
        this.categoryButtons.clear();
        if (categorized) {
            LinearLayout bar = header.addChild(LinearLayout.horizontal().spacing(CATEGORY_BUTTON_SPACING));
            for (int i = 0; i < this.categories.size(); i++) {
                int index = i;
                Button button = Button.builder(this.categories.get(i).label(), pressed -> this.selectCategory(index))
                    .width(CATEGORY_BUTTON_WIDTH)
                    .build();
                this.categoryButtons.add(bar.addChild(button));
            }
        }

        this.list = this.layout.addToContents(new NativeConfigList(
            this.minecraft, this.width, this.layout.getContentHeight(), this.layout.getHeaderHeight()));

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, pressed -> this.saveAndClose()).build());
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, pressed -> this.onClose()).build());

        this.layout.visitWidgets(widget -> this.addRenderableWidget(widget));
        this.selectCategory(this.selectedCategory);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        // Overridden so a resize re-lays-out the existing widgets instead of Screen's default rebuildWidgets().
        if (this.layout == null) {
            return;
        }
        if (!this.categoryButtons.isEmpty()) {
            int count = this.categoryButtons.size();
            int available = this.width - 2 * CATEGORY_BAR_MARGIN - CATEGORY_BUTTON_SPACING * (count - 1);
            int buttonWidth = Math.max(MIN_CATEGORY_BUTTON_WIDTH, Math.min(CATEGORY_BUTTON_WIDTH, available / count));
            for (Button button : this.categoryButtons) {
                button.setWidth(buttonWidth);
            }
        }
        this.layout.arrangeElements();
        if (this.list != null) {
            this.list.updateSize(this.width, this.layout);
        }
    }

    private void selectCategory(int index) {
        if (this.categories.isEmpty() || this.list == null) {
            return;
        }
        this.selectedCategory = Math.max(0, Math.min(index, this.categories.size() - 1));
        for (int i = 0; i < this.categoryButtons.size(); i++) {
            this.categoryButtons.get(i).active = i != this.selectedCategory;
        }
        this.list.showRows(this.categories.get(this.selectedCategory).rows());
    }

    /**
     * Done: stage -> live config -> {@code save()} (listeners + file) -> back. {@code save()} runs even when nothing
     * was edited — it is idempotent ({@code IPConfig.onConfigChanged} is written to be re-fired by "an in-game config
     * save"), and it keeps Done meaning one thing.
     */
    private void saveAndClose() {
        Object config = this.holder.getConfig();
        if (config != null) {
            for (Category category : this.categories) {
                for (NativeConfigList.Row row : category.rows()) {
                    row.option().applyTo(config);
                }
            }
        }
        this.holder.save();
        this.minecraft.gui.setScreen(this.parent);
    }

    /** Cancel, and Esc ({@code Screen.keyPressed} routes it here): the staged edits are simply dropped. */
    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    private record Category(Component label, List<NativeConfigList.Row> rows) {
    }
}
