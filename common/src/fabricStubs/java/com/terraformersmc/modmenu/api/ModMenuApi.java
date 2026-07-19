// S10-A-family loader-seam compileOnly stub (entity-portal migration; S19-B, port-note
// S19-peripheral-tail §4). NOT IP source, NOT shipped. ModMenu DOES exist for 26.2
// (com.terraformersmc:modmenu:20.0.0-beta.4 is a real :fabric implementation dep — the
// block-era ModMenuIntegration compiles against it), so these shells follow the fabricStubs
// rule, NOT ipStubs: wired onto :common's compile classpath only (+ the neoforge
// fabricStubsClasspath), NEVER onto :fabric — where the stub would SHADOW the real ModMenu
// types (the exact hazard the fabricStubs set exists to avoid). Faithful shell of
// ModMenuApi as far as the ported tree consumes it: the single getModConfigScreenFactory
// member IPModMenuConfigEntry overrides. Removed at S20.
package com.terraformersmc.modmenu.api;

public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}
