# SPIKE-R7 — client packet re-queue ordering (network.md §A, API_RISKS R7)

**Status: §A SURVIVES CONTACT — both claims empirically CONFIRMED on a live 26.2 client.**

| | |
|---|---|
| Plan ref | EXECUTION_PLAN S1(a) second bullet; D6 discipline |
| Required reading verified against | `migration/api-map/network.md` §A + G1; `migration/inventory/network.md` §2.7/§3.2 |
| Spike branch | `spike/r7-requeue` @ `79edd02a` (base `4a489405` = S0.3), worktree `.claude/worktrees/wf_c463c3ac-d47-2` — never merged |
| Run vehicle | Fabric client-gametest (`:fabric:runClientGametest`, loom run config `clientGametest`), singleplayer integrated server, world auto-created, game auto-exited. `BUILD SUCCESSFUL in 37s`, exit code 0 |
| Run date | 2026-07-13, log `spikerun1.log` in the worktree (166 recorded events) |
| Probe shape | 3 bursts × 8 interleaved pairs of [custom `seamlessportals:spike_r7` payload, vanilla `ClientboundPingPacket`], one shared monotonically increasing server-side seq tagging every packet; every observation recorded with a linearizable global event index (`AtomicInteger.getAndIncrement`) + thread name |

Vanilla observable chosen: `ClientboundPingPacket` — carries an `int id` (seq carrier, offset
base 1,500,000,000), its client handler takes the normal re-queue path
(`ClientCommonPacketListenerImpl.handlePing` → `PacketUtils.ensureRunningOnSameThread(packet,
this, this.minecraft.packetProcessor())`, `mc262-ref client/multiplayer/ClientCommonPacketListenerImpl.java:153-155`),
and the pong reply is a server-side no-op (`ServerCommonPacketListenerImpl.handlePong` has an
empty body, `mc262-ref server/network/ServerCommonPacketListenerImpl.java:91-92`) — zero side
effects at any send rate.

---

## 1. Verdicts (from the run's own analysis pass, quoted verbatim)

```
[SPIKE] ANALYSIS mode=OBSERVE seqRange=[0,16)  processedOrder=[C0,P1,C2,P3,C4,P5,C6,P7,C8,P9,C10,P11,C12,P13,C14,P15]
[SPIKE] ANALYSIS mode=OBSERVE inversions=0 customEnter(netty=8,main=8) pingEnter(netty=8,main=8) fabricRecv=8 customProcess=8
[SPIKE] VERDICT  mode=OBSERVE ORDER-FAITHFUL (no cross-queue reordering)

[SPIKE] ANALYSIS mode=REQUEUE seqRange=[16,32) processedOrder=[C16,P17,C18,P19,C20,P21,C22,P23,C24,P25,C26,P27,C28,P29,C30,P31]
[SPIKE] ANALYSIS mode=REQUEUE inversions=0 customEnter(netty=8,main=8) pingEnter(netty=8,main=8) fabricRecv=0 customProcess=8
[SPIKE] VERDICT  mode=REQUEUE ORDER-FAITHFUL (no cross-queue reordering)

[SPIKE] ANALYSIS mode=EXECUTE seqRange=[32,48) processedOrder=[P33,P35,P37,P39,P41,P43,P45,P47,C32,C34,C36,C38,C40,C42,C44,C46]
[SPIKE] ANALYSIS mode=EXECUTE inversions=1 customEnter(netty=8,main=0) pingEnter(netty=8,main=8) fabricRecv=0 customProcess=8
[SPIKE] VERDICT  mode=EXECUTE REORDERED (1 inversions)
```

(`C<n>` = custom-payload processing event, `P<n>` = ping main-pass processing event, listed in
global event order; seq is the server-side send order. `inversions` counts *adjacent* seq drops —
the EXECUTE order is in fact maximally reordered: **all 8 later-sent pings were processed before
all 8 earlier-sent customs**, one clean boundary at P47→C32.)

- **REQUEUE** = the §A order-faithful design: netty pass →
  `minecraft.packetProcessor().scheduleIfPossible(listener, outerPacket)` + `ci.cancel()`; main
  pass (the scheduled re-invocation hits the same HEAD inject with the thread check now passing)
  → handle inline + `ci.cancel()`. **Zero inversions; perfect interleave with vanilla packets.**
- **EXECUTE** = the naive IP-1.21-verbatim port (`minecraft.execute(self)` re-submit,
  `IP:PacketRedirectionClient.java:70-76` shape). **Every vanilla packet that arrived after a
  redirected packet was handled before it** — the §A/G1 predicted cross-queue reordering is real,
  reproducible, and total within a burst.
- **OBSERVE** = untouched vanilla+Fabric path (mixin logs, never cancels) — baseline confirming
  the burst methodology itself introduces no reordering.

## 2. Claim (1) — ORDERING: proven

**§A's mechanism claim verified in source:** the client packet re-queue destination is
`Minecraft.packetProcessor` (field `mc262-ref client/Minecraft.java:369`, built on `gameThread`
`:730`, public getter `packetProcessor()` `:2922`); `PacketUtils.ensureRunningOnSameThread(packet,
listener, packetProcessor)` schedules `(listener, packet)` and throws
(`mc262-ref network/protocol/PacketUtils.java:21-26`); `runTick` drains
`packetProcessor.processQueuedPackets()` under `"scheduledPacketProcessing"` **before**
`runAllTasks()` under `"scheduledExecutables"` (`mc262-ref client/Minecraft.java:1169-1172`).
`PacketProcessor` is a plain FIFO `ConcurrentLinkedQueue` drained till empty
(`mc262-ref network/PacketProcessor.java:14,34-40`).

**Empirically:** a packet re-submitted from the netty thread via
`scheduleIfPossible` (with original delivery cancelled) lands in the **same FIFO queue** as every
vanilla packet's own re-queue, so relative order is preserved exactly — REQUEUE's processed order
`C16,P17,C18,…,P31` is the send order, digit for digit. Representative raw events (netty
arrival, then the game-thread drain, same client frame):

```
evt=60 kind=custom.handle.ENTER seq=16 thread=Netty Local IO #1 mode=1 pass=netty mcSame=false ppSame=false
evt=61 kind=custom.requeue.SCHEDULED seq=16 thread=Netty Local IO #1
evt=62 kind=ping.handle.ENTER   seq=17 thread=Netty Local IO #1 pass=netty mcSame=false ppSame=false
...
evt=84 kind=custom.handle.ENTER seq=16 thread=Render thread mode=1 pass=main mcSame=true ppSame=true
evt=85 kind=custom.PROCESS      seq=16 thread=Render thread path=requeue
evt=86 kind=ping.handle.ENTER   seq=17 thread=Render thread pass=main mcSame=true ppSame=true
evt=87 kind=ping.PROCESS        seq=17 thread=Render thread
```

**Negative control (the reordering §A warns against):** in EXECUTE mode the custom's processing
runnable was submitted to `minecraft.execute` *before* the next ping even arrived on the netty
thread (evt=119 SUBMITTED seq=32 precedes evt=120 ping ENTER seq=33), yet every ping was
processed first:

```
evt=118 kind=custom.handle.ENTER seq=32 thread=Netty Local IO #1 mode=2 pass=netty ...
evt=119 kind=custom.execute.SUBMITTED seq=32 thread=Netty Local IO #1
evt=120 kind=ping.handle.ENTER   seq=33 thread=Netty Local IO #1 pass=netty ...
...
evt=142 kind=ping.handle.ENTER   seq=33 thread=Render thread pass=main ...   <- later-sent ping
evt=143 kind=ping.PROCESS        seq=33 thread=Render thread
...
evt=157 kind=ping.PROCESS        seq=47 thread=Render thread                 <- ALL pings done
evt=158 kind=custom.PROCESS      seq=32 thread=Render thread path=execute    <- THEN the customs
...
evt=165 kind=custom.PROCESS      seq=46 thread=Render thread path=execute
```

This observed order (packet queue fully drained, then the execute queue) is exactly consistent
with the `Minecraft.java:1169-1172` drain order. Redirected chunk/entity packets racing
non-redirected respawn/login/flag packets is the class of bug this project has already paid for —
the naive port re-opens it **every frame**; the §A port closes it.

## 3. Claim (2) — DOUBLE-INVOCATION shape: proven

- **Handler bodies enter TWICE per packet, once per thread.** For both probed handlers, every
  packet produced exactly 2 HEAD-inject entries: `customEnter(netty=8,main=8)`,
  `pingEnter(netty=8,main=8)` in OBSERVE and REQUEUE. First entry on `Netty Local IO #1`
  (`mcSame=false ppSame=false`), second on `Render thread` (`mcSame=true ppSame=true`). The
  netty pre-pass is real **even in singleplayer** — the local pipeline hops through a dedicated
  netty thread (`Connection.channelRead0` → `packet.handle(listener)`,
  `mc262-ref network/Connection.java:146-170`).
- **A mixin HEAD inject therefore fires twice** unless the packet is cancelled/consumed on the
  netty pass — the isSameThread doctrine (briefing §6; memory `respawn-mislabel-phantom-blocks`)
  holds unchanged on 26.2. Corollary from source (not runtime-instrumented here): on the netty
  pass the handler exits via the `RunningOnDifferentThreadException` throw
  (`PacketUtils.java:24`), so RETURN-phase injects/cleanup are skipped on that pass.
- **With the §A mixin cancelling on both passes there is no double delivery:** REQUEUE shows
  `customProcess=8` (exactly one processing per packet) and `fabricRecv=0` — the `ci.cancel()`
  on the netty pass prevented vanilla's own re-queue (no third invocation) and the main-pass
  cancel kept Fabric's payload dispatch from ever seeing the packet. EXECUTE shows
  `customEnter(main=0)`: cancel-without-requeue means the handler never re-enters at all.
- **`Minecraft.isSameThread()` and `minecraft.packetProcessor().isSameThread()` agreed in all
  logged checks** (`mcSame` == `ppSame` in every event) — both test the game thread
  (`PacketProcessor` is constructed on `this.gameThread`, `Minecraft.java:730`). Either guard
  works; the §A port should use `packetProcessor().isSameThread()` since that is vanilla's own
  gate in `ensureRunningOnSameThread`.

## 4. Bonus observations (evidence-backed, useful for S7)

- **Fabric play receivers (v6, fabric-api 0.152.1+26.2) run synchronously inside the main-pass
  handler on the Render thread** — no extra frame delay, no separate queue: OBSERVE events are
  strictly adjacent per packet: `evt=18 custom.handle.ENTER pass=main` → `evt=19 custom.PROCESS`
  → `evt=20 fabric.RECV seq=0 thread=Render thread` (F3's "render thread" contract confirmed,
  and stronger: same-stack, order-preserving).
- **Vanilla's own custom-payload path already re-queues the OUTER packet** — OBSERVE (no cancel)
  produced exactly the netty+main double entry via
  `ClientCommonPacketListenerImpl.handleCustomPayload`'s `ensureRunningOnSameThread`
  (`ClientCommonPacketListenerImpl.java:159-167`; note the `DiscardedPayload` short-circuit at
  `:161` sits BEFORE the thread hop). The §A design is literally vanilla's own hop with the
  handling swapped in at the mixin HEAD — which is why it cannot reorder.
- **`scheduleIfPossible` accepted the outer `ClientboundCustomPayloadPacket` with the
  `ClientCommonPacketListener` mixin-arg as listener** and the drain re-invoked
  `handle(listener)` through `listener.shouldHandleMessage(packet)`
  (`PacketProcessor.java:47-62`) with no casts beyond
  `Packet<ClientCommonPacketListener> outer = (ClientboundCustomPayloadPacket)(Object)this;`
  (the record implements that exact interface). Compiles and runs as §A prescribes.
- Burst delivery: all 16 packets of a burst arrived back-to-back on `Netty Local IO #1` and were
  drained in a single game-thread pass — the per-frame reordering window is easily and reliably
  hit by a one-tick burst; this is not a rare race.

## 5. Consequences for the S7 implementation (no design change needed)

network.md §A ports as written:
1. `MixinClientboundCustomPayloadPacket` HEAD inject, keyed on
   `payload instanceof PacketRedirection.Payload`:
   netty pass (`!minecraft.packetProcessor().isSameThread()`) →
   `minecraft.packetProcessor().scheduleIfPossible(listener, (Packet<ClientCommonPacketListener>) outerPacket); ci.cancel();`
   main pass → `Payload.handle(...)`/`handleRedirectedPacket` inline, then `ci.cancel()`.
2. `PacketRedirectionClient.handleRedirectedPacket`'s own `minecraft.execute` fallback branch
   then never runs for the outer hop (spike measured the main-pass entry always arrives with the
   thread check passing) — keep the branch verbatim (fidelity + defense), it is simply cold.
3. `wrapRunnable`/`scheduleExecutables` hooks keep their narrowed §A role (tasks submitted via
   `Minecraft.execute` DURING redirected handling); nothing in this spike contradicts that. Not
   probed here (out of scope).

## 6. Unproven / out of scope (honest residue)

- **Remote (non-local) connections:** singleplayer local channels pass packet objects by
  reference — the payload STREAM_CODEC (and the `i:r` inner-packet decode on the netty thread)
  never executed in this run. The threading/queueing path probed (channelRead0 → handle →
  ensureRunningOnSameThread → PacketProcessor) is byte-identical for remote connections, but
  codec-on-netty-thread behavior and decode cost remain unexercised until a dedicated-server
  test (S13+ scripts cover this).
- **Disconnect semantics:** `scheduleIfPossible`'s `RejectedExecutionException` after close
  (`PacketProcessor.java:27-29`) and `shouldHandleMessage` drop-on-disconnect (`:49,59-61`) were
  not exercised — asserted from source only.
- **Bundle packets:** ordering of re-queued packets relative to `ClientboundBundlePacket`
  sub-packet delivery was not probed (IP wraps sub-packets individually before bundling, so the
  §A mixin sees them as ordinary custom-payload packets — but that interaction is untested).
- **RETURN-inject skip on the netty pass:** stated from source (the
  `RunningOnDifferentThreadException` throw), not runtime-instrumented in this spike.
- The EXECUTE-mode frame attribution (same-frame vs next-frame drain) was not instrumented with
  frame counters; the reordering conclusion does not depend on it.

## 7. Reproduction

Spike branch `spike/r7-requeue` (commit `79edd02a`, worktree-only). Files:
`fabric/src/main/java/com/warwa/seamlessportals/spike/r7/` (SpikeR7 core + payload + burst
driver + analysis, SpikeR7Init/SpikeR7ClientInit entrypoints, SpikeR7GameTest client-gametest
driver, `mixin/SpikeCustomPayloadPacketMixin` + `mixin/SpikePingHandlerMixin`),
`seamlessportals-spike-r7.mixins.json`, fabric.mod.json entrypoint/mixin wiring. Run:
`.\gradlew.bat :fabric:runClientGametest --console=plain --no-daemon` — auto-creates the world,
fires the bursts, prints `[SPIKE] ...` analysis, exits. Grep the run log for `[SPIKE]`.

## Review verdict (S1 adversarial review, 2026-07-13)

**PASS.** The §A confirmed-or-corrected deliverable is present (CONFIRMED) and the evidence genuinely supports it, including a real negative control.
- All quoted analysis/verdict lines and raw events (evt=60–87, 118–165) verified verbatim against `spikerun1.log` in the worktree (uncommitted — the memo's quotes are the durable record).
- mc262-ref re-checked line-exact: `processQueuedPackets` drained under `scheduledPacketProcessing` BEFORE `runAllTasks` (`Minecraft.java:1169-1172`); `PacketProcessor` FIFO `ConcurrentLinkedQueue`, `scheduleIfPossible` closed→`RejectedExecutionException`, `shouldHandleMessage` drop; `PacketUtils.ensureRunningOnSameThread` schedule+throw; `handlePing` re-queue path (`ClientCommonPacketListenerImpl.java:153-155`); `handlePong` empty body; `handleCustomPayload`'s `DiscardedPayload` short-circuit before the thread hop. The ping choice as a side-effect-free vanilla observable is sound.
- The EXECUTE negative control is the strongest part: total cross-queue reordering (all 8 later pings before all 8 earlier customs) from the exact IP-1.21-verbatim shape — §A's correction is now empirical, not paper.
- The UNPROVEN list is honest; nothing S7 needs is left unproven (remote-connection codec-on-netty behavior is assigned to S13+ scripts by the plan; the threading path probed is the same code).
