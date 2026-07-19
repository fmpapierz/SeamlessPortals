# S19-E HANDOFF — real Sodium/Iris/cloth 26.2 wiring + the S19 tail

**Read this first, then `migration/port-notes/S19-peripheral-tail.md` (§1-§7 = the S19 ledger
of record) and `migration/S19_HANDOFF.md` §0 (ALL standing rules — unchanged and still
binding: NO GUESSING/instrument-first, Opus for mechanical agent tasks / Fable only for
verify+design, Fable-verify every fix pre-ship, commit+push every increment to
`claude/nifty-kepler`, the 8-leg suite green before every READY with an honest
cannot-exercise statement, the IP side-by-side court, the 4-scalp gate-audit rule, the
mid-packet-frame rule, the Java process rules).**

## 1. WHERE S19 STANDS (2026-07-19)

| Part | State | Commits |
|---|---|---|
| S19-A wand + creative tab | **CLOSED, live-proven** ("all wands work good"; tab ✓; crossing ✓) | `6ce907d`,`67fabcb`,`b5ba20d` |
| S19-B ModMenu config GUI | Compile shape landed; **RE-OPENED by ground truth** — cloth-config 26.2.155+fabric EXISTS (§7): the real dep + entrypoint swap are now landable | `7cb134f` |
| S19-C dim stack | **CLOSED, live-proven** ("all worked"; 2 live GUI defects fixed probe-first) | `93ecf65` |
| S19-D alternate dims | **LANDED, live-proven core** (biome variety ✓ fog ✓ chaos ✓ stack ✓ regression ✓); 3 user-routed polish items §6.2 | `f0b9df1` |
| S19-E compat layers | **NEXT — user-directed design change, see §2** | — |
| Peripheral commons tail | Remaining: MixinEnderEyeItem_CVB (end-portal CVB), dfu MixinItemStackComponentizationFix (wand/stick legacy datafixer — 1:1, zero changes, needs its AW line), MixinBossHealthOverlay_CVB, MixinSplashManager_CVB | — |

Verify track record this stage: **7 rounds, 4 with real pre-ship catches** (the flag-OFF
GAMEMASTER privilege escalation; the missing-setLineWidth first-frame crash + circle
NaN-normal; the flag-OFF chaos-NPE + the black-fog ambience omission + piglin half; plus the
S19-C children()/geometry findings). The pattern holds: verify EVERYTHING before the user
runs it.

## 2. S19-E — THE USER DIRECTIVE (2026-07-18): real 26.2 artifacts, not the old stubs

Pinned versions (port-note §7, user-supplied links + fetch): **Sodium 0.9.1 for Fabric 26.2**
(full release Jul 8), **Iris 1.11.2 for Fabric 26.2** (full release Jul 8), **ModMenu
20.0.0-beta.4** (already a real `:fabric` dep), **Cloth Config 26.2.155+fabric** (release
Jun 18).

The plan:
1. **Deps**: add Sodium 0.9.1 + Iris 1.11.2 (+ cloth-config 26.2.155) to the fabric module
   (the modmenu `implementation` precedent; mavens: Modrinth maven / shedaniel / caffeinemc).
   Decide per-dep implementation-vs-compileOnly (Sodium/Iris should probably be
   modCompileOnly-style — NOT in the default dev runtime, or every runClient runs Sodium;
   consider a separate run config for Sodium testing).
2. **RETIRE the F21 sodium/iris ipStubs** (real classes + stubs on one classpath = the
   PROVEN shadowing hazard — the S19-B ModMenu-stub catch). The stub-consuming IP compat
   files (SodiumInterface etc.) were written against Sodium 0.6-era internals — they will
   NOT compile against 0.9.1: assess per-file (retarget the trivial, hold the deep ones
   behind C2).
3. **cloth-config swap (the S19-B re-entry checklist, port-note §4)**: replace the F21
   AutoConfig functional no-op (common/src/main/java/me/shedaniel/**) with the REAL dep —
   CAREFUL: the no-op is SHIPPED RUNTIME code (IPConfig.register runs through it); this is a
   real migration step with its own Fable-verify. Then swap IPModMenuConfigEntry into the
   fabric.mod.json "modmenu" list (one line; the class is ready and unwired). Live test:
   Mods → Seamless Portals → config = the real IP cloth screen.
4. **On*Present detection** against the real mod ids + honest flag-ON gating: with real
   Sodium in a dev run config the flag-ON+Sodium interaction becomes TESTABLE (expectation:
   the renderer substrate conflicts — the standing warning "users must NOT run Sodium with
   entityPortals ON" holds until C2). Land presence detection + a loud incompat warning or
   disable.
5. **THE C2 DEPTH QUESTION (ask the user at S19-E open)**: IP's sodium compat has NO 26.2
   ground truth (upstream never ported past 1.21.3-era Sodium 0.6) — real Sodium 0.9.1
   render-path compat = original engineering under C2 (COVERAGE INFO-3 wants the full-depth
   27-file pass first). Landing wiring/detection/gating now vs entering C2 now is the
   user's call.

## 3. THE POLISH LADDER (S19 additions on top of S19_HANDOFF §3's list)

**From the S19-D live round (§6.2, user-routed, instrument-first):**
- **THE DIM-PERSISTENCE GAP (top item)**: alt dims DO NOT survive world reopen (relog inside
  one → dumped to OW, "unknown dimension" everywhere, missing OW clouds after). Diagnosis in
  §6.2: the port landed DimLib's load-window but NOT its PERSISTENCE half (record added dims
  → re-add inside SERVER_DIMENSIONS_LOAD every boot). Fix = port that half from the on-disk
  dimlib-source (`C:\Users\warwa\ModDev\Immersive Portals\ImmersivePortals1.21.11\dimlib-source`).
  Re-check the missing-clouds tail after (S18 cloud-renderer null-texture class).
- **Bright dims darken at night** — candidate: the added `ambient_light_color #0a0a0a` /
  timeline scaling over ambient_light 15 (§6.2 mechanisms; discriminate live first).
- **void/bright_void not empty** (terrain+biomes where 1-air-layer flat was expected) —
  candidate mechanisms in §6.2; instrument the created stem's generator at registration.
- **R13g-PHASE-2** (runtime add of NEW alt dims via /portal dimension_stack — the deferred
  dynamic half; §6 has the client-sync analysis: both dimension_types always login-synced,
  so phase-2 = LevelStem mutation + mid-session ServerLevel construction + the EXISTING
  DimensionIntId resync).
- S19-A carried: same-dim wand-overlay absence (spectral-glow class); the S18 §3.5 JVM C2
  JIT crash mitigation (production jars unmitigated — release-packaging item; if the silent
  hard-crash recurs in dev, a SECOND hot method needs the same CompileCommand flag — read
  the new hs_err's CompileTask line).
- Everything in S19_HANDOFF §3 (vehicle fix from the capture result, hand sliver, spectral
  glow, melee-through-portal enhancement, etc.) stands.

## 4. WATCH ITEMS / STANDING LEDGERS (new since S18)

- **The unbound-holder dependency (§6.1, empirically proven)**: the dim load-window works
  ONLY because fabric-registry-sync binds holders at register RETURN (upstream DimLib has
  the identical dependency). registry-sync must never leave the runtime; S20 hardening
  candidate = explicit bindValue.
- **C7 NeoForge landmine list** (all mixin- or class-load-reachable flag-ON): PeripheralModMain.TAB
  static-init (fabric-api), DimensionStackAPI + DimensionAPI EventFactory static-inits,
  MixinMinecraftServer_DimLib. All need loader-neutral re-seams at C7.
- **B11/S20 sweep additions**: putLineToLineStrip + renderSphere (unreachable, width-less),
  renderPortalAreaGridNew (caller-less), ParticleEnginePortalSkipMixin (S18),
  IENoiseGeneratorSettings (commented-out shell), DimensionTemplate.init (kept-unwired),
  the flag-OFF latent CCE via portal_children (dies with the flag), the D3 flag-OFF
  degradations (experimental screen on alt-dim reopen; no weather mirror; void darkness).
- The 8-leg suite CANNOT exercise: GUI anything, alt-dim creation/generation/visuals
  (dimension_type DECODE is suite-proven), wand flows, RPC permissions — say so at READYs.

## 5. AFTER S19-E: close S19 → S20 → polish → C2/C7 (ask-first)

S19 close-out = the commons tail (§1 table) + EXECUTION_PLAN S19 CLOSED block + memory
consolidation + a fresh S20 handoff. S20 = the block-era deletion per the disposition
tables + survivor audit + the full 12-point regression (the biggest remaining live round).
Then the polish backlog, THEN ask C2 (Sodium depth — §2.5) / C7 (NeoForge).

## 6. Next-session opening prompt (paste-ready)

> Read `migration/S19E_HANDOFF.md` in full (it chains to `migration/S19_HANDOFF.md` §0 for
> the standing rules — follow ALL of them), then execute S19-E per §2: the real
> Sodium 0.9.1 / Iris 1.11.2 / cloth-config 26.2.155 wiring (user-directed — no stale F21
> stubs), the cloth swap + ModMenu entrypoint (§2.3), On*Present detection + honest gating,
> and OPEN WITH THE C2-DEPTH QUESTION (§2.5) to the user. Then the peripheral commons tail
> (§1), then close S19 and proceed autonomously to S20 + polish per §5 — the top polish
> item is the dim-persistence gap (§3). Fable-verify every increment; commit+push each;
> suite green before every READY.
