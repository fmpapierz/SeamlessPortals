# FRESH-SESSION STARTER PROMPT — in-portal fullbright (paste verbatim)

---

Continue the Seamless Portals shaders-ON engagement: the LAST open fault is the in-portal FULLBRIGHT
(dest terrain seen through a same-dim portal is too bright, MAIN-CAMERA DIRECTION-DEPENDENT, toggles
correct↔fullbright as I pan). Three sibling faults already shipped + live-confirmed (flash ca6e93b,
two-portal depth e8e4767, phantom e408a0d).

FIRST, READ IN FULL (do not act before reading both):
  - migration/FULLBRIGHT_HANDOFF.md  ← rev 2. §0 rules → §3 confirmed mechanism → §4 why the last fix
    failed → §5 THE PROBE (now FULLY DESIGNED, build-ready) → §8 javap symbol table.
  - memory: portal-shaders-lighting-wave (+ the MEMORY.md index line).

HARD DISCIPLINE (the pre-recon attempt violated this and wasted a build):
  - NO GUESSING. DIAGNOSE-FIRST. A bytecode-confirmed mechanism + a SOUND design panel FIRED WITHOUT
    ERROR AND DID NOT FIX THE VISUAL. So "confirmed mechanism + sound design" is NOT permission to ship.
    INSTRUMENT AND CONFIRM ON THE GPU before writing or trusting any fix.
  - ADD DEBUG LOGS to CONFIRM EVERY LINK — log actual values, not assumptions.
  - READ THE FULL latest.log every run (fabric/runs/client-sodium/logs/latest.log), not just a tail/grep —
    GL errors, iris chatter, my probe lines. Compare against the pre-change log.
  - Trust MY live observation over any screenshot/bytecode reading.

THE FIRST ACTION IS TO BUILD THE PROBE (handoff §5 — it is fully designed, symbols in §8), NOT a fix:
  A reflection-only `IrisFullbrightProbe` (clone ShadowEmptinessProbe: no iris @Mixin, self-disarming,
  1Hz, render-thread only) driven by a thin `@Inject` at `com.mojang.blaze3d.opengl.GlCommandEncoder
  .trySetup(...)Z` RETURN (clone MixinSodiumProbe_GlCommandEncoder). Lever `-Dseamlessportals.fullbrightProbe`
  (default-OFF; add the `-PfullbrightProbe` passthrough to BOTH build.gradle blocks). Pass discriminator =
  `PortalRendering.isRendering()` (FALSE=main, TRUE=dest), NOT isDestExtracting. Per portal frame, capture the
  MAIN-pass and DEST-pass COUNTER + glGetUniformfv of gbufferModelView + celestial (shadowLightPosition/
  sunPosition/upPosition are the strong direction-dependent discriminators; cameraPosition is weak) + the
  reflected CapturedRenderingState source, and log ONE comparison block. The re-upload test is the CROSS-PASS
  COUNTER compare (C_dest != C_main), NOT a post-update lastFrame read (that is always ==COUNTER — useless).

  Then have me RUN it A/B:
    (i)  .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true -PfullbrightProbe=true          (bump ON)
    (ii) …same… -PdisableIrisPerFrameRefresh=true                                                  (bump OFF)
  Read per §5's 3-way table:
    - GL values DIFFER (dest) but still fullbright → H3: pivot to per-vertex lmcoord.
    - GL values EQUAL (main) AND C_dest != C_main → H1: re-source (Option B — push dest camera + celestial +
      shadow matrices before the dest render).
    - GL values EQUAL (main) AND C_dest == C_main → H2: bump didn't reach → fix reach/timing.
  RISK the probe self-checks: if no terrain-like program appears in the dest pass, sodium terrain isn't routing
  through trySetup here → hook sodium's terrain draw path directly.

  Only AFTER the probe settles which cause, design the fix (deep-verify it) and implement.

STANDING RULES:
  - Worktree: C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow, branch
    iris-on/is5-shadow (HEAD d7f567c + the doc-only rev-2 handoff commit). Iris jar for javap + IP source
    paths are in handoff §0.
  - Everything lever-gated; default-OFF for a probe, default-ON only for a confirmed fix. Every new lever/probe
    gets a -P passthrough in fabric/build.gradle (runClientSodium AND gametest blocks).
  - Deep-verify non-trivial fixes (the multi-agent panel). Depth over speed.
  - Commit isolation: the working tree has UNCOMMITTED shadow-sync WIP AND the ineffective fullbright counter-bump.
    When you commit anything, ISOLATE it from that WIP (git add only your files). Push stage commits to origin
    iris-on/is5-shadow.
  - Java-process cleanup: only kill java PIDs whose command line matches THIS worktree path; never gradlew --stop;
    never touch idea64.
  - The ~6 "Invalid format" GL log-lines are a SEPARATE known phantom-fix residual — don't chase them mid-fullbright.

Open by reading the handoff + memory, then BUILD the §5 probe, show me the diff, and have me run it A/B. Do not
write a fullbright fix until the probe tells us which of the three causes it is.
