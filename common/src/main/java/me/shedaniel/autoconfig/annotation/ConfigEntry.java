// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (see ../ConfigData.java header; S13-B P-1).
package me.shedaniel.autoconfig.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Annotation container mirroring Cloth-Config's {@code ConfigEntry}. Only the nested annotations IPConfig
 * uses are declared: {@link Category}, {@link BoundedDiscrete}, {@link Gui.Tooltip}, {@link Gui.Excluded},
 * {@link Gui.EnumHandler} (with its nested {@link Gui.EnumHandler.EnumDisplayOption}). These are pure
 * GUI-layout hints in Cloth; the shipped Gson serializer ignores them (IPConfig's own onConfigChanged()
 * does the value clamping the BoundedDiscrete bounds describe). No {@code @Target} is set so the shell
 * never conflicts with a usage context.
 */
public class ConfigEntry {
    private ConfigEntry() {
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface Category {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    public @interface BoundedDiscrete {
        long min() default 0L;

        long max();
    }

    public static class Gui {
        private Gui() {
        }

        @Retention(RetentionPolicy.RUNTIME)
        public @interface Tooltip {
            int count() default 1;
        }

        @Retention(RetentionPolicy.RUNTIME)
        public @interface Excluded {
        }

        @Retention(RetentionPolicy.RUNTIME)
        public @interface EnumHandler {
            EnumDisplayOption option() default EnumDisplayOption.BUTTON;

            enum EnumDisplayOption {
                BUTTON,
                DROPDOWN
            }
        }
    }
}
