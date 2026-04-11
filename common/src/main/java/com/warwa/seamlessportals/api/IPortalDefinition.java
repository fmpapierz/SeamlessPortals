package com.warwa.seamlessportals.api;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public interface IPortalDefinition {

    Identifier getId();

    PortalShape getShape();

    ResourceKey<Level> getSourceDimension();

    ResourceKey<Level> getDestinationDimension();

    Vec3 transformCoordinate(Vec3 source);

    Vec3 transformVelocity(Vec3 velocity);

    double getCoordinateScale();

    boolean shouldRenderThrough();

    boolean shouldAllowEntityPass();

    boolean shouldAllowProjectilePass();

    boolean isBlockPartOfPortal(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos);

    enum PortalShape {
        RECTANGLE,
        HORIZONTAL_RECTANGLE,
        CUSTOM
    }
}
