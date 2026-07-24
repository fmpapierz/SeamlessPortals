# FRESH-SESSION STARTER PROMPT — portal-view polish/regressions (paste verbatim)

---

Continue the Seamless Portals shaders-ON engagement: the LIGHTING WAVE IS CLOSED (commit 00d15cf on
iris-on/is5-shadow — wash = clip-in-shadow-scope + ghost = iris prev-camera-uniform poisoning, both fixed,
A/B-attributed, suite green). THIS session = the PORTAL-VIEW POLISH/REGRESSION QUEUE I reported while
confirming those fixes:
  (a) the portal view BOBS like player view-bob,
  (b) ENTITIES are not visible through the portal (the old sodium-era regression — now the top fidelity gap),
  (c) SOURCE particles bleed into/show over the dest view in the window,
  (d) probably more — start with a visual inventory pass.

FIRST, READ IN FULL (do not act before reading both):
  - migration/PORTAL_VIEW_POLISH_HANDOFF.md  ← §0 standing rules → §1 the shipped fix stack + levers →
    §2 the queue (per-item known facts + first moves) → §3 the follow-up ledger.
  - memory: portal-shaders-lighting-wave (CLOSED — the evidence trail + hard-learned seam rules) and
    entity-vanish-cooldown-mirror-gate (the entities-through-portal history).

HARD DISCIPLINE (all re-earned last session — violations cost us builds and whole runs):
  - NO GUESSING. DIAGNOSE-FIRST. Instrument and confirm ON THE LIVE CLIENT before designing any fix. A
    bytecode-confirmed mechanism + a SOUND design panel is NOT permission to ship.
  - ADD DEBUG LOGS FOR EVERYTHING — liveness lines + confirm counters on every fix, once-only WARNs on EVERY
    silent-skip/throw path. A fix whose firing cannot be proven from the log is unjudgeable.
  - READ THE FULL latest.log every run (fabric/runs/client-sodium/logs/latest.log): GL-error census first
    (baseline ~4-8 known "Invalid format" lines; any new class = investigate before concluding ANYTHING),
    then your liveness/counter lines, compared against the prior run.
  - TRUST MY LIVE OBSERVATIONS over screenshots/bytecode readings; describe what you think I'll see and ask
    me to confirm; I film willingly — tell me EXACTLY what to point the camera at.
  - MODEL TIERS: fable for theory/design/adjudication/adversarial-verify panels (the complicated work); opus
    for mechanical recon (javap/bytecode extraction, source-fact mapping, log tabulation). Multi-agent panels
    with independent analysts + adversarial verifiers for every non-trivial mechanism. Depth over speed.
  - Every fix lever-gated DEFAULT-ON (-Dseamlessportals.disableX), every probe DEFAULT-OFF, every lever a -P
    passthrough in BOTH fabric/build.gradle blocks. A/B-attribute every fix BOTH directions before calling it
    done. Suite gate (.\gradlew.bat :fabric:runCrossingGametest, no GUI) before every commit; push stage
    commits to origin iris-on/is5-shadow; git add explicit file lists only.
  - SEAM RULES (hard-learned): never read the woven LevelRenderer.pipeline field at the compat anchor (NULL
    in the finally window — use Iris.getPipelineManager().getPipelineNullable()); never cite iris's
    post-restore debug C-string; GL_PACK bracket every glReadPixels; javap every symbol before
    reflecting/mixing; per-portal vs per-frame state semantics are load-bearing.

STANDING RULES:
  - Worktree: C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\is5-shadow, branch
    iris-on/is5-shadow (HEAD 00d15cf). Live runs: .\gradlew.bat :fabric:runClientSodium -PirisRuntime=true
    (iris 1.11.2 + sodium 0.9.1 + Complementary r5.8.1). Jars for javap in handoff §0.
  - The shipped stack + its levers + the probes are tabled in handoff §1 — reuse them (ShadowAliasProbe
    carries all the fix confirm-counters; debugTintStamp dyes the stamp magenta).
  - ACCEPTED COSTS (do not "fix"): in-window TAA-history-free look (stationary sub-pixel wobble + fresh
    reflections inside the aperture only). The proper per-dest-history upgrade is ledgered (§3).
  - Java-process cleanup: only kill java PIDs whose command line matches THIS worktree; never gradlew --stop;
    never touch idea64.

SUGGESTED OPENING SEQUENCE (adjust as evidence dictates):
  1. §2d visual inventory pass (I run + film; you pre-write the observation checklist).
  2. §2b ENTITIES (top fidelity gap): opus recon of the dest entity submit path (stencil vs compat route,
     where sodium changes it) + a 1Hz submitted-vs-drawn counter probe + the shaders-OFF A/B — then a fable
     panel on the mechanism before any fix.
  3. §2a BOBBING: the stencil-renderer A/B + the IP 1.21.3 side-by-side (does real IP bob its portal views?)
     to set the target behavior, THEN the matrix-dump probe.
  4. §2c PARTICLES: my characterization run first (are the bleeding particles physically in FRONT of the
     portal plane = correct occlusion, or behind = bug?) + the debugTintStamp discriminator + opus recon of
     iris/Complementary particle depth-write behavior.
  Do not write ANY fix until the relevant probe/A-B names the mechanism.
