# S20 CONTINUATION — increment 4 (the flag's death) + the close-out round

**State: the block-era deletion is DONE and GREEN. One code stage remains.**
Branch `s20/block-deletion` @ `09e8bc8`. Ledger of record:
`migration/port-notes/S20-block-era-deletion.md` — **read its EXECUTIVE SUMMARY first**, then §G.12.

---

## Paste-ready opening prompt

> Continue S20 at increment 4 — the `entityPortals` flag's death — on branch `s20/block-deletion`
> in worktree `C:\Users\warwa\ModDev\Portals\Portal 26.2\.claude\worktrees\s20`.
>
> READ FIRST, in order: (1) `migration/port-notes/S20-block-era-deletion.md` — the EXECUTIVE SUMMARY,
> then **§G.12 in full** (increment 4's verified spec, and the blocker that invalidates the obvious
> plan), then §E.2, §G.1, §G.3; (2) `migration/S20_REGRESSION_CHECKLIST.md` (the close-out round);
> (3) `migration/S19_HANDOFF.md` §0 — ALL standing rules, unchanged and binding.
>
> Increment 4 is ATOMIC — do not land it in pieces (see §1 below for why). Apply it, gate it, commit
> and push, then ask the user before the close-out round. Two other Claude Code sessions may be live
> in this repo: never touch `.claude/worktrees/is5-shadow` or `.claude/worktrees/redstone`, never run
> `gradlew --stop`, and filter `Win32_Process` by THIS worktree's path before any `taskkill`.

---

## §1 WHY INCREMENT 4 IS ATOMIC (do not land it in pieces)

The two mixin-weave gates currently read `isFabricLoaderPresent() && EntityPortalsFlag.isOn()`
(pre-armed at increment 1). Collapsing them to `isFabricLoaderPresent()` **before** the rest of the
flag dies would weave the whole IP mixin set for a Fabric user who has `entityPortals=false`, while
their runtime gates still take the flag-OFF branch — a mixed, broken state that exists in no design.
The collapse is only correct once flag-OFF ceases to exist as a state. **One commit.**

## §2 THE BLOCKER — read §G.12 before writing any code

`EntityPortalsFlag.isOn()` carries a **loader force-false** (`:98-100`, `:89`). That lives *inside*
`isOn()`, so it applies at **all eight** gate sites, not just the two weave ones. On plain NeoForge
every gate is FALSE today. Collapsing the six RUNTIME gates to unconditional flips them to their
flag-ON branch on a shipping loader, and `MinecraftFramePumpMixin` then reaches `IPGlobal`'s static
initialiser on the first client frame → `ExceptionInInitializerError` → **a NeoForge client that no
longer boots.** `compileJava` is green either way and the 8-leg suite is Fabric-only, so **no gate
catches this.**

**Decided fix:** a `FABRIC_ONLY_IP_DRIVERS` set in `SeamlessMixinConfigPlugin.shouldApplyMixin`,
skipped when `!isFabricLoaderPresent()`, reusing the predicate already added at increment 1.

## §3 THE WORK, in the order to do it

1. **The isolated two-line review.** `SeamlessMixinConfigPlugin:191` → `&& !isFabricLoaderPresent()`;
   `IPCompatMixinPlugin:136` → `return isFabricLoaderPresent();`. Plus the `FABRIC_ONLY_IP_DRIVERS`
   mechanism from §2. These three edits are the entire cross-loader contract — eyeball them against
   the literal text before touching anything else.
2. **KEEP the D3 carve-out sets** (`:61-95`) and both `!…contains(…)` terms. §G.12 proves they are
   NOT redundant after the collapse: on NeoForge they are the only reason four classes weave.
3. **Collapse the remaining gates per §G.12**, each individually. Watch the non-gates:
   `SodiumFogOverrideMixin:69` is a **log argument**; `CrossingSmoke:87` is a **log argument** in the
   8-leg gate itself; `QuadParticleGroupMixin:92` collapses to an **identity redirect**.
4. **Delete whole:** `EntityPortalsFlag`, `config/SeamlessPortalsConfig`, `fabric/ModMenuIntegration`,
   `fabric/SeamlessConfigScreen` (the last was in no spec — it orphans silently with **no gate
   firing**). Swap `fabric.mod.json:24`'s modmenu entrypoint to `IPModMenuConfigEntry` **in this same
   commit** (IP's Cloth config registers only inside the flag-ON arm).
5. **Fix `CrossingSmoke:3, :86-93`** or the 8-leg gate itself will not build.
6. **Delete the two dead string-literal sets** (`ENTITY_PORTALS_SUPERSEDED_MIXINS` now; leave
   `SODIUM_INCOMPATIBLE_MIXINS` to a separate later commit).
7. **The C4 Mechanism-B removal** — atomic, and `IPConfig`'s null guard dies **with** its field, never
   before it (the gson hazard, §G.3).
8. **The holding machinery** — `IpHeldPaths` + **both** Groovy imports + the whole
   `multiloader-common.gradle` block. Correct ranges: `multiloader-loader.gradle` **21-27** (NOT
   22-28 — `:28` is load-bearing), `fabricStubsClasspath` comment **30-38** (`:39-41` is load-bearing).
9. **The TITLE-CARD reshape** — `fabric/build.gradle` `270-280` is **non-contiguous**: delete
   `:270-275` and `:280` only; the `initialScreenShown` seed must survive.
10. **Archive `EXCLUSIVITY_LEDGER.md`** (its active role retires).

## §4 GATES

`.\gradlew.bat :common:compileJava :fabric:compileJava :neoforge:compileJava --console=plain`
then `.\gradlew.bat :fabric:runCrossingGametest --console=plain`.

**The suite's `latest.log` ROTATES MID-RUN** — reconstruct before judging:

```bash
{ gzip -dc fabric/runs/gametest-crossing/logs/2026-07-26-1.log.gz; cat fabric/runs/gametest-crossing/logs/latest.log; } | grep "CROSSING SMOKE" | grep PASS
```

Legs are labelled 1, 2, 3, 4, 5, 6a, 6b, 7 (no leg 8). `ALL LEGS PASS` + exit 0 is the verdict.

**What the gates do NOT cover** — and therefore what increment 4 can break invisibly: the NeoForge
weave contract (§2), the D3 carve-outs (§3.2), string-literal class names, `fabric.mod.json`
entrypoints, and the gson config migration. Every one of those needs the close-out round or a manual
launch.

## §5 AFTER INCREMENT 4

Ask the user, then run `migration/S20_REGRESSION_CHECKLIST.md` — the canonical 12 plus 13
S20-specific rows. Two framing notes for that conversation: **B.1** (cross-dim fire spread) is the
re-keyed mixin's FIRST live exercise, not a regression check, because its old body was flag-OFF-only;
and **B.8** (a particle bleed near portals) is EXPECTED and belongs to `iris-on/is5-shadow`'s §2c fix.

Then S20 closes and the §5 era opens with the dim-persistence fix (`S19E_HANDOFF.md` §3).

## §6 MERGE-FORWARD DEBT THIS SESSION CREATED

`iris-on/is5-shadow` adds flag-ON work inside directories S20 deleted (`entity/`) and modifies files
S20 deleted (`EntityMixin`), plus it references `SeamlessPortalsConfig.isEntityPortals()` and
`PortalContextSwitch.isRenderingPortal`, both gone. §A and §E.6 of the port-note enumerate every
collision. The merge is mechanical but must be conscious. `redstone/passthrough` also rebases onto
this — and S20 *helps* it, since the block-era plumbing that made redstone hard is gone.
