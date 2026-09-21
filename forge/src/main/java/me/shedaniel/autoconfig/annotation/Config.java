// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (see ../ConfigData.java header; S13-B P-1).
package me.shedaniel.autoconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Config-class marker: {@code @Config(name = "immersive_portals")} on IPConfig. RUNTIME-retained — the
 * shipped {@code GsonConfigSerializer} reads {@link #name()} to derive the config file name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Config {
    String name();
}
