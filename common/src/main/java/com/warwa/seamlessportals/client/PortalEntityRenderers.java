package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.network.PlatformHelper;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import qouteall.imm_ptl.core.portal.BreakableMirror;
import qouteall.imm_ptl.core.portal.EndPortalEntity;
import qouteall.imm_ptl.core.portal.LoadingIndicatorEntity;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalTrackedPortal;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;
import qouteall.imm_ptl.core.portal.global_portals.WorldWrappingPortal;
import qouteall.imm_ptl.core.portal.nether_portal.GeneralBreakablePortal;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity;
import qouteall.imm_ptl.core.render.LoadingIndicatorRenderer;
import qouteall.imm_ptl.core.render.PortalEntityRenderer;

/**
 * NF-PARITY W19 (2026-08-25): the WIRE-2 portal entity-renderer registration, EXTRACTED
 * VERBATIM from {@code SeamlessPortalsClientFabric.registerPortalEntityRenderers} into
 * {@code :common} — it was already loader-neutral (it only feeds the S0
 * {@link PlatformHelper#registerEntityRenderer} seam), so both loader client entrypoints
 * now share one copy. 1:1 with IP's IPModEntryClient.initPortalRenderers:41-61 (the
 * raw-cast + {@code @SuppressWarnings} pattern is IP's own — the shared
 * {@code PortalEntityRenderer} services every portal subtype, which the seam's per-type
 * generic cannot express). Called unconditionally (both flag states) — see the loader
 * entrypoints' call-site notes.
 */
public final class PortalEntityRenderers {

    private PortalEntityRenderers() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerAll() {
        PlatformHelper platformHelper = PlatformHelper.getInstance();

        for (EntityType<?> entityType : new EntityType<?>[]{
            Portal.ENTITY_TYPE,
            NetherPortalEntity.ENTITY_TYPE,
            EndPortalEntity.ENTITY_TYPE,
            Mirror.ENTITY_TYPE,
            BreakableMirror.ENTITY_TYPE,
            GlobalTrackedPortal.ENTITY_TYPE,
            WorldWrappingPortal.ENTITY_TYPE,
            VerticalConnectingPortal.ENTITY_TYPE,
            GeneralBreakablePortal.ENTITY_TYPE
        }) {
            platformHelper.registerEntityRenderer(
                (EntityType) entityType, (EntityRendererProvider) PortalEntityRenderer::new);
        }

        platformHelper.registerEntityRenderer(
            LoadingIndicatorEntity.entityType, LoadingIndicatorRenderer::new);
    }
}
