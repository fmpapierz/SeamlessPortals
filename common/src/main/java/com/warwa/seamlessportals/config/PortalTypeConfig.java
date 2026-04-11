package com.warwa.seamlessportals.config;

public class PortalTypeConfig {
    private boolean immersive;
    private boolean renderThrough;
    private boolean seamlessTeleport;
    private boolean projectilePassThrough;

    public PortalTypeConfig(boolean immersive) {
        this.immersive = immersive;
        this.renderThrough = immersive;
        this.seamlessTeleport = immersive;
        this.projectilePassThrough = immersive;
    }

    public boolean isImmersive() { return immersive; }
    public void setImmersive(boolean immersive) { this.immersive = immersive; }

    public boolean isRenderThrough() { return renderThrough; }
    public void setRenderThrough(boolean renderThrough) { this.renderThrough = renderThrough; }

    public boolean isSeamlessTeleport() { return seamlessTeleport; }
    public void setSeamlessTeleport(boolean seamlessTeleport) { this.seamlessTeleport = seamlessTeleport; }

    public boolean isProjectilePassThrough() { return projectilePassThrough; }
    public void setProjectilePassThrough(boolean projectilePassThrough) { this.projectilePassThrough = projectilePassThrough; }
}
