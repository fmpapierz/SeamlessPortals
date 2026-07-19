export const meta = {
  name: 's19-commons-tail',
  description: 'S19 commons tail: EnderEye/dfu-datafixer/BossBar/Splash mixins (1:1 ports) + Fable verify',
  phases: [
    { title: 'Implement', detail: '1 Opus agent, 4 small 1:1 mixin ports', model: 'opus' },
    { title: 'Verify', detail: '3 parallel Fable lenses (targets / fidelity+D3 / S19-E hand-folds)', model: 'fable' },
  ],
}

const REPORT = {
  type: 'object',
  properties: {
    report: { type: 'string' },
    changedFiles: { type: 'array', items: { type: 'string' } },
    compileGreen: { type: 'boolean' },
  },
  required: ['report', 'changedFiles', 'compileGreen'],
}

const VERDICT = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['PASS', 'PASS_WITH_CORRECTIONS', 'FAIL'] },
    findings: { type: 'array', items: { type: 'object', properties: {
      severity: { type: 'string', enum: ['BLOCKER', 'CORRECTION', 'NOTE'] },
      file: { type: 'string' },
      summary: { type: 'string' },
      evidence: { type: 'string' },
      suggestedFix: { type: 'string' },
    }, required: ['severity', 'summary', 'evidence'] } },
    report: { type: 'string' },
  },
  required: ['verdict', 'findings', 'report'],
}

phase('Implement')
const impl = await agent(`S19 COMMONS TAIL — port the four remaining held-out IP commons/client mixins, 1:1.

Repo: C:\\Users\\warwa\\ModDev\\Portals\\Portal 26.2 (branch claude/nifty-kepler). Windows; gradle = .\\gradlew.bat from repo root, ALWAYS --console=plain. IP 1.21.3 upstream (fidelity baseline): C:\\Users\\warwa\\ModDev\\ImmersivePortalsMod. Decompiled vanilla 26.2 (MOJANG names): C:\\Users\\warwa\\ModDev\\mc262-ref. 26.2 ships UNOBFUSCATED — for bytecode ground truth javap the real merged jar (find it under the loom/moddev caches; the repo's earlier port-notes name the technique) or read mc262-ref source.
STANDING RULES: NO GUESSING — every mixin target (method name, descriptor, injection point) must be grounded in mc262-ref source or javap; cite evidence per target in your report. 1:1 IP port discipline: keep IP's class body shape; name any 26.2-forced delta in an in-code comment. DO NOT commit/push. DO NOT run any game. End with .\\gradlew.bat :common:compileJava :fabric:compileJava :neoforge:compileJava --console=plain GREEN.

THE FOUR ITEMS (from the S19E handoff §1 — the S16 held-out commons cargo; none exist in the repo yet, verify that first with a glob/grep):
1. MixinEnderEyeItem_CVB — IP's mixin on net.minecraft.world.item.EnderEyeItem (find IP's original under qouteall/imm_ptl/core/mixin/ — likely common/mixin/MixinEnderEyeItem or similar; read what it does — it is part of the CVB = "custom vanilla behavior" family the port suffixes _CVB). Port to the 26.2 target: locate EnderEyeItem in mc262-ref, find the corresponding method(s), verify the injection anchor still exists with the same semantics.
2. dfu MixinItemStackComponentizationFix — IP's datafixer mixin (wand/command-stick legacy item datafix). The handoff says "1:1, zero changes, needs its AW line". Find IP's original + ITS accesswidener/AT line in IP's resources; add the corresponding line to common/src/main/resources/seamlessportals.accesswidener (check the existing AW file's header format + the neoforge AT twin if the repo keeps one — look at how the S19-C MoreTab AW/AT entries were done, port-note §5 precedent) if the 26.2 target member is actually still inaccessible (verify against mc262-ref/javap first — 26.2 relaxed many members; do not add a dead AW line without proof it is needed).
3. MixinBossHealthOverlay_CVB — IP's boss-bar overlay mixin (client). Locate IP original + purpose; find the 26.2 BossHealthOverlay equivalent (the 26.2 GUI/render rewrite may have moved/renamed the render method — ground the new anchor).
4. MixinSplashManager_CVB — IP's splash-text mixin (client; IP adds its own splash lines). Locate IP original; 26.2 SplashManager target check.

WIRING: register each in the correct existing mixins json (read common/src/main/resources/seamlessportals-*.mixins.json set — the ip-core-common vs ip-client split; follow where IP registers each of these in ITS jsons: imm_ptl.mixins.json vs imm_ptl_client.mixins.json). The SeamlessMixinConfigPlugin weave-gates qouteall.* mixins flag-ON automatically — confirm these four SHOULD be flag-gated (they are IP-era features: yes, standard qouteall rule applies; note any that the D3 lens might dispute, e.g. a datafixer that must run in both flag states to load flag-ON-created saves — THINK about this one: the wand/stick ITEMS are registered unconditionally (D3 seam, port-note §1.1) so their legacy-data fix may also need to weave flag-OFF; check what the fix actually does and whether flag-OFF worlds can contain wand/stick stacks (they CAN — D3 save-parity). If so, the mixin needs a D3_UNCONDITIONAL carve-out in SeamlessMixinConfigPlugin (there is an existing D3_UNCONDITIONAL_WORLDGEN_ACCESSORS precedent — read it) — implement per your evidence and DOCUMENT the decision.
Also: if IP's originals carry lang/assets dependencies (splash lines list?), port those too.

Report per item: IP original path+shape, 26.2 target evidence, what you registered where, any named deltas, the AW decision evidence, the D3/gating decision evidence.`, { label: 'impl:commons-tail', phase: 'Implement', model: 'opus', schema: REPORT })

if (!impl || !impl.compileGreen) { log('Commons tail impl not green'); return { impl, aborted: true } }
log(`Commons tail: ${impl.changedFiles.length} files changed`)

phase('Verify')
const VR = `Repo: C:\\Users\\warwa\\ModDev\\Portals\\Portal 26.2 (branch claude/nifty-kepler).

*** THE DIFF UNDER REVIEW IS COMMITTED, NOT WORKING-TREE. \`git diff\` shows NOTHING — do not
conclude there is nothing to review. Inspect it with: ***
  git show 28999ce   = the S19 commons tail (the 4 new mixins + the AW line + the NeoForge AT twin
                       + the peripheral mixins-json entries + the SeamlessMixinConfigPlugin carve-out)
  git show b2187ad   = the S19-E wiring (already verified by 3 lenses EXCEPT the hand-applied folds — lens C)
Read the CURRENT file contents too (git show gives the diff; the files on disk are the truth).

IP upstream (fidelity baseline): C:\\Users\\warwa\\ModDev\\ImmersivePortalsMod
Decompiled vanilla 26.2 (MOJANG names; 26.2 ships UNOBFUSCATED — dev name IS prod name): C:\\Users\\warwa\\ModDev\\mc262-ref

ADVERSARIAL verifier: find what is WRONG, do not confirm. DO NOT TRUST the implementation report
below or its cited line numbers — RE-DERIVE every target yourself. A wrong-but-compiling mixin is
exactly the failure class this pass exists to catch (stage track record: 7 rounds, 4 with real
pre-ship catches). Ground every finding in file reads / git show / javap / IP source side-by-side.
Do NOT modify files, do NOT run gradle builds, do NOT commit.

IMPLEMENTATION REPORT (treat as CLAIMS to be audited, not facts):\n${impl.report}`
const [vA, vB, vC] = await parallel([
  () => agent(`VERIFY LENS A — mixin-target/bytecode soundness. ${VR}\n\nFor EACH of the four new mixins (common/end_portal/MixinEnderEyeItem_CVB, common/dfu/MixinItemStackComponentizationFix, client/MixinBossHealthOverlay_CVB, client/MixinSplashManager_CVB under common/src/main/java/qouteall/imm_ptl/peripheral/mixin/): re-derive the target independently (mc262-ref source read + javap where descriptors matter): does the target method exist with exactly the claimed signature; is the @At anchor unique/unambiguous (COUNT matching INVOKEs/constants/RETURN points yourself — do not accept "single exit" on faith); inject/redirect require semantics (what is injectors.defaultRequire in seamlessportals-ip-peripheral.mixins.json? would a drifted anchor hard-fail or SILENTLY no-op?); handler signature correctness (CallbackInfoReturnable types; the dfu mixin's private-static target + ItemStackData param); client-vs-common json placement vs the target's dist; THE AW/AT PAIR: is \`accessible class net/minecraft/util/datafix/fixes/ItemStackComponentizationFix$ItemStackData\` syntactically valid for this AW file's version header, and is that class REALLY private-static in 26.2 (prove it) or is the AW line DEAD? Same for the NeoForge accesstransformer.cfg twin (a malformed AT line = a neoforge build BLOCKER); mixin-into-datafixer legality (DFU classes load EARLY — is SeamlessMixinConfigPlugin reliably ready before ItemStackComponentizationFix classloads? does IP hit the identical constraint?); 26.2 GUI-rewrite drift in the BossHealthOverlay/SplashManager anchors — for SplashManager specifically verify the @Shadow of a PRIVATE STATIC method (literalSplash) is legal as written, that the immutable-list reassign cannot be clobbered by a later vanilla call, and that the vanilla splash strings IP removes ("Euclidian!", "Slow acting portals!") ACTUALLY EXIST in 26.2's splashes resource (if renamed/removed, the removal silently no-ops = a real fidelity defect).`, { label: 'verify:mixin-targets', phase: 'Verify', model: 'fable', effort: 'high', schema: VERDICT }),

  () => agent(`VERIFY LENS B — IP fidelity + THE D3 CARVE-OUT (the priority target of this pass). ${VR}\n\n(1) Side-by-side each of the four mixin bodies vs its IP original (under ImmersivePortalsMod/src/main/java/qouteall/imm_ptl/peripheral/mixin/): statement-exact modulo the NAMED deltas? Any silently dropped branch, reordered guard, or changed condition? The claimed deltas are: EnderEye = Level.isClientSide field -> isClientSide() accessor; Splash = the whole List<Component> + immutable-reassign rewrite. Verify each is genuinely FORCED by 26.2 and behavior-preserving, and that nothing ELSE changed.\n\n(2) **THE D3 CARVE-OUT — audit this hardest.** The diff adds D3_UNCONDITIONAL_ITEM_DATAFIX to common/src/main/java/com/warwa/seamlessportals/mixin/SeamlessMixinConfigPlugin.java, making MixinItemStackComponentizationFix weave in BOTH flag states (every other qouteall.* mixin is flag-ON-gated). Stated reasoning: the wand/command-stick items + DataComponentTypes are registered UNCONDITIONALLY (D3 save-parity, port-note S19 §1.1), flag-OFF is the SHIPPING DEFAULT, so gating the datafix flag-ON would skip it on the primary migration path (an IP 1.20.4 world opened here) and sweep the stored command/mode into minecraft:custom_data. INDEPENDENTLY VERIFY — do not accept the argument as given:\n  a. Are the items + DataComponentTypes REALLY registered unconditionally? Read the registration sites.\n  b. Is flag-OFF really the shipping default? Read EntityPortalsFlag + its config source.\n  c. Does the datafix ACTUALLY fire only for pre-1.20.5 saves? Trace when ItemStackComponentizationFix runs; confirm the mixin cannot fire on modern saves.\n  d. Is the handler truly byte-neutral for non-IP items? Confirm the is("immersive_portals:...") guards dominate EVERY mutation and nothing runs before them.\n  e. **THE INVERSE RISK the impl agent never argued**: does weaving flag-OFF introduce a hazard? Does the handler touch ANY class that is flag-ON-only-initialized (a static null flag-OFF)? Does it reference IPGlobal/config state? A NoClassDefFoundError or NPE DURING DATAFIXING would corrupt or refuse a world load — that is a BLOCKER class. Prove it clean or find it.\n  f. Is the carve-out set membership an EXACT FQN match for the mixin's real package+name? A typo silently reverts it to flag-ON-gated (silent-no-op class).\n\n(3) The other three: confirm flag-ON gating is right (live engine/client behavior, not save data) and that NEITHER client mixin touches anything save- or world-state-visible. The splash + boss-fog mixins load at/near the TITLE SCREEN — walk the pinned-flag-OFF TITLE-CARD gametest and the 8-leg suite for new exposure.\n\n(4) Every new comment/javadoc: no overclaims (the S19-D lesson — comments get verified too). The carve-out javadoc makes strong factual claims; check each.`, { label: 'verify:fidelity-d3', phase: 'Verify', model: 'fable', effort: 'high', schema: VERDICT }),

  () => agent(`VERIFY LENS C (NEW) — audit the HAND-APPLIED S19-E folds, which no lens has ever seen. ${VR}\n\nCONTEXT: commit b2187ad (S19-E wiring) WAS verified by three Fable lenses — but the orchestrator then hand-applied those lenses' corrections and committed WITHOUT re-verifying its own edits. Those hand-edits are unaudited surface. Inspect them (git show b2187ad + the files on disk):\n\n1. common/src/main/resources/assets/immersive_portals/lang/en_us.json — two ADDED keys: text.autoconfig.immersive_portals.option.crossPortalEntityClipMechanism and its .@Tooltip. VERIFY: is the JSON still valid (no trailing comma / duplicate key)? Is the key spelling EXACTLY what cloth derives for that field — RE-DERIVE the key format from the real cloth-config-fabric-26.2.155.jar in the gradle cache (its ConfigScreenProvider / DefaultGuiTransformers / i18n helper) combined with @Config(name="immersive_portals"), the field name, and its @ConfigEntry.Category. An off-by-one in the key means the raw translation key STILL shows and the fold FAILED to fix the reported defect. Also sweep the other non-excluded IPConfig fields for any further missing lang pairs the first lens may have missed.\n2. fabric/build.gradle, common/build.gradle, neoforge/build.gradle — the three Shedaniel repo blocks gained content { includeGroupByRegex '...' }. VERIFY THE GROOVY ESCAPING AS IT SITS ON DISK (it was edited twice — written wrong, then patched) and that the regex actually matches the group 'me.shedaniel.cloth'. A wrong regex = cloth silently fails to resolve on a CLEAN-CACHE build — a real build-breaking class that the current warm cache HIDES. This is the highest-risk item in this lens.\n3. fabric/build.gradle — the reworded iris/sodium runtime-scoping comment: is it now factually true (iris's own fabric.mod.json depends range vs what gradle actually pulls)?\n4. fabric/.../ModMenuIntegration.java + common/.../IPModMenuConfigEntry.java javadoc rewrites + the softened gate-on comment in fabric/.../SeamlessPortalsClientFabric.java: verify each new claim against the real jars/source — especially that AutoConfigClient.getConfigScreen really exists with that signature in 26.2.155, and the claim that flipping ExperimentalCompatGate would CCE at the duck casts.\n5. The deleted empty directory common/src/main/java/me — confirm nothing references it and no build script globs it.\n\nFor anything unsettleable read-only (e.g. clean-cache dependency resolution), say so EXPLICITLY and name the exact command the orchestrator should run to settle it.`, { label: 'verify:s19e-handfolds', phase: 'Verify', model: 'fable', effort: 'high', schema: VERDICT }),
])

return { impl: { report: impl.report, changedFiles: impl.changedFiles }, vA, vB, vC }