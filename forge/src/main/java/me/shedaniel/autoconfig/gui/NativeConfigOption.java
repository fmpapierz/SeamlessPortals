// FORGE 26.3: the reflection model behind the native config screen (NativeConfigScreen's header says why that screen
// exists). ONE instance = one config field AutoConfig would have shown, plus the value the player has STAGED for it.
// Deliberately free of every net.minecraft type — plain reflection and parsing — so the widgets in NativeConfigList
// stay thin and the rules below can be read (and exercised) without a running client.
package me.shedaniel.autoconfig.gui;

import me.shedaniel.autoconfig.annotation.ConfigEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One shown config field: what AutoConfig's annotations say about it, the value it had when the screen opened, and
 * the value the player has staged since. Nothing here writes to the live config until {@link #applyTo(Object)} — the
 * screen's Done button — so Cancel/Esc is simply "drop this object".
 *
 * <p>The annotation rules are Cloth Config's own ({@code me.shedaniel.autoconfig.gui.ConfigScreenProvider},
 * {@code DefaultGuiProviders}, {@code DefaultGuiTransformers} in cloth-config 26.x), restated in {@link #scan}.
 */
final class NativeConfigOption {
    private static final Logger LOGGER = LoggerFactory.getLogger("AutoConfig");

    /**
     * The widget family a field maps to — AutoConfig's {@code DefaultGuiProviders} table cut down to the field types
     * {@code IPConfig} really has among its shown fields (boolean, enum, bounded and unbounded int) plus the scalar
     * neighbours that cost nothing (long, float, double, String). Lists, maps and nested objects are NOT modelled:
     * {@code IPConfig}'s only ones ({@code disabledWarnings}, {@code dimStackPreset}) are {@code @Excluded}.
     */
    enum Kind {
        BOOLEAN,
        ENUM,
        SLIDER,
        WHOLE_NUMBER,
        DECIMAL_NUMBER,
        TEXT
    }

    /** AutoConfig's category for a field with no {@code @ConfigEntry.Category}. */
    static final String DEFAULT_CATEGORY = "default";

    // FORGE 26.3: Double.parseDouble alone is too generous for a config box — it takes "NaN", "Infinity", hex floats
    // and the Java literal suffixes ("1f", "2d"). Gson refuses to WRITE a non-finite number, so such a value would
    // make the whole save fail silently (the serializer is best-effort). Plain decimal notation only.
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?");

    private final Field field;
    private final Class<?> type;
    private final Kind kind;
    private final String labelKey;
    private final List<String> tooltipKeys;
    private final long min;
    private final long max;
    private final Object original;
    private Object staged;

    private NativeConfigOption(
        Field field, Kind kind, String labelKey, List<String> tooltipKeys, long min, long max, Object value
    ) {
        this.field = field;
        this.type = field.getType();
        this.kind = kind;
        this.labelKey = labelKey;
        this.tooltipKeys = tooltipKeys;
        this.min = min;
        this.max = max;
        this.original = value;
        this.staged = value;
    }

    /**
     * Walks the config class the way {@code ConfigScreenProvider.get()} does and returns the shown options grouped by
     * category, in AutoConfig's order.
     *
     * <ul>
     *   <li>Fields are taken in {@code getDeclaredFields()} order (declaration order on HotSpot — AutoConfig leans on
     *       the same thing).</li>
     *   <li>A category is created the first time ANY field names it — {@code @Excluded} fields included, because
     *       AutoConfig resolves the category before it asks the registry for the field's entries. That is why
     *       {@code IPConfig} opens on "default" (its first field, the excluded wiki link, has no {@code @Category})
     *       and lists "client" second, exactly as it does under Cloth on Fabric and NeoForge. A category that ends
     *       up with no shown field is returned empty; the screen leaves it out.</li>
     *   <li>{@code @ConfigEntry.Gui.Excluded} fields are not shown. Neither are static, transient or synthetic ones:
     *       Gson never persists those, so they are not config values at all.</li>
     * </ul>
     *
     * @param i18n {@code text.autoconfig.<name>} — the prefix every lang key of this config hangs off
     */
    static Map<String, List<NativeConfigOption>> scan(Class<?> configClass, Object config, String i18n) {
        Map<String, List<NativeConfigOption>> byCategory = new LinkedHashMap<>();
        if (config == null) {
            return byCategory;
        }
        Defaults defaults = new Defaults(configClass);
        for (Field field : configClass.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.isSynthetic() || Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                continue;
            }
            ConfigEntry.Category category = field.getAnnotation(ConfigEntry.Category.class);
            List<NativeConfigOption> options = byCategory.computeIfAbsent(
                category != null ? category.value() : DEFAULT_CATEGORY, name -> new ArrayList<>());
            if (field.isAnnotationPresent(ConfigEntry.Gui.Excluded.class)) {
                continue;
            }
            NativeConfigOption option = create(field, config, i18n, defaults);
            if (option != null) {
                options.add(option);
            }
        }
        return byCategory;
    }

    private static NativeConfigOption create(Field field, Object config, String i18n, Defaults defaults) {
        Object value;
        try {
            field.setAccessible(true);
            value = field.get(config);
        }
        catch (RuntimeException | IllegalAccessException e) {
            LOGGER.warn("Config option '{}' cannot be read reflectively; it is not shown", field.getName(), e);
            return null;
        }

        // AutoConfig: text.autoconfig.<name>.option.<field>, and for @Tooltip either ONE key "<option>.@Tooltip"
        // (count == 1, the default) or "<option>.@Tooltip[0]" .. "[count-1]" (count > 1). count == 0 = no tooltip.
        String labelKey = i18n + ".option." + field.getName();
        List<String> tooltipKeys = new ArrayList<>();
        ConfigEntry.Gui.Tooltip tooltip = field.getAnnotation(ConfigEntry.Gui.Tooltip.class);
        if (tooltip != null) {
            if (tooltip.count() == 1) {
                tooltipKeys.add(labelKey + ".@Tooltip");
            }
            else {
                for (int i = 0; i < tooltip.count(); i++) {
                    tooltipKeys.add(labelKey + ".@Tooltip[" + i + "]");
                }
            }
        }

        Class<?> type = field.getType();
        if (type == boolean.class || type == Boolean.class) {
            return new NativeConfigOption(field, Kind.BOOLEAN, labelKey, tooltipKeys, 0L, 0L,
                value != null ? value : Boolean.FALSE);
        }
        if (type.isEnum()) {
            // With or without @EnumHandler(option = BUTTON): Cloth's other enum form is a dropdown widget, which has
            // no vanilla counterpart, and a cycle button over the same constants edits the same value.
            Object[] constants = type.getEnumConstants();
            if (constants == null || constants.length == 0) {
                return null;
            }
            if (value == null) {
                // AutoConfig: getUnsafely(field, config, getUnsafely(field, defaults)) — a null enum (Gson's answer to
                // an unknown constant name in the json) shows the default instance's value.
                value = defaults.valueOf(field);
            }
            return new NativeConfigOption(field, Kind.ENUM, labelKey, tooltipKeys, 0L, 0L,
                value != null ? value : constants[0]);
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            boolean wide = type == long.class || type == Long.class;
            if (value == null) {
                if (wide) {
                    value = Long.valueOf(0L);
                }
                else {
                    value = Integer.valueOf(0);
                }
            }
            // @BoundedDiscrete makes the slider; its absence makes the typed box. IPConfig relies on exactly that
            // split (see the comments on maxPortalLayer / portalWindowRenderDistance there).
            ConfigEntry.BoundedDiscrete bounds = field.getAnnotation(ConfigEntry.BoundedDiscrete.class);
            if (bounds != null) {
                long min = wide ? bounds.min() : Math.max(bounds.min(), Integer.MIN_VALUE);
                long max = wide ? bounds.max() : Math.min(bounds.max(), Integer.MAX_VALUE);
                if (max > min) {
                    return new NativeConfigOption(field, Kind.SLIDER, labelKey, tooltipKeys, min, max, value);
                }
            }
            return new NativeConfigOption(field, Kind.WHOLE_NUMBER, labelKey, tooltipKeys, 0L, 0L, value);
        }
        if (type == float.class || type == Float.class || type == double.class || type == Double.class) {
            if (value == null) {
                if (type == Float.class) {
                    value = Float.valueOf(0.0F);
                }
                else {
                    value = Double.valueOf(0.0);
                }
            }
            return new NativeConfigOption(field, Kind.DECIMAL_NUMBER, labelKey, tooltipKeys, 0L, 0L, value);
        }
        if (type == String.class) {
            return new NativeConfigOption(field, Kind.TEXT, labelKey, tooltipKeys, 0L, 0L, value != null ? value : "");
        }
        LOGGER.warn("Config option '{}' has type {}, which the native config screen does not edit; it is not shown",
            field.getName(), type.getName());
        return null;
    }

    Kind kind() {
        return this.kind;
    }

    String labelKey() {
        return this.labelKey;
    }

    /** Empty = no tooltip. One element per tooltip line key, in order. */
    List<String> tooltipKeys() {
        return this.tooltipKeys;
    }

    /** {@link Kind#SLIDER} only: inclusive lower bound. */
    long min() {
        return this.min;
    }

    /** {@link Kind#SLIDER} only: inclusive upper bound. */
    long max() {
        return this.max;
    }

    /** {@link Kind#ENUM} only: the constants to cycle through, in declaration order. */
    Object[] enumConstants() {
        return this.type.getEnumConstants();
    }

    /** The staged value, never null — a Boolean, an enum constant, a boxed number or a String, by {@link #kind()}. */
    Object staged() {
        return this.staged;
    }

    /** {@link Kind#BOOLEAN} / {@link Kind#ENUM}: the toggle or cycle button moved to {@code value}. */
    void stage(Object value) {
        if (value != null) {
            this.staged = value;
        }
    }

    /**
     * {@link Kind#SLIDER}: where the knob belongs. A stored value outside the annotated range shows at the nearer end
     * but stays STAGED as it is — only moving the slider replaces it — so opening the screen and pressing Done never
     * rewrites a value the player did not touch.
     */
    long sliderValue() {
        long value = ((Number) this.staged).longValue();
        return Math.max(this.min, Math.min(this.max, value));
    }

    /** {@link Kind#SLIDER}: the knob moved to {@code value} (already within the range). */
    void stageSlider(long value) {
        if (this.type == long.class || this.type == Long.class) {
            this.staged = Long.valueOf(value);
        }
        else {
            this.staged = Integer.valueOf((int) value);
        }
    }

    /** The text a typed box starts with. */
    String text() {
        return String.valueOf(this.staged);
    }

    /**
     * {@link Kind#WHOLE_NUMBER} / {@link Kind#DECIMAL_NUMBER} / {@link Kind#TEXT}: the box now reads {@code text}.
     *
     * @return {@code false} when {@code text} is not a value of the field's type. The staged value is then LEFT AT THE
     * LAST VALID ONE, so Done can never persist half-typed input, and the caller paints the box in its error colour.
     */
    boolean stageText(String text) {
        if (this.kind == Kind.TEXT) {
            this.staged = text;
            return true;
        }
        String trimmed = text.trim();
        try {
            if (this.kind == Kind.WHOLE_NUMBER) {
                if (this.type == long.class || this.type == Long.class) {
                    this.staged = Long.valueOf(Long.parseLong(trimmed));
                }
                else {
                    this.staged = Integer.valueOf(Integer.parseInt(trimmed));
                }
                return true;
            }
            if (this.kind == Kind.DECIMAL_NUMBER && DECIMAL.matcher(trimmed).matches()) {
                if (this.type == float.class || this.type == Float.class) {
                    float parsed = Float.parseFloat(trimmed);
                    if (Float.isFinite(parsed)) {
                        this.staged = Float.valueOf(parsed);
                        return true;
                    }
                }
                else {
                    double parsed = Double.parseDouble(trimmed);
                    if (Double.isFinite(parsed)) {
                        this.staged = Double.valueOf(parsed);
                        return true;
                    }
                }
            }
        }
        catch (NumberFormatException ignored) {
            // not a number of this type (empty, letters, out of range) — reported through the return value
        }
        return false;
    }

    /** Whether the player has changed this option since the screen opened. */
    boolean isDirty() {
        return !Objects.equals(this.original, this.staged);
    }

    /**
     * Done: writes the staged value into {@code config} — only if it changed, so an untouched option can never clobber
     * a value something else wrote into the live config while the screen was open. Never throws.
     */
    void applyTo(Object config) {
        if (!this.isDirty()) {
            return;
        }
        try {
            this.field.set(config, this.staged);
        }
        catch (RuntimeException | IllegalAccessException e) {
            LOGGER.error("Config option '{}' could not be written; it keeps its previous value",
                this.field.getName(), e);
        }
    }

    /**
     * A default-constructed config instance, built at most once and only on demand (a null enum is the one thing that
     * asks). Real AutoConfig gets it from {@code ConfigSerializer.createDefault()}; the holder interface of this
     * module does not expose its serializer, and the no-arg constructor is what that method calls anyway.
     */
    private static final class Defaults {
        private final Class<?> configClass;
        private Object instance;
        private boolean attempted;

        Defaults(Class<?> configClass) {
            this.configClass = configClass;
        }

        Object valueOf(Field field) {
            if (!this.attempted) {
                this.attempted = true;
                try {
                    Constructor<?> constructor = this.configClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    this.instance = constructor.newInstance();
                }
                catch (RuntimeException | ReflectiveOperationException e) {
                    LOGGER.warn("Config {} has no usable no-arg constructor; defaults are unavailable",
                        this.configClass.getName(), e);
                }
            }
            if (this.instance == null) {
                return null;
            }
            try {
                return field.get(this.instance);
            }
            catch (RuntimeException | IllegalAccessException e) {
                return null;
            }
        }
    }
}
