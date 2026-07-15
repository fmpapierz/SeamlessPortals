// S13-B loader-seam compileOnly stub (entity-portal migration; port-note S13B-flip.md).
// NOT IP source, NOT shipped. This is fabric-networking-api v6's interface-injected extension of
// vanilla ServerConfigurationPacketListenerImpl (renamed from FabricServerConfigurationNetworkHandler
// in older fabric-api; fabric.mod.json injects it onto net.minecraft class_8610). This build's
// minimal loom does not APPLY the interface injection to the recompiled common source (verified
// S10A §4/§7), so ImmPtlNetworkConfig routes addTask/completeTask through an explicit cast to this
// interface (S13-B P3). The interface TYPE is a real, resolvable class in the fabric-networking jar,
// so the cast + calls compile at :fabric even without weaving; at runtime the real fabric mixin makes
// the vanilla listener implement it. :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.server.network.ConfigurationTask;

public interface FabricServerConfigurationPacketListenerImpl {
    default void addTask(ConfigurationTask task) {
    }

    default void completeTask(ConfigurationTask.Type key) {
    }
}
