package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.minecraft.world.phys.Vec3;

public class CameraTransitionHandler {
    private static boolean inTransition = false;
    private static int transitionTicks = 0;
    private static int maxTransitionTicks = 5;

    private static Vec3 startPos;
    private static Vec3 endPos;
    private static float startYRot;
    private static float startXRot;
    private static float endYRot;
    private static float endXRot;

    public static void startTransition(Vec3 from, Vec3 to, float fromYRot, float fromXRot,
                                        float toYRot, float toXRot) {
        inTransition = true;
        transitionTicks = 0;
        maxTransitionTicks = SeamlessPortalsConfig.get().getCameraSmoothingTicks();

        startPos = from;
        endPos = to;
        startYRot = fromYRot;
        startXRot = fromXRot;
        endYRot = toYRot;
        endXRot = toXRot;
    }

    public static void tick() {
        if (!inTransition) return;

        transitionTicks++;
        if (transitionTicks >= maxTransitionTicks) {
            inTransition = false;
        }
    }

    public static boolean isInTransition() {
        return inTransition;
    }

    public static float getTransitionProgress() {
        if (!inTransition || maxTransitionTicks <= 0) return 1.0f;
        float t = (float) transitionTicks / maxTransitionTicks;
        return smoothstep(t);
    }

    public static Vec3 getInterpolatedPosition(float partialTick) {
        if (!inTransition) return endPos;
        float t = ((float) transitionTicks + partialTick) / maxTransitionTicks;
        t = Math.min(1.0f, t);
        t = smoothstep(t);
        return startPos.lerp(endPos, t);
    }

    public static float getInterpolatedYRot(float partialTick) {
        if (!inTransition) return endYRot;
        float t = ((float) transitionTicks + partialTick) / maxTransitionTicks;
        t = Math.min(1.0f, t);
        t = smoothstep(t);
        return lerpAngle(startYRot, endYRot, t);
    }

    public static float getInterpolatedXRot(float partialTick) {
        if (!inTransition) return endXRot;
        float t = ((float) transitionTicks + partialTick) / maxTransitionTicks;
        t = Math.min(1.0f, t);
        t = smoothstep(t);
        return startXRot + (endXRot - startXRot) * t;
    }

    private static float smoothstep(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lerpAngle(float from, float to, float t) {
        float diff = to - from;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return from + diff * t;
    }

    public static void cancel() {
        inTransition = false;
    }
}
