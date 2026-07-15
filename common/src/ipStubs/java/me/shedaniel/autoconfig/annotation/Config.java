// F21 Cloth-Config / AutoConfig compileOnly stub — see ../ConfigData.java header. NOT shipped. Removed at S20.
package me.shedaniel.autoconfig.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Config-class marker: {@code @Config(name = "immersive_portals")} on IPConfig. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Config {
    String name();
}
