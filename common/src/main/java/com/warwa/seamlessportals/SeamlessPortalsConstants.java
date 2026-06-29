package com.warwa.seamlessportals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SeamlessPortalsConstants {
    public static final String MOD_ID = "seamlessportals";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Master switch for VERBOSE render/crossing-path diagnostic logging (the
     * per-FBO-render [SEAMLESS DEBUG]/PHASE2/DIAG/PARTICLE/etc. trace). Default
     * OFF: these fire on the render thread, where a log4j per-event timestamp
     * formatter compile can stall the frame ~130ms, and
     * they burst dozens-deep per crossing. Flip to true only when debugging the
     * portal pipeline.
     */
    public static final boolean VERBOSE_RENDER_LOG = false;

    /** Render/crossing-path diagnostic log — no-ops unless {@link #VERBOSE_RENDER_LOG}. */
    public static void rlog(String msg, Object... args) {
        if (VERBOSE_RENDER_LOG) {
            LOGGER.info(msg, args);
        }
    }
}
