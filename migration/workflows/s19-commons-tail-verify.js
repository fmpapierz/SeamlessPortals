export const meta = {
  name: 's19-commons-tail-verify',
  description: 'Verify-only: adversarially audit commit 28999ce (commons tail) + the hand-applied S19-E folds in b2187ad',
  phases: [
    { title: 'Verify', detail: '3 parallel Fable lenses over the committed diffs', model: 'fable' },
  ],
}

const VERDICT = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['PASS', 'PASS_WITH_CORRECTIONS', 'FAIL'] },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['BLOCKER', 'CORRECTION', 'NOTE'] },
          file: { type: 'string' },
          summary: { type: 'string' },
          evidence: { type: 'string' },
          suggestedFix: { type: 'string' },
        },
        required: ['severity', 'summary', 'evidence'],
      },
    },
    report: { type: 'string' },
  },
  required: ['verdict', 'findings', 'report'],
}

const BASE = `Repo: C:\\Users\\warwa\\ModDev\\Portals\\Portal 26.2 (branch claude/nifty-kepler).
THE DIFF UNDER REVIEW IS COMMITTED, not working-tree: inspect it with
  git show 28999ce            (the S19 commons tail: 4 mixins + AW + AT + peripheral mixins json + the config-plugin carve-out)
  git show b2187ad            (the S19-E wiring — already verified by 3 lenses, EXCEPT the hand-folds; see lens 3)
IP 1.21.3 upstream (fidelity baseline): C:\\Users\\warwa\\ModDev\\ImmersivePortalsMod
Decompiled vanilla 26.2 (MOJANG names, dev name == prod name — 26.2 ships UNOBFUSCATED): C:\\Users\\warwa\\ModDev\\mc262-ref

YOU ARE AN ADVERSARIAL VERIFIER. Your job is to find what is WRONG, not to confirm. Do NOT trust the
implementation agent's report or its cited line numbers — RE-DERIVE every target yourself from
mc262-ref / javap / IP source. A wrong-but-compiling mixin is exactly the failure class this pass exists
to catch (track record: 7 verify rounds this stage, 4 with real pre-ship catches).

DO NOT modify any file. DO NOT commit. DO NOT run runClient or any game. DO NOT run gradle builds
(the orchestrator re-runs the gates). Read-only analysis only.`

phase('Verify')
const [targets, fidelity, folds] = await parallel([
  () => agent(`VERIFY LENS 1 — mixin-target / bytecode soundness of the four new commons-tail mixins.
${BASE}

The four (all under common/src/main/java/qouteall/imm_ptl/peripheral/mixin/):
  common/end_portal/MixinEnderEyeItem_CVB.java
  common/dfu/MixinItemStackComponentizationFix.java
  client/MixinBossHealthOverlay_CVB.java
  client/MixinSplashManager_CVB.java

For EACH, independently re-derive and report:
1. Does the target method exist in 26.2 with EXACTLY the claimed signature/descriptor? (read mc262-ref;
   javap the real merged jar where a descriptor is load-bearing). Name the file:line you verified against.
2. Is the @At anchor unique and unambiguous? COUNT the matching call sites / constants / RETURN points
   yourself — do not accept "single exit" on faith. Check the mixins json's injectors.defaultRequire and
   whether each injector would hard-fail or silently no-op if the anchor drifted.
3. Handler signature correctness: for @Inject(RETURN) on a method with a return value, is the
   CallbackInfoReturnable type right? For the dfu mixin, is the private-static-target + ItemStackData
   param type actually compilable/loadable the way it is written?
4. THE AW/AT PAIR: is 'accessible class net/minecraft/util/datafix/fixes/ItemStackComponentizationFix$ItemStackData'
   syntactically valid for this AW file's version header, and is the class REALLY private-static in 26.2
   (prove it) — or is the AW line DEAD (26.2 relaxed it)? Same question for the NeoForge AT twin. If the
   widen is unnecessary, say so; if the AW is needed but the AT twin is malformed, that is a BLOCKER for
   the neoforge build.
5. DATAFIXER LOAD-ORDER LEGALITY: DFU classes load very early. Does the mixin config's plugin
   (SeamlessMixinConfigPlugin) reliably run before ItemStackComponentizationFix is classloaded? Does IP hit
   the identical constraint (check IP's own registration)? A plugin that is not ready at that point is a
   real crash class.
6. 26.2 GUI/renderer-rewrite drift on the two CLIENT mixins: BossHealthOverlay.shouldCreateWorldFog and the
   SplashManager rewrite (List<Component>, non-final field, apply() reassigning to an immutable
   List.copyOf, the @Shadow'd private static literalSplash). Verify the shadow of a PRIVATE STATIC method
   is legal as written and that the field reassignment cannot race or be overwritten by a later vanilla
   call. Confirm the splash strings IP removes ("Euclidian!", "Slow acting portals!") actually exist in
   26.2's vanilla splashes resource — if they were renamed/removed, the removal silently no-ops (a real
   fidelity defect worth a NOTE).`, { label: 'verify:mixin-targets', phase: 'Verify', model: 'fable', effort: 'high', schema: VERDICT }),

  () => agent(`VERIFY LENS 2 — IP fidelity + the D3 carve-out (THE PRIORITY TARGET OF THIS WHOLE PASS).
${BASE}

(1) Side-by-side EACH of the four mixin bodies against its IP 1.21.3 original (find them under
    ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/peripheral/mixin/). Statement-exact modulo the
    NAMED deltas? Any silently dropped branch, reordered guard, or changed condition? The claimed deltas
    are: EnderEye = Level.isClientSide field -> isClientSide() accessor; Splash = the whole
    List<Component>/immutable rewrite. Verify each delta is genuinely FORCED by 26.2 and is
    behavior-preserving, and that nothing ELSE silently changed.

(2) **THE D3 CARVE-OUT — audit this hardest.** The diff adds D3_UNCONDITIONAL_ITEM_DATAFIX to
    common/src/main/java/com/warwa/seamlessportals/mixin/SeamlessMixinConfigPlugin.java, making
    MixinItemStackComponentizationFix weave in BOTH flag states (every other qouteall.* mixin is
    flag-ON-gated). The stated reasoning: the wand/command-stick items + their DataComponentTypes are
    registered UNCONDITIONALLY (D3 save-parity, port-note S19 §1.1), flag-OFF is the SHIPPING DEFAULT, so
    gating the datafix flag-ON would skip it on the primary migration path (an IP 1.20.4 world opened in
    this mod) and sweep the stored command/mode into minecraft:custom_data.
    INDEPENDENTLY VERIFY, do not accept the argument as given:
    a. Are the items + DataComponentTypes REALLY registered unconditionally? Read the registration sites.
    b. Is flag-OFF really the shipping default? Read EntityPortalsFlag and its default/config source.
    c. Does the datafix ACTUALLY fire only for pre-1.20.5 saves? Trace when
       ItemStackComponentizationFix runs and confirm the mixin cannot fire on modern saves.
    d. Is the handler truly byte-neutral for non-IP items? Read it; confirm the is("immersive_portals:...")
       guards dominate every mutation and that nothing runs before them.
    e. THE INVERSE RISK (the part the impl agent did NOT argue): does weaving flag-OFF introduce any
       hazard? Does the handler touch ANY class that is flag-ON-only-initialized (a static that is null
       flag-OFF)? Does it reference IPGlobal/config state? A NoClassDefFoundError or NPE during
       DATAFIXING would corrupt or refuse a world load — that is a BLOCKER class. Prove it clean or find it.
    f. Is the carve-out set membership an EXACT FQN match for the mixin's real package+name?
       A typo silently reverts it to flag-ON-gated (a silent-no-op class).

(3) The other three: confirm flag-ON gating is correct for them (they are live engine/client behavior,
    not save-data), and specifically that NEITHER client mixin touches anything save-visible or
    world-state-visible. The splash + boss-fog mixins load at/near the TITLE SCREEN — walk the pinned
    flag-OFF TITLE-CARD gametest and the 8-leg suite for any new exposure.

(4) Every new in-code comment and javadoc: no overclaims (the S19-D lesson — comments get verified too).
    The carve-out javadoc makes strong factual claims; check each one.`, { label: 'verify:fidelity-d3', phase: 'Verify', model: 'fable', effort: 'high', schema: VERDICT }),

  () => agent(`VERIFY LENS 3 — audit the HAND-APPLIED S19-E folds (these were never machine-verified).
${BASE}

CONTEXT: commit b2187ad (S19-E wiring) WAS verified by three Fable lenses — but the orchestrator then
hand-applied the lenses' corrections and committed WITHOUT re-verifying its own edits. Those hand-edits
are the unaudited surface. Inspect them in b2187ad (and against the lens findings they were meant to fix):

1. common/src/main/resources/assets/immersive_portals/lang/en_us.json — two ADDED keys:
   text.autoconfig.immersive_portals.option.crossPortalEntityClipMechanism (+ its .@Tooltip).
   VERIFY: is the JSON still valid (no trailing-comma/duplicate-key breakage)? Is the key spelling EXACTLY
   what cloth's autoconfig derives for that field (re-derive the key format from the real
   cloth-config-fabric-26.2.155.jar's ConfigScreenProvider/DefaultGuiTransformers + the @Config name +
   the field name + its @ConfigEntry.Category — an off-by-one in the key = the raw key still shows, i.e.
   the fold FAILED to fix the reported defect). Also check the OTHER non-excluded IPConfig fields for any
   further missing lang pairs the first lens may have missed.
2. fabric/build.gradle, common/build.gradle, neoforge/build.gradle — the three Shedaniel repo blocks
   gained content { includeGroupByRegex 'me\\\\.shedaniel.*' }. VERIFY the Groovy escaping is correct as it
   sits ON DISK (it was edited twice — once wrong, once corrected) and that the regex actually matches the
   group 'me.shedaniel.cloth'. A wrong regex = cloth silently fails to resolve on a clean-cache build.
   This is a REAL build-breaking class and the current state was never re-resolved from an empty cache.
3. fabric/build.gradle — the reworded iris/sodium runtime-scoping comment. Is it now factually true
   (iris's own fabric.mod.json depends range vs what gradle actually pulls)?
4. fabric/.../ModMenuIntegration.java + common/.../IPModMenuConfigEntry.java javadoc rewrites, and the
   softened gate-on comment in fabric/.../SeamlessPortalsClientFabric.java. Verify each new claim against
   the real jars/source — especially 'AutoConfigClient.getConfigScreen' (does that class+method really
   exist with that signature in 26.2.155?) and the claim that flipping ExperimentalCompatGate would CCE
   at the duck casts.
5. The deleted empty directory common/src/main/java/me — confirm nothing references it and no build script
   globs it.

For anything you cannot settle read-only (e.g. clean-cache dependency resolution), say so explicitly and
name the exact command the orchestrator should run to settle it.`, { label: 'verify:s19e-handfolds', phase: 'Verify', model: 'fable', effort: 'high', schema: VERDICT }),
])

return { targets, fidelity, folds }
