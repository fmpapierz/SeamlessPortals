package com.warwa.seamlessportals.portal;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public enum PortalType {
    NETHER("nether", Level.OVERWORLD, Level.NETHER, 8.0),
    END("end", Level.OVERWORLD, Level.END, 1.0),
    CUSTOM("custom", null, null, 1.0);

    private final String id;
    private final ResourceKey<Level> defaultSource;
    private final ResourceKey<Level> defaultDestination;
    private final double coordinateScale;

    PortalType(String id, ResourceKey<Level> defaultSource, ResourceKey<Level> defaultDestination, double coordinateScale) {
        this.id = id;
        this.defaultSource = defaultSource;
        this.defaultDestination = defaultDestination;
        this.coordinateScale = coordinateScale;
    }

    public String getId() {
        return id;
    }

    public ResourceKey<Level> getDefaultSource() {
        return defaultSource;
    }

    public ResourceKey<Level> getDefaultDestination() {
        return defaultDestination;
    }

    public double getCoordinateScale() {
        return coordinateScale;
    }

    public ResourceKey<Level> getDestinationFor(ResourceKey<Level> current) {
        if (this == NETHER) {
            if (current == Level.OVERWORLD) return Level.NETHER;
            if (current == Level.NETHER) return Level.OVERWORLD;
        }
        if (this == END) {
            if (current == Level.OVERWORLD) return Level.END;
            if (current == Level.END) return Level.OVERWORLD;
        }
        return defaultDestination;
    }

    public static PortalType fromId(String id) {
        for (PortalType type : values()) {
            if (type.id.equals(id)) return type;
        }
        return CUSTOM;
    }
}
