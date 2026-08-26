package com.warwa.seamlessportals.client;

/**
 * NF-PARITY W3/B2 (2026-08-25): duck interface exposing {@link HandleRespawnMixin}'s
 * {@code seamlessportals$seamlessTransition} state to its loader-shape sibling mixins
 * ({@code HandleRespawnLoadScreenShapeVanilla} / {@code ...NeoForge}), which weave onto the
 * same {@code ClientPacketListener} instance. Implemented by {@code HandleRespawnMixin}, so
 * the cast {@code (SeamlessRespawnTransitionAccess)(Object) this} inside a sibling always
 * succeeds at runtime.
 */
public interface SeamlessRespawnTransitionAccess {
    boolean seamlessportals$isSeamlessTransition();
}
