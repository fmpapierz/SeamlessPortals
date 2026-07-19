export const meta = {
  name: 'c2-design-panel',
  description: 'C2 design: 3 independent Fable designs (staging / mechanism / substrate-fidelity angles) + max-effort synthesis into migration/C2_DESIGN.md',
  phases: [
    { title: 'Design', detail: '3 independent Fable designers', model: 'fable' },
    { title: 'Synthesize', detail: '1 Fable max-effort judge+synthesis writing C2_DESIGN.md', model: 'fable' },
  ],
}

const DESIGN = {
  type: 'object',
  properties: {
    design: { type: 'string', description: 'The full staged design. Concrete: per stage, the exact files/mixins/injection specs (or the redesign mechanism), the verify plan, the live-round script, and what is deliberately deferred.' },
    stages: { type: 'array', items: { type: 'object', properties: {
      name: { type: 'string' }, goal: { type: 'string' }, deliverables: { type: 'string' }, risks: { type: 'string' }, verify: { type: 'string' },
    }, required: ['name', 'goal', 'deliverables', 'risks', 'verify'] } },
    openQuestions: { type: 'array', items: { type: 'string' }, description: 'Irreducible unknowns: things only a live probe / instrument-first round or a user decision can settle. State the discriminating probe for each.' },
  },
  required: ['design', 'stages', 'openQuestions'],
}

const GROUND = `You are designing C2: making Immersive Portals' Sodium/Iris compatibility REAL against Sodium 0.9.1 / Iris 1.11.2 on MC 26.2, for the Seamless Portals port at C:\\Users\\warwa\\ModDev\\Portals\\Portal 26.2 (READ-ONLY — do not modify anything; another workflow is editing unrelated mixins).

MANDATORY READING (read BOTH IN FULL before designing — they are the evidence base; every design claim must trace to them or to your own file reads):
1. migration/C2_IP_COMPAT_DEPTH.md — IP 1.21.3's 27-file compat surface at injection level (what upstream DID, per file, vs Sodium 0.6/Iris 1.8): the 4 sodium mechanism chains (context-swap #1/#2, culling #6→#5/#4, clipping #8→#7, freshness #9), the iris mixins + renderer trio, the facade contract table.
2. migration/C2_091_MAP.md — the phase-2 javap verdicts against the REAL 0.9.1/1.11.2 jars + OUR repo's substrate contract map (tracer 4). Key verdicts: RSM renderLists/renderDistance fields survive but the render-list model moved to SectionTree/per-frame regeneration; isSectionVisible → isBoxVisible(DDDDDD); OcclusionCuller async multi-visitor (redesign); Viewport testSection(FFF) replaced the testAab redirect point; the whole Sodium gl/ package GONE (RenderPipeline/UBO/bind-group + Vulkan backend — clipping = from-scratch subsystem); setupTerrain gained FogParameters+Matrix4f; Iris pipeline binds mostly survive, MixinLevelRenderer_BeforeIris needs a new FrameGraph anchor; OUR substrate: swap sites already bracket SecondaryWorldRenderCore.renderDestWorld, terrain setup split into extract/submit phases, our clip = view-space seamlessportals_ClipPlane via GlCommandEncoderClipMixin (C4-decided SUBMIT_ORDER_UNIFORM), our own TerrainSetupOverride yields to Sodium when present, no compat mixins json exists yet, ExperimentalCompatGate default-false with the warn/force honest gating.

BINDING CONSTRAINTS (from the project's standing rules — violating any is a design defect):
- Fidelity: IP-faithful where IP's mechanism still applies; NAMED deviations where 26.2/0.9.1 forces re-engineering — each deviation must state what IP contract it preserves.
- The flag-ON entity-portal substrate is the target (block-era warwa compat is S20-doomed; preserve coexistence with SodiumFogOverrideMixin until then).
- NO GUESSING: any uncertain runtime behavior becomes an instrument-first probe in the plan, not an assumption.
- Delivery = STAGED with a live runClientSodium round + Fable verify + suite green + commit at each stage boundary; the honest gating (warn + renderMode=none force) must remain correct at EVERY intermediate state — partial compat must never silently activate broken paths.
- The 8-leg suite cannot exercise sodium; the clientSodium run (-PsodiumRuntime) + user live rounds are the proof. Iris live testing needs -PirisRuntime + a shaderpack.
- Model the S18 invariants (dest passes run NO framegraph; per-dim renderer isolation; the LAYER-0 exposure class; visibleSections FIELD contract).`

phase('Design')
const [dStaging, dMechanism, dFidelity] = await parallel([
  () => agent(`DESIGNER A — STAGING-FIRST angle.\n${GROUND}\n\nYour angle: the optimal DELIVERY LADDER. Design C2 as the sequence of smallest independently-shippable, independently-verifiable, honestly-gated stages. For each stage: what activates (and how the gate/warn state evolves — e.g. when does the renderMode=none force lift, per-chain sub-gates?), what visibly works after it that didn't before (the live-round script), what stays broken and how the user is told, rollback story. Decide: does clipping ship disabled first? Is Iris a separate later ladder? Where do the C2-HELD deletions (ExperimentalCompatGate etc.) happen? What is the MINIMUM stage-1 that makes "Sodium + portals basically render" true? Also: which stages risk regressing NON-sodium runs (mixins that apply only when sodium loaded = zero risk; anything touching shared code = suite-gated) — engineer the ladder so plain-run regressions are structurally impossible where achievable.`, { label: 'design:staging', phase: 'Design', model: 'fable', effort: 'high', schema: DESIGN }),

  () => agent(`DESIGNER B — MECHANISM-FIRST angle.\n${GROUND}\n\nYour angle: the three REDESIGN-NOT-REMAP subsystems, designed concretely to injection level. (1) RENDER-LIST ISOLATION across portal passes under the SectionTree model: does the 2-field swap still achieve dest-world isolation given renderLists is regenerated per-frame from the tree — walk the 0.9.1 frame anatomy (extract: setupTerrain→async cull→createRenderLists; submit: drawChunkLayer) against OUR renderDestWorld bracket and design the swap that actually isolates (what must be swapped: renderTree? cullResults? the RSM wholesale per dest world — IP's ClientWorldLoader keeps per-world renderers... does OUR port hold per-dim SodiumWorldRenderers or ONE? — derive from the substrate map §2, and design accordingly, incl. the same-dim-portal case the doc's #3 exists for). (2) CULLING under async multi-visitor: design the portal-context capture (schedule-time snapshot vs task-local state vs accepting the lever-off deferral) + the testSection(FFF) re-derivation of canDetermineInvisibleWithCameraCoord + whether the #4 origin-retarget is even needed for OUR architecture (our bounded-BFS/VisibleSectionDiscovery is vanilla-path-only; under Sodium what actually builds the dest world's visible set in our bracket?). (3) CLIPPING transport: design the backend-agnostic uniform path (source injection seam for sodium:blocks GLSL under blaze3d ShaderManager; bind-group/UBO extension vs discard-based; upload site; OUR view-space convention) AND the honest fallback if stage-N ships without it (what does an unclipped dest terrain look like through an aperture — is renderMode=none per-chain gating needed until clipping lands?). For each: exact target classes/members from the 091 map, injection specs, failure modes, and the discriminating probe for every uncertainty.`, { label: 'design:mechanism', phase: 'Design', model: 'fable', effort: 'high', schema: DESIGN }),

  () => agent(`DESIGNER C — SUBSTRATE-FIDELITY angle.\n${GROUND}\n\nYour angle: the IP-contract-to-OUR-architecture mapping, and the fidelity ledger. Walk the phase-1 doc's facade CONTRACT table call site by call site: for each SodiumInterface/IrisInterface invoker method, what IP's core expected, where OUR port's call site sits (substrate map §1), and what the 0.9.1 implementation of OnSodiumPresent/OnIrisPresent must now DO to honor that contract in OUR frame anatomy (SecondaryWorldRenderCore renderDestWorld, no nested framegraph, extract/submit split, per-dim renderer isolation, the ImmPtlViewArea C3 rebuild, ImmPtlClientChunkMap hooks). Decide per IP compat file: PORT-AS-IS / RETARGET (new sig, same mechanism) / RE-EXPRESS (same contract, our mechanism) / DEFER-DORMANT (ledger why) / DROP (IP mechanism has no meaning in our architecture — prove it). Special attention: the Iris renderer trio + MixinLevelRenderer_BeforeIris vs our S18 render model (is IP's Iris stencil renderer even the right shape when dest passes are decomposed? or is Iris compat in OUR architecture a different, smaller thing — pipeline-swap + clip + detection — with the trio DEFERRED?); the mixin config plugin (new imm_ptl_compat.mixins.json with IP's substring gate vs extending SeamlessMixinConfigPlugin — interaction with the qouteall flag-ON weave rule!); the D3/flag matrix (every new mixin: what happens flag-OFF, sodium-present, both); C7 NeoForge parity notes; what dies at S20 vs survives.`, { label: 'design:substrate-fidelity', phase: 'Design', model: 'fable', effort: 'high', schema: DESIGN }),
])

phase('Synthesize')
const synthesis = await agent(`SYNTHESIS JUDGE — produce the governing C2 design doc.\n${GROUND}\n\nTHREE INDEPENDENT DESIGNS to judge and synthesize (score each dimension: evidence-grounding, staging safety, mechanism concreteness, fidelity discipline; take the best of each, name conflicts and RESOLVE them with evidence — re-read the two ground-truth docs where designs disagree):\n\n=== DESIGN A (staging-first) ===\n${JSON.stringify(dStaging)}\n\n=== DESIGN B (mechanism-first) ===\n${JSON.stringify(dMechanism)}\n\n=== DESIGN C (substrate-fidelity) ===\n${JSON.stringify(dFidelity)}\n\nWRITE the synthesized governing design to C:\\Users\\warwa\\ModDev\\Portals\\Portal 26.2\\migration\\C2_DESIGN.md (you have Write access; UTF-8; this is the ONE file you may create — modify nothing else). Structure: §0 the decision record (what was taken from which design + resolved conflicts); §1 the stage ladder (each stage: goal, exact deliverables to injection level, gate/warn evolution, verify plan incl. what Fable lenses check, the live-round script, rollback); §2 the per-IP-file disposition table (27 files: PORT-AS-IS/RETARGET/RE-EXPRESS/DEFER-DORMANT/DROP + one-line why + stage assignment); §3 the redesign specs (render-list isolation, culling, clipping transport — concrete enough for an Opus implementation agent to execute without re-deriving); §4 the open questions with their discriminating probes (instrument-first items) and any USER-decision points; §5 the fidelity/deviation ledger entries this creates; §6 D3/flag matrix + C7/S20 interactions. Return a SHORT summary (the stage list + the key resolved conflicts + open questions) — the doc carries the detail.`, { label: 'synthesize:c2-design', phase: 'Synthesize', model: 'fable', effort: 'xhigh', schema: {
  type: 'object',
  properties: {
    summary: { type: 'string' },
    stageList: { type: 'array', items: { type: 'string' } },
    resolvedConflicts: { type: 'array', items: { type: 'string' } },
    openQuestions: { type: 'array', items: { type: 'string' } },
    docWritten: { type: 'boolean' },
  },
  required: ['summary', 'stageList', 'resolvedConflicts', 'openQuestions', 'docWritten'],
} })

return { synthesis, scores: { staging: dStaging?.openQuestions?.length, mechanism: dMechanism?.openQuestions?.length, fidelity: dFidelity?.openQuestions?.length } }