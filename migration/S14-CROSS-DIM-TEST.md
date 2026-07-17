# S14 — CROSS-DIMENSION: the USER runClient script (rung 2)

> **STATUS: DRAFT — do not run yet.** This script goes live only after the S14 readiness audit's
> confirmed defects are fixed, Fable-verified, committed, and you get an explicit **READY** with all
> workflows idle (process rule 1). A "§ audit outcomes" section will be appended then.

This is the hands-on test script for **S14 rung 2** — the first REAL cross-dimension (nether-view)
portal. Rung 2 is the first live contact for: **R1** secondary `ClientLevel` construction (seaLevel
protocol), **R7** packet redirection under real cross-dim traffic, **R9** per-dim fog/lightmap,
**R10** the first genuinely-LOADING chunk tickets, the **S13-H driver core's cross-dim path**
(`sharedState=false` — never run live; its extractor-identity bug was fixed pre-emptively at
`e817b57` but never exercised), and IP's **cross-dim teleportation** (server transfer + seamless
client dim swap). Assembled from `EXECUTION_PLAN.md` S14(d)/(e), `S14_HANDOFF.md`, and the S13
lessons in `S13-FIRST-LIGHT-TEST.md`.

---

## 0. Before you launch

- **Flag stays ON** from S13: `fabric/runs/client/config/seamlessportals.properties` →
  `entityPortals=true` (load-time; full restart for any change). Fabric client only.
- **World: a NEW dedicated test world** (never a main world). Creative, cheats ON — but **NOT
  peaceful** this time: R10 is a watch item (IP semantics allow natural mob spawns in dest chunks;
  peaceful would mask it). Superflat is fine for the overworld side.
- **Logs:** `fabric/runs/client/logs/latest.log` + `debug.log`; crashes in
  `fabric/runs/client/crash-reports/`. Copy `latest.log` out before each restart overwrites it.
- **Marker lesson (attempt-8):** the nether view through the window is unambiguous by itself (netherrack
  ≠ grass), so no marker pillar is needed for step 1 — but your black-wool framing convention around
  the portal is still the right contrast aid.

## PRE-ANSWERED EXPECTED BEHAVIORS — read BEFORE filing anything as a bug

1. **The nether view FADES IN over a few seconds — EXPECTED, not a defect.** IP loads the destination
   gradually (~8 chunks by default, graduated + capped; memory `ip-dest-loading-model`). There is no
   instant-from-cold dest view, even in original IP. Holes that PERSIST after ~10s of standing still
   are a real symptom (§ symptom table).
2. **Nether-side far-chunk pop at ~5-block tiers with a few seconds' grace = faithful IP graduated
   loading** (verified during the block-era hunt: 5/15-block tiers, ~3.9s grace). Not a defect.
3. **The deterministic `ImmPtlChunkTickets` "Chunk loading failure" log line is KNOWN-BENIGN** — IP
   ships it. Do not file.
4. **Time-of-day mismatch through the window is EXPECTED** — `WorldInfoSender` is weather-only (F1).
5. **No clouds through the window** (both directions) — dest clouds are deliberately skipped until S18
   (documented deviation). The nether has no clouds anyway; the check matters when looking INTO the
   overworld from the nether side.
6. **Weather RENDER through the window is deferred to S18** (deviation ledger: weather + world border
   omitted). Step 6 verifies weather STATE sync (it rains when you cross into the overworld), not
   rain visuals inside the window. [PENDING AUDIT — the packets track may refine this pre-answer.]
7. **Mobs may spawn near the nether-side portal (piglins/ghasts/zombified piglins) — that is R10
   under watch.** IP's ticket semantics re-enable natural spawns in dest chunks. It is IP-faithful
   behavior, NOT a bug — but NOTE the intensity (a piglin FLOOD through the window within a minute
   would be worth capturing; a few spawns is IP-normal).
8. **`make_portal` is still ONE-SIDED and ONE-WAY** (S13 lesson; IP behavior). No return portal until
   `complete_bi_way_portal`. The window is visible only from the FRONT.
9. **First-visit nether worldgen can pause the SERVER side briefly** — fresh nether chunks generate on
   first load. A few seconds of dest-view stall on the very first portal is worldgen, not a hang.
   [Block-era measured 29s at radius-32; IP's ~8-chunk graduated load should be far smaller.]
10. **The `GL_INVALID_OPERATION … 'Framebuffer name must be generated'` spam is the KNOWN U2 flood**
    (root-caused, fix queued for polish; chip task_70fec4eb). Note only if it CHANGES character.
11. **The scaled-portal notes from S13 all still apply** (giant physics, buried-portal white bar,
    2× parallax on scaled portals) if you experiment beyond the script.

---

## 1. The rung-2 command sequence + EXPECTED result per step

Run in order; one-line PASS/FAIL per step at the end.

1. **`/portal make_portal 4 4 minecraft:the_nether 0 70 0`**
   EXPECTED: a 4×4 window that **fades in to a nether view over a few seconds** as dest chunks
   arrive (pre-answer 1); **portal-view lighting correct BEFORE any crossing** — lava glow/flames lit,
   not dark/stale (the lateUpdateLight regression, item 8); **nether fog + sky inside the window,
   overworld fog outside it, no bleed either way** (R9). Circle it: parallax tracks like a real
   window; behind it: invisible (front-only).
   FAILURE SIGNATURES (capture + map via § symptom table): crash on the command (R1 secondary-world
   construction); window stays empty forever (R10 tickets); OVERWORLD terrain shown in the window
   (extractor/renderer identity — the e817b57 class); fog corruption in the MAIN world after looking
   through (R9 UBO); dark/greyscale window that lights up only after crossing (lateUpdateLight).
2. **Cross into the nether; play ~2 minutes; break/place blocks; cross back.**
   EXPECTED: seamless crossing BOTH ways — no respawn screen, no camera snap, hand steady, velocity
   preserved (the standing rule: nothing may numerically change rotation or zero velocity); block
   edits remesh immediately in the nether; after crossing back, NO chunk holes, limbo bands, or
   distant-chunk vanish on either side; no multi-hundred-ms crossing freeze.
3. **Double-crossing stress: two fast back-to-back crossings.**
   EXPECTED: clean both times. WATCH: wrong-dimension block updates / floating phantom blocks — the
   historical R7 double-crossing signature (netty pre-pass flag leak shape). If you see overworld
   blocks "painted" into the nether or vice versa, capture immediately.
4. **Large portal: `/portal make_portal 10 6 minecraft:the_nether 0 70 0`, and a negative-coords
   portal: `/portal make_portal 4 4 minecraft:the_nether -128 70 -128`. Cross both.**
   EXPECTED: both build, render, and cross clean (regression item 10 — the old negative-coord
   off-by-one class is server-side history, but this is its cross-dim re-check).
5. **`/portal debug report_chunk_loaders`** (and the per-player loading report if offered).
   EXPECTED: loader radii sane (default chain ~8, config-scaled, clamp 1..32); after walking >64
   blocks away from a portal and waiting ~1 min, the report shows the loaders COLLAPSED (R10 ticket
   lifecycle). Portals' own chunks stay loaded while you stand near them — **C8 RECHECK: if any
   portal's OWN chunk sits unloaded (window black/hole at the frame) until a relog, that is the C8
   flake surviving IP loading = a real bug now.**
6. **Weather: `/weather rain`, then observe from the nether side.**
   EXPECTED: rain STATE syncs (cross back into the overworld → it is raining). Through-window rain
   visuals are S18-deferred (pre-answer 6). No crash from the weather change while a secondary world
   is live.
7. **Long-walk probe: post-crossing, walk 200+ blocks in the nether, then back through and 200+ in
   the overworld.**
   EXPECTED: no limbo bands / missing chunk stripes anywhere (this is the §11.7 empirical test of
   IP's no-ACK loading model). If drops REPRODUCE: checkpoint **C8** activates — vanilla-path root
   cause hunt first; a reliability layer only as documented deviation (F20).
8. **R13k fluid-fog check: submerge the camera in water directly beside the portal while looking
   through the window** (and lava with fire resistance, if convenient).
   EXPECTED: water fog applies on YOUR side; the window keeps the NETHER's fog; surfacing restores
   normal fog with no stale tint (exercises the S12 camera pull-model gates: `initialized` flag +
   post-extract view-rotation processing).
9. **Relog standing next to the NETHER-side portal.**
   EXPECTED: world reopens in the nether beside a working portal (window live after the graduated
   fade-in), no persistence loss (regression item 12).

## 2. Symptom → suspect table (cross-dim edition)

| Symptom | Suspect |
|---|---|
| Crash the moment `make_portal` runs with a nether dest | **R1** — secondary `ClientLevel` construction (seaLevel/ctor args) |
| Window shows the OVERWORLD (your own dim) instead of the nether | Driver core `sharedState=false` / extractor identity (**e817b57** class) |
| Window stays permanently empty/black (no fade-in ever) | **R10** tickets not actually loading, or the chunk-send→client-store path |
| Nether view dark/greyscale until you cross once | **lateUpdateLight** not wired for fresh cross-dim secondaries |
| Fog corruption in the MAIN world after looking through | **R9** — fog UBO slot written mid-frame without restore |
| Overworld-colored fog inside the window | **R9** — dest atmosphere fill not consuming the nether's fog color |
| Wrong-dim phantom blocks after fast double-crossing | **R7** ordering / a packet-handler mixin missing `isSameThread` |
| Respawn screen flash / camera snap / velocity zeroed at crossing | Cross-dim teleport routing through vanilla respawn handling IP replaces |
| Portal's own chunk unloaded until relog | **C8** — file it, it is a real bug now |
| Crash on the second+ portal or on crossing back | PassState eviction / secondary-renderer churn (S13 §1.5 item 3 — first exercised HERE) |

## 3. What to capture and hand back

- `latest.log` (+ `debug.log` if anything looked wrong) per session; crash reports if any.
- Screenshot of any symptom (black-wool framing helps); say WHICH step + what you interpreted —
  per the standing rule we confirm visual interpretations together before acting.
- One-line PASS/FAIL per step 1–9, and which suspect row any failure maps to.
- The step-5 loader report text (copy/paste) — it is the R10 lifecycle evidence.
