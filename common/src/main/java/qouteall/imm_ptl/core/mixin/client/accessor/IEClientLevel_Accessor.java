package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

// S12-B port disposition: PORTS-CLEAN target, RETARGETED map generic (mixin-client.md §2 / §1
// MixinClientLevel row). 26.2: `ClientLevel.mapData` is `private final Map<MapId, MapItemSavedData>`
// (26.2:ClientLevel.java:160) — the map KEY changed from `String` (IP 1.21.3) to `MapId`, so the
// accessor generics move from `Map<String, MapItemSavedData>` to `Map<MapId, MapItemSavedData>`
// (matches the already-landed MixinClientLevel's `getAllMapData()`/`addMapData(Map<MapId,...>)`).
// Shares map-item data across dimensions (ClientWorldLoader / map sync). Held/unregistered until S13.
@Mixin(ClientLevel.class)
public interface IEClientLevel_Accessor {

    @Accessor("mapData")
    Map<MapId, MapItemSavedData> ip_getMapData();

    @Mutable
    @Accessor("mapData")
    void ip_setMapData(Map<MapId, MapItemSavedData> mapData);
}
