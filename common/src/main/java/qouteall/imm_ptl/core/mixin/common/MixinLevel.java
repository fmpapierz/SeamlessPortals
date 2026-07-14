package qouteall.imm_ptl.core.mixin.common;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IEWorld;

@Mixin(Level.class)
public abstract class MixinLevel implements IEWorld {

    @Shadow
    @Final
    protected WritableLevelData levelData;

    @Shadow
    public abstract ResourceKey<Level> dimension();

    @Shadow
    protected float rainLevel;

    @Shadow
    protected float thunderLevel;

    @Shadow
    protected float oRainLevel;

    @Shadow
    protected float oThunderLevel;

    @Shadow
    protected abstract LevelEntityGetter<Entity> getEntities();

    @Shadow
    @Final
    private Thread thread;

    // Fix overworld rain cause nether fog change
    //
    // S10-C forced deviation (F-common-MixinLevel-1): ANCHOR-GONE + obsolete-by-vanilla.
    // The 1.21.3 inject targeted `Level.prepareWeather()V` (TAIL) to zero the nether rain/thunder
    // gradients. In 26.2 `Level.prepareWeather()` NO LONGER EXISTS — weather setup moved to the
    // private `ServerLevel.prepareWeather(WeatherData)` (ServerLevel.java:702-709), so a verbatim
    // port would fail to apply (no such method on Level.class). The role the inject served — keep
    // the nether from inheriting the (server-wide) rain gradient — is now VANILLA behavior: both
    // the construction-time call (ServerLevel.java:272-274) and advanceWeatherCycle (:713) are
    // gated by `Level.canHaveWeather()` (Level.java:857-858 =
    // hasSkyLight() && !hasCeiling() && dimension()!=END). The nether has a ceiling, so
    // canHaveWeather() is false and its rainLevel/thunderLevel are NEVER raised — the gradients
    // stay at their default 0 with no intervention. NEUTRALIZED (behavior-preserving). The residual
    // cross-dimension rain-flip broadcast (ServerLevel.java:785-795, still un-dimensioned) is a
    // SEPARATE concern handled by the dimension-scoped packet path, not by this construction-time
    // inject. See migration/api-map/mixin-common.md §1 (MixinLevel).
//    @Inject(method = "Lnet/minecraft/world/level/Level;prepareWeather()V", at = @At("TAIL"))
//    private void onInitWeatherGradients(CallbackInfo ci) {
//        if (dimension() == Level.NETHER) {
//            rainLevel = 0;
//            oRainLevel = 0;
//            thunderLevel = 0;
//            oThunderLevel = 0;
//        }
//    }

    @Override
    public WritableLevelData ip_getLevelData() {
        return levelData;
    }

    @Override
    public void portal_setWeather(float rainGradPrev, float rainGrad, float thunderGradPrev, float thunderGrad) {
        oRainLevel = rainGradPrev;
        rainLevel = rainGrad;
        oThunderLevel = thunderGradPrev;
        thunderLevel = thunderGrad;
    }

    @Override
    public LevelEntityGetter<Entity> portal_getEntityLookup() {
        return getEntities();
    }

    @Override
    public Thread portal_getThread() {
        return thread;
    }
}
