# S7 — Network, global portals, API (U5) — port-note

**Stage:** S7 (EXECUTION_PLAN §3 S7). **Effort:** L — the wire-protocol layer + three spike-designed
mechanisms (R7 packet re-queue, R1 seaLevel protocol, R11 DataFixTypes).
**Unit:** U5 — `q_misc_util` networking/RPC (`MiscNetworking`, `ImplRemoteProcedureCall`,
`api/McRemoteProcedureCall`) + the `q_misc_util/mixin` tree (4 files) + `CustomTextOverlay`;
`imm_ptl/core/network/*` (`PacketRedirection` pair, `ImmPtlNetworking`, `ImmPtlNetworkConfig`);
**co-ports** `imm_ptl/core/portal/global_portals/*` (5 files, cycle 10) and `imm_ptl/core/api/*`
(`PortalAPI`, `ImmPtlEntityExtension`, cycle 11).
**Discipline:** D2 verbatim `qouteall.*` paths · D4.2 probe-ledger triage (the probe, not the paper
ledger, is authoritative — the S5 lesson) · D4.3 source-diff gate · S0 B2 PlatformHelper play-payload
seam · §S7(a) contents · §S7(b) forward-ref debt union. All **19 files land HELD** (no carve-in
change — `IpHeldPaths` already covers `q_misc_util/**` + `imm_ptl/core/**`), unregistered until S13.
**IP source root (1.21.3, Mojang mappings):** `C:/Users/warwa/ModDev/ImmersivePortalsMod/src/main/java/qouteall`
**26.2 evidence root (authoritative):** `C:/Users/warwa/ModDev/mc262-ref`
**Spike ground truth consumed:** `migration/spikes/SPIKE-R7-requeue.md` (§A CONFIRMED, 0 inversions,
negative control proved the naive port reorders), `migration/spikes/SPIKE-R1-sealevel.md` (protocol
design v1 §3), `migration/spikes/SPIKE-R11-saveddata.md` (D-R11-1 constant + D-R11-3 loss signatures).
**API-maps amended by this stage:** `migration/api-map/portal-generation.md` — the **[S7] amendment
block** (R11 erratum on row 1 per SPIKE-R11 D-R11-5; R11(i) propagation note on row 5). One governed
category-(c) resolution recorded in THIS note, not an api-map file: the `CustomTextOverlay`/
`MixinGui_Overlay` render-family rewrite (§6), deferred to the S11/S12 render api-map.

This note is the **S7 commit-4 deliverable**. It consolidates the three working fragments
(`migration/fragments/S07-{qmisc,net,global}.md`, deleted at stage end) plus probe/verify evidence.

---

## 0. Stage result (build + probe evidence)

| Gate | Command | Result |
|---|---|---|
| Shipping build | `:common:compileJava :fabric:compileJava` (`ip_scc_closed=false`) | **BUILD SUCCESSFUL** — all 19 U5 files held, invisible to javac; invariant preserved (`:fabric` uses the identical MAIN held-list wiring) |
| Compile probe | `:common:compileJava -Pip_scc_closed=true` | **BUILD FAILED (expected)** — every error on a U5 file maps to the documented S7(b) forward-ref union, the S4-exit loader-facade debt, or the two governed category-(c) amendments (§7). **Zero translation slips on the U5 slice.** |

**Interim per-slice probe counts (measured during the slice-by-slice port, before consolidation):**
Slice B (imm_ptl network) recorded **472** whole-tree errors; Slice A (q_misc_util) recorded **495**,
each at a different landing point (the count moves only with landing order, never with a translation
slip — the S6 lesson). Held-paths coverage required **no `IpHeldPaths` edit**; all 19 files arrive
default-held, so the shipping gate stays green because javac never sees them.

**U5 slice = 19 files, in three landing slices:**
- **Slice A — `q_misc_util` (8):** `MiscNetworking`, `ImplRemoteProcedureCall`,
  `api/McRemoteProcedureCall`, `CustomTextOverlay`, `mixin/dimension/MixinPlayerList_Misc`,
  `mixin/MixinMinecraftServer_Misc`, `mixin/client/IEClientPacketListener_Misc`,
  `mixin/client/MixinGui_Overlay`. Plus the new mixin config
  `common/src/main/resources/seamlessportals-ip-qmisc.mixins.json` (created, **NOT registered** — D1;
  registered S13 step 2 with the other three IP configs; it lists all 5 q_misc_util mixins —
  `IELevelStorageAccess_Misc` landed at S4 + these 4).
- **Slice B — `imm_ptl/core/network` (4):** `PacketRedirection`, `PacketRedirectionClient`,
  `ImmPtlNetworking`, `ImmPtlNetworkConfig`.
- **Slice C — `imm_ptl/core` global portals + API (7):** `portal/global_portals/{GlobalTrackedPortal,
  VerticalConnectingPortal, WorldWrappingPortal, BorderBarrierFiller, GlobalPortalStorage}`,
  `api/{PortalAPI, ImmPtlEntityExtension}`. (`api/example/ExampleGuiPortalRendering` is NOT in U5 —
  it is U11/S13 closure set, EXECUTION_PLAN §1.)

---

## 1. Diff-gate record (D4.3) — per file (`git diff --no-index` vs IP)

Every hunk below is traceable to an api-map row, the recurring-rename list, an S0 loader-seam
substitution, or a spike-mandated deliverable (R1/R7/R11). Blank-line indentation (IP keeps
4/8/12-space whitespace on blank lines) is byte-preserved in the imm_ptl files; the Slice-A files
normalize trailing whitespace on blanks (semantically inert, verified no non-whitespace hunk beyond
those tabulated). Ports were made by byte-copying IP then applying only the tabulated hunks.

### Slice A — `q_misc_util`

| File | Hunks |
|---|---|
| `MiscNetworking.java` | **(a) R1** (§3): 3rd record component `dimSeaLevelTag`; server fill; 3rd write/read; `handle()` seaLevel map build + `ClientWorldLoader.dimIdToDimSeaLevel` assign. **(b) S0 seam** (B2): `-PayloadTypeRegistry/-ServerPlayNetworking/-ClientPlayNetworking`, `+PlatformHelper`, `+ClientboundCustomPayloadPacket`; `createPacket` `ServerPlayNetworking.createS2CPacket(p)`→`new ClientboundCustomPayloadPacket(p)` (F2/F3 GONE); `init()` `playS2C().register`→`registerClientboundPayload`; `initClient()` `ClientPlayNetworking.registerGlobalReceiver(TYPE,(p,c)->p.handle())`→`registerClientPayloadHandler(TYPE,(p,client)->p.handle())` (F1). **(c) C9** `registryOrThrow`→`lookupOrThrow`. **(d) C10** `.location()`→`.identifier()` ×3 (dimId + `BuiltinDimensionTypes.OVERWORLD`). **(e) C1** `ResourceLocation dimTypeId`→`Identifier`. **(f) C13** `getAllKeys()`→`keySet()`, `getString(key)`→`getStringOr(key,"")`. |
| `ImplRemoteProcedureCall.java` | **(a) S0 seam**: `-ServerPlayNetworking/-ClientPlayNetworking/-PayloadTypeRegistry`, `+PlatformHelper/+ClientboundCustomPayloadPacket/+ServerboundCustomPayloadPacket`; `init()` 2× register→`registerServerboundPayload`/`registerClientboundPayload`, `ServerPlayNetworking.registerGlobalReceiver(C2S.TYPE, C2S::handle)`→`registerServerPayloadHandler(...)`; `initClient()` `ClientPlayNetworking.registerGlobalReceiver(S2C.TYPE, S2C::handle)`→`registerClientPayloadHandler(...)`; `createC2SPacket`→`new ServerboundCustomPayloadPacket(p)` (F3 GONE); `createS2CPacket`→`new ClientboundCustomPayloadPacket(p)` (F2 GONE). **(b) handler-signature seam**: `C2SRPCPayload.handle(ServerPlayNetworking.Context c)`→`handle(ServerPlayer player)` (`c.player()`→`player`; the method-ref `C2SRPCPayload::handle` now satisfies `ServerPayloadHandler<T>`=`(payload,sender)->…`); `S2CRPCPayload.handle(ClientPlayNetworking.Context c)`→`handle(Minecraft client)` (`client` unused, matches `ClientPayloadHandler<T>`). **(c) C1/C3/C10** `ResourceLocation`→`Identifier`, `writeResourceLocation`→`writeIdentifier`, `.location()`→`.identifier()`, `::readResourceLocation`→`::readIdentifier`, `readResourceLocation()`→`readIdentifier()` ×2. **(d) G2** `clientTellFailure`: `Minecraft.getInstance().gui.getChat().addMessage(m)`→`.gui.hud.getChat().addClientSystemMessage(m)`. |
| `api/McRemoteProcedureCall.java` | **IDENTICAL — 100% verbatim (0 hunks).** `player.connection.send(packet)` (S22 SAME) + `Minecraft.getInstance().getConnection().send(packet)` (S14 SAME) survive; RPC FQN wire semantics untouched (§5). |
| `mixin/dimension/MixinPlayerList_Misc.java` | **G3** `MiscNetworking.DimIdSyncPacket.createPacket(player.server)`→`createPacket(player.level().getServer())` (ONLY hunk; injection point `@At(INVOKE, ClientboundChangeDifficultyPacket.<init>)` + descriptor unchanged — network.md S17). |
| `mixin/MixinMinecraftServer_Misc.java` | **C6** `super(string)`→`super(string, false)`; **C7** `onConstruct(Thread,…,ChunkProgressListenerFactory, CallbackInfo)`→`onConstruct(CallbackInfo)` (+drop 7 arg imports); **C8** `onWorldsCreated(ChunkProgressListener, CallbackInfo)`→`onWorldsCreated(CallbackInfo)`; **S30** shadow `public storageSource`→`protected`. Diff verified: only these 4 hunks (+ removed imports). |
| `mixin/client/IEClientPacketListener_Misc.java` | **IDENTICAL — 100% verbatim (0 hunks).** `@Accessor("levels")` on `Set<ResourceKey<Level>>`; field still `private` non-final, plain `@Accessor` (network.md S9). |
| `CustomTextOverlay.java` | 26.2 GUI-render re-derivation — **category-(c), see §6.** |
| `mixin/client/MixinGui_Overlay.java` | 26.2 GUI-render re-target — **category-(c), see §6.** |

### Slice B — `imm_ptl/core/network`

| File | +/− | Hunks |
|---|---|---|
| `PacketRedirectionClient.java` | **0 / 0** | **IDENTICAL — 100% verbatim.** This is the R7 §A treatment for the file (§2): the `minecraft.execute` fallback in `handleRedirectedPacket` is kept verbatim (COLD under §A). The `@Deprecated old_handleRedirectedPacket` dead code ports verbatim incl. its stale `@link PacketUtils#ensureRunningOnSameThread(Packet, PacketListener, BlockableEventLoop)` — that overload is G1-GONE, but javac does not validate `@link`; the 3 javadoc-only imports still resolve, so it compiles byte-identical (noted for any future doclint). |
| `PacketRedirection.java` | 6 / 6 | **(a) S0 seam** `-PayloadTypeRegistry`, `+PlatformHelper`; `init()` `PayloadTypeRegistry.playS2C().register(Payload.TYPE, Payload.CODEC)`→`PlatformHelper.getInstance().registerClientboundPayload(...)` (F1/B2). **(b) C1** `ResourceLocation`→`Identifier` (import + `payloadId`). **(c) G3** `player.server`→`player.level().getServer()` ×2 (:122, :181). |
| `ImmPtlNetworking.java` | 17 / 18 | **(a) S0 seam** `-ClientPlayNetworking/-PayloadTypeRegistry/-ServerPlayNetworking`, `+PlatformHelper`; `init()` 2× `playS2C().register`→`registerClientboundPayload`, 1× `playC2S().register`→`registerServerboundPayload`, `ServerPlayNetworking.registerGlobalReceiver(TeleportPacket.TYPE,(packet,c)->packet.handle(c.player()))`→`registerServerPayloadHandler(TeleportPacket.TYPE,(packet,sender)->packet.handle(sender))`; `initClient()` 2× client receivers→`registerClientPayloadHandler`. **(b) C1** `ResourceLocation`→`Identifier` (import + `Identifier.fromNamespaceAndPath("imm_ptl","teleport")`). **(c) C11** `entityType.create(world)`→`create(world, EntitySpawnReason.LOAD)`. **(d) C12** `entity.moveTo(x,y,z)`→`snapTo(x,y,z)`. **(e) G3** `player.server`→`player.level().getServer()` ×2 (:78, :81). |
| `ImmPtlNetworkConfig.java` | 5 / 4 | **(a) F2/F4** `ServerConfigurationNetworking.createS2CPacket(new S2CConfigStartPacket(v))`→`new ClientboundCustomPayloadPacket(new S2CConfigStartPacket(v))` (+import; `createS2CPacket` GONE). **(b) F4** `context.networkHandler()`→`context.packetListener()` (:160). **(c) C14** `gameProfile.getName()/getId()`→`name()/id()` ×2 (record-style bundled authlib). Config-phase Fabric-API imports held verbatim (facade debt, §7). |

### Slice C — `imm_ptl/core` global portals + API

| File | Verdict | Hunks |
|---|---|---|
| `api/ImmPtlEntityExtension.java` | VERBATIM | none (byte-identical modulo whitespace). |
| `global_portals/GlobalTrackedPortal.java` | VERBATIM | none. |
| `global_portals/VerticalConnectingPortal.java` | VERBATIM | none — touches no CHANGED/GONE MC API; `Helper.combineNullable`, `DQuaternion.*`, `McHelper.getMinY/getMaxContentYExclusive`, `PortalExtension.get(...).adjustPositionAfterTeleport`, `MiscHelper.getServer().getLevel(...)`, `dimensionType().coordinateScale()` (SAME row S16) all survive. |
| `global_portals/WorldWrappingPortal.java` | translated | **GONE row 4** `Tuple`→`Pair` (`.getA()/.getB()`→`.getFirst()/.getSecond()`; held `Helper.getPerpendicularDirections` already returns `Pair`, `Helper.java:392`); **`Direction.getNormal()`→`getUnitVec3i()`**; **CHANGED row 4** `ENTITY_TYPE.create(w)`→`create(w, EntitySpawnReason.LOAD)` (uniform S6 convention for programmatic portal creation); **CHANGED row 13** `getBoolean/getInt`→`getBooleanOr/getIntOr` (guarded by existing `contains`, defaults inert). The `readAdditionalSaveData(CompoundTag)`/`addAdditionalSaveData(CompoundTag)` stay `@Override` of the CompoundTag form (S6 canonical serialization surface; the ValueInput/ValueOutput bridge lives one level up in `Portal`). |
| `api/PortalAPI.java` | translated | GONE row 4 `Tuple`→`Pair` + `.getNormal()`→`.getUnitVec3i()`; **recurring rename** `entity.getServer()`→`entity.level().getServer()`. Forward-refs kept HELD (§7 debt). |
| `global_portals/BorderBarrierFiller.java` | translated | **CHANGED row 22** `player.displayClientMessage(msg,false)`→`player.sendSystemMessage(msg)` ×5 (all sites pass `false`); **CHANGED row 10** `chunk.setBlockState(pos, AIR, false)`→`setBlockState(pos, AIR, Block.UPDATE_ALL)` (3rd arg boolean→`@Block.UpdateFlags int`; nearest-faithful `UPDATE_ALL`=3; manual `lightingProvider.checkBlock` after is unchanged, SAME row S18). |
| `global_portals/GlobalPortalStorage.java` | translated | **R11 (F15)** + NBT getters + F3 seam + renames — **see §4 (the headline).** |

---

## 2. R7 §A implementation — mapped to SPIKE-R7-requeue.md (F7)

The §A order-faithful redirection mechanism spans four code sites. **This stage delivers the two
plain classes that live in the U5 network unit and ports the wire format 1:1; the two mixins are
documented forward-refs at their own stages** (S10 common, S12 client — S7(b)). SPIKE-R7 CONFIRMED §A
with **zero inversions** and a negative control that proved the naive IP-1.21 shape reorders *every
frame* (all 8 later-sent vanilla packets processed before all 8 earlier redirected ones,
`Minecraft.java:1169-1172` drains `scheduledPacketProcessing` before `scheduledExecutables`).

| §A component | SPIKE-R7 ref | Where | This stage? |
|---|---|---|---|
| **Wire format 1:1** — `PacketRedirection.Payload` via `GameProtocols.CLIENTBOUND_TEMPLATE.bind(argBuf -> (RegistryFriendlyByteBuf) argBuf)` + `PLACEHOLDER_PROTOCOL_INFO.codec().encode/decode` | network.md S5; SPIKE §5.1 | `PacketRedirection.java` | **YES — ported 1:1, verbatim** (the single most version-sensitive line; compiles unchanged). |
| **`handleRedirectedPacket` inline path + COLD `minecraft.execute` fallback** (isSameThread-true → inline; isSameThread-false → re-submit, retained but never runs for the outer hop under §A) | SPIKE §5.2 ("keep the branch verbatim — fidelity + defense, it is simply cold") | `PacketRedirectionClient.java` | **YES — 100% verbatim** (0 hunks). IP's own `minecraft.isSameThread()` guard (:51) ported as-is. |
| **Netty-pass re-queue of the OUTER packet** — `minecraft.packetProcessor().scheduleIfPossible(listener, outerPacket); ci.cancel();` on the netty pass; inline `redirectPayload.handle(...)` + `ci.cancel()` on the main pass. Guard = `packetProcessor().isSameThread()` (vanilla's own gate in `ensureRunningOnSameThread`). | network.md §A step 2; SPIKE §5.1 + §3 | `mixin/common/networking/MixinClientboundCustomPayloadPacket` | **NO — S10** (common mixin, U8). **HANDOFF below.** |
| **`wrapRunnable` + `scheduleExecutables` narrowed** (redirect only `Minecraft.execute` tasks submitted DURING redirected handling) | network.md §A step 3; SPIKE §5.3 | `mixin/client/sync/MixinMinecraft_RedirectedPacket` | **NO — S12** (client mixin, U10); javadoc-`@link`-imported by `PacketRedirectionClient`. |

### ⚠ S10 HANDOFF — do NOT port the stock IP `MixinClientboundCustomPayloadPacket` verbatim

IP's stock mixin, on the netty pass, does `redirectPayload.handle(...)` → `handleRedirectedPacket` →
`!isSameThread` → `minecraft.execute(resubmit)` + `ci.cancel()` — this **IS the naive EXECUTE shape
SPIKE-R7 proved reorders** (§1 verdict `mode=EXECUTE REORDERED`). The §A correction, applied when the
mixin lands at S10 (recorded per D4.2 as an api-map-§A-justified deviation from the stock mixin):

```java
@Inject(method="handle(Lnet/minecraft/network/protocol/common/ClientCommonPacketListener;)V",
        at=@At("HEAD"), cancellable=true)
private void onHandle(ClientCommonPacketListener listener, CallbackInfo ci) {
    if (payload instanceof PacketRedirection.Payload redirectPayload) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.packetProcessor().isSameThread()) {                     // netty pass
            mc.packetProcessor().scheduleIfPossible(
                listener, (Packet<ClientCommonPacketListener>)(Object) this);
        } else if (listener instanceof ClientGamePacketListener cgpl) {  // main pass
            redirectPayload.handle(cgpl);
        }
        ci.cancel();
    }
}
```

`scheduleIfPossible` accepts the outer `ClientboundCustomPayloadPacket` with the mixin-arg `listener`
(SPIKE §4 measured this exact call — `Packet<ClientCommonPacketListener> outer = (ClientboundCustomPayloadPacket)(Object)this`,
no casts beyond that); `RejectedExecutionException`-after-close + `shouldHandleMessage` drop are
vanilla's own disconnect semantics (more faithful than raw `minecraft.execute`). This is the
`IECustomPayloadPacket` duck target — duck + mixin both land S10.

### isSameThread-guard inventory (SPIKE-R7 §3 doctrine)

The netty pre-pass fires a packet-handler HEAD inject **TWICE — once per thread — even in
singleplayer** (SPIKE §3: `customEnter(netty=8,main=8)`; the local pipeline hops through
`Netty Local IO #1`). Every packet-handler injection written in U5-adjacent stages therefore carries
the `packetProcessor().isSameThread()` guard. Full inventory of packet-handler injections that touch
U5-landed code (this stage writes NONE that need the guard — proof, not omission):

| Injection | Stage | Packet-handler? | isSameThread guard | Rationale |
|---|---|---|---|---|
| `mixin/common/networking/MixinClientboundCustomPayloadPacket` | S10 | **YES** — client `ClientboundCustomPayloadPacket.handle` | **REQUIRED** (`packetProcessor().isSameThread()`; code block above) | The §A netty double-invocation this doctrine exists for. |
| `mixin/client/sync/MixinMinecraft_RedirectedPacket` | S12 | narrows `Minecraft.execute` during redirect | n/a (task-scheduling hook, not a packet `handle`) | §A step 3; no HEAD-inject on a packet handler. |
| `q_misc_util/mixin/dimension/MixinPlayerList_Misc` | **S7 (this stage)** | **NO** — injects `placeNewPlayer` (server login path) at `ClientboundChangeDifficultyPacket.<init>` INVOKE | **N/A** — not a packet `handle`; runs once, server-side login | The seaLevel send site (§3); no client netty double-pass applies. |
| `q_misc_util/mixin/MixinMinecraftServer_Misc` | **S7 (this stage)** | **NO** — server `<init>`/`createLevels` construction taps | **N/A** | Server-thread constructor, single invocation. |
| `q_misc_util/mixin/client/MixinGui_Overlay` | **S7 (this stage)** | **NO** — GUI render inject (`Gui.extractRenderState` RETURN) | **N/A** — render thread, not a packet handler | §6; render-family concern. |

**Conclusion:** Slice A/B write **no client packet-handler HEAD injection**, so no
`packetProcessor().isSameThread()` guard binds any file that landed this stage. The doctrine binds the
S10/S12 mixins above (the S10 code block already carries it). This is recorded so the S10/S12 executor
cannot miss it.

---

## 3. R1 seaLevel protocol — implemented (F8, SPIKE-R1 §3 design v1)

The 26.2 `ClientLevel` ctor's trailing `int seaLevel` is `private final` — a wrong construction-time
value is **permanent for that level's whole life**, and the level gets PROMOTED to `mc.level` on
crossing (API_RISKS R1 / SPIKE-R1 §1.2). A not-yet-visited dimension has no vanilla seaLevel source
until the player actually travels there, so the value must be synced ahead of time. Implemented
exactly per the memo's design v1, riding the existing dim-id sync path (`MiscNetworking.DimIdSyncPacket`,
the mod-owned `imm_ptl:dim_int_id_sync` channel — both ends ship together, no cross-version concern):

| Piece | Implementation (this stage) |
|---|---|
| **Wire field** | `DimIdSyncPacket` gains a THIRD record component `CompoundTag dimSeaLevelTag` (append-only, after the existing `dimIntIdTag`/`dimTypeTag` pair). |
| **write/read** | third `buf.writeNbt(dimSeaLevelTag)` / third `buf.readNbt()`. |
| **Server fill** | in `createFromServer`'s existing `server.getAllLevels()` loop: `dimSeaLevelTag.putInt(dimId.identifier().toString(), world.getSeaLevel())` (`.location()`→`.identifier()`, C10). **Bit-identical to vanilla's own spawn-info source** (`26.2:ServerPlayer.createCommonSpawnInfo` passes `level.getSeaLevel()`) — the synced value can never disagree with what vanilla sends when the player travels there. **No new send sites** (SPIKE-R1 §3): the packet is rebuilt from `getAllLevels()` per send, so seaLevel rides both existing senders automatically (login + dynamic-dim rebuild, below). |
| **Client cache scaffold** | `handle()` mirrors the existing `dimIdToDimTypeId` handling EXACTLY: builds `ImmutableMap<ResourceKey<Level>, Integer>` from `dimSeaLevelTag.keySet()` (`getIntOr(key, 0)`), then replaces the client cache WHOLESALE: `ClientWorldLoader.dimIdToDimSeaLevel = dimSeaLevelMap`. |

**Login order preserved (DEPENDENCY_ORDER §4.2 / seam inventory A3).** `MixinPlayerList_Misc` still
injects at the `ClientboundChangeDifficultyPacket.<init>` INVOKE inside `placeNewPlayer` (descriptor
unchanged, network.md S17), so `DimIdSyncPacket` (now carrying seaLevel) is sent **BEFORE** the
difficulty packet, before any chunk/portal/redirected payload. The dynamic-dim rebuild
(`DimensionIntId.onServerDimensionChanged`, landed S4) is the second sender; new dims' seaLevels
arrive before any portal can target them (the same packet is what makes the dim constructible at all).

### ⚠ S10 HANDOFF — `ClientWorldLoader.dimIdToDimSeaLevel` field (client consumption)

The `handle()` assignment is a forward-ref that **subsumes under the same documented U8
`ClientWorldLoader`-missing debt** as the pre-existing `dimIdToDimTypeId` line (S7(b)) — it adds NO
new distinct probe symbol at S7. But the FIELD itself is a mod addition (IP's `ClientWorldLoader` has
`dimIdToDimTypeId`, not `dimIdToDimSeaLevel`). **S10 must, beside `dimIdToDimTypeId`
(`IP:ClientWorldLoader.java:72`):**

```java
@Nullable public static ImmutableMap<ResourceKey<Level>, Integer> dimIdToDimSeaLevel;
```

1. **Declare** it as above.
2. **Null** it on client exit exactly where `dimIdToDimTypeId` is nulled
   (`IP:ClientWorldLoader.java:98-100`).
3. **Consume** it in `createSecondaryClientWorld`: pass `dimIdToDimSeaLevel.get(dimension)` as the
   `ClientLevel` ctor's trailing `seaLevel` arg (SPIKE-R1 §3 "Consumption (S10)"; 26.2 ctor
   `ClientLevel.java:238-249`, the 1.21.3 call site being ported is `ClientWorldLoader.java:445-456`).
4. **Fallback** when unset (ordering makes this near-impossible-by-construction — the same packet's
   dim-type map is already a hard dependency of `createSecondaryClientWorld`): use
   `CLIENT.level.getSeaLevel()` + WARN once per dim. **Fail-soft, never throw** (SPIKE-R1 §3; matches
   IP's rate-limited remote-world doctrine). The field is final, so a later `DimIdSyncPacket` cannot
   retro-fix a constructed level; if a mismatch is ever observed the S10-time option is
   dispose+recreate the secondary while it has zero chunks — NOT in v1.

Because the `handle()` assignment MUST live in this S7-owned file (stage ownership), the field must
exist by S10 or the probe stays red on `dimIdToDimSeaLevel` from S10 onward.

**What v1 deliberately does NOT do (SPIKE-R1 §3):** no per-dim seaLevel in `CommonPlayerSpawnInfo`
mimicry, no dimension-type-keyed defaults table (disproven — flat OW = −63), no vanilla-packet codec
extension (R8-style stamping is for position packets; this is mod-channel data).

---

## 4. R11 SavedDataType — GlobalPortalStorage (F15, SPIKE-R11) — the headline deliverable

### GONE row 1 mechanism swap

IP declared persistence with
`world.getDataStorage().computeIfAbsent(new SavedData.Factory<>(constructorSupplier, deserializer, null), "global_portal")`.
26.2 has **no `SavedData.Factory`** and **no `SavedData#save`**; per-dimension persistence is a
`SavedDataType<T>(Identifier id, Supplier<T> constructor, Codec<T> codec, DataFixTypes dataFixType)`
consumed by `SavedDataStorage.computeIfAbsent(SavedDataType)` (`SavedDataStorage.java:65`). Translation
(closing over `world` exactly as IP's Factory did, so the per-dimension `world` reference is available
to both constructor and codec):

```java
computeIfAbsent(new SavedDataType<>(
    SAVED_DATA_ID,                               // McHelper.newResourceLocation("global_portal") == minecraft:global_portal
    () -> { LOGGER.info(...); return new GlobalPortalStorage(world); },   // IP's constructor supplier, verbatim
    createDataCodec(world),                       // IP's (nbt,holderLookup) deserializer body, wrapped (below)
    DataFixTypes.SAVED_DATA_COMMAND_STORAGE       // SPIKE-R11 D-R11-1: proven zero-fix constant (was null on 1.21.3)
))
```

`extends SavedData` is KEPT (26.2 `SavedData` is still the dirty-flag base; `computeIfAbsent` returns
`T extends SavedData`). The verbatim `ServerTickEvents.END_SERVER_TICK.register(...)` in `init()` and
the `DimensionAPI.SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT.register(...)` (DimLib F11 stub) are retained.

### DataFixTypes constant = `SAVED_DATA_COMMAND_STORAGE` (SPIKE-R11 D-R11-1)

Vanilla uses this constant for arbitrary `/data storage` NBT (`26.2:CommandStorage.java:80`), so Mojang
can never write a shape-assuming fix against its TypeReference (registered `DSL::remainder` passthrough
in `V99.java:291`/`V1460.java:301`; a grep of `util/datafix/fixes/` confirms **zero fixes target it** —
`DataFixTypes` has no `NONE`). Chosen over `null` because null's load-safety is only a fabric-api loader
patch (`SavedDataStorageMixin.handleNullDataFixType`) that a distribution can jar-exclude (D-R11-2); a
zero-fix constant costs nothing on same-version loads (D-R11-4: `update` is `if (version < newVersion)…
return input` — same-version returns the input untouched) and keeps behavior identical to
IP-on-fabric-api (= no destructive fixing). Note IP's own 1.21.3 `null` was always riding the same
Fabric-API rescue.

### Codec = `CompoundTag.CODEC.xmap(decode, encode)` (D-R11-6 / portal-generation.md G1 row 2)

A CompoundTag pass-through that keeps IP's GPS tag layout **byte-shape**. Encode =
`storage.save(new CompoundTag(), world.registryAccess())` (IP's persistence tag). Decode =
`new GlobalPortalStorage(world)` + `fromNbt(tag)` (IP's Factory deserializer body, wrapped by the LOUD
guard below). The outer on-disk wrapper `{DataVersion, data:<gps-compound>}` is vanilla's
(`SavedDataStorage.encodeUnchecked:198-204`; `readSavedData` parses `tag.get("data")` :92-95); only the
INNER gps compound `{data:[<portal-nbt + entity_type>...], version, bedrockReplacement}` must stay
byte-shaped, and it does.

### SavedDataType id = `minecraft:global_portal`

`McHelper.newResourceLocation("global_portal")` = `Identifier.parse("global_portal")` =
`minecraft:global_portal` — the lowest-judgment 1:1 translation of IP's literal DimensionDataStorage key
`"global_portal"`, wrapped in the same helper IP uses for every string→Identifier conversion. 26.2 forces
an Identifier (portal-generation.md G1 row 2 "the string name moves into `SavedDataType.id`). On-disk
path becomes `<dim>/data/minecraft/global_portal.dat` (SPIKE-R11 D-R11-5; no collision — no vanilla
`global_portal` saved data). **Alternative considered:** `immersive_portals:global_portal` (matches the
S6 entity-type namespace) — rejected as inventing a namespace where IP hardcoded none. **Changing the id
changes the S13 grep tokens below.**

### `save(CompoundTag, HolderLookup.Provider)` — `@Override` removed (GONE row 1)

26.2 `SavedData` has no `save` method, so the `@Override` is dropped; the method is retained verbatim
otherwise (it is BOTH the codec's encode side AND is called directly by `createSyncPacket`). One inner
change — **row 2** ValueOutput bridge: `portal.saveWithoutId(portalTag)`→
`TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries)` → `saveWithoutId(output)` →
`CompoundTag portalTag = output.buildResult()`, then the verbatim `portalTag.putString("entity_type", …)`
+ `listTag.add(portalTag)`. IP's on-disk tag layout is preserved byte-for-byte. The load side mirrors it
(**row 3**): `e.load(compoundTag)`→
`e.load(TagValueInput.create(ProblemReporter.DISCARDING, currWorld.registryAccess(), compoundTag))` (idiom
matches held `McHelper.copyEntity`, `McHelper.java:413-419`).

NBT-getter + entity translations (CHANGED rows 13/14/15/4): `getInt→getIntOr`,
`getCompound→getCompoundOrEmpty`, `getList("data",10)→getListOrEmpty("data")`, `getString→getStringOr`
(all guarded by the existing `contains`, defaults inert; `NbtUtils.readBlockState`/`writeBlockState`
SAME row S26); `BuiltInRegistries.ENTITY_TYPE.get(id)→.getValue(id)` (**row 15 fidelity default** —
replicate 1.21.3's defaulted-registry behavior: unknown id → PIG, still fails the `(Portal) e` cast
exactly as on 1.21.3; observable behavior identical, flagged for adversarial review §6);
`entityType.create(w)→create(w, EntitySpawnReason.LOAD)`.

`createSyncPacket(...)` — **F3 seam** (matches the S6 `Portal.java:959-967` precedent):
`ServerPlayNetworking.createS2CPacket(payload)`→`new ClientboundCustomPayloadPacket(payload)`; the
record directly `implements Packet<ClientCommonPacketListener>` so it matches the return type with no
cast. `world.getServer()` stays (`Level.getServer()`, not the removed `Entity` method).

### The LOUD load-failure guard (never swallow) — SPIKE-R11 D-R11-3

`deserializeWithGuard(world, tag)` wraps the decode:
`try { new GlobalPortalStorage(world); fromNbt(tag); } catch (RuntimeException e) { LOGGER.error(…loud,
mod-branded, names dimension + overwrite hazard…); throw e; }`.

**Why it is needed:** `SavedDataStorage.readSavedData` (`SavedDataStorage.java:86-102`) wraps the WHOLE
codec read in `catch (Exception)`. On ANY read/parse failure it logs one of two easy-to-miss ERROR lines,
returns `null`, and `computeIfAbsent` then **SILENTLY constructs a fresh empty storage, marks it dirty**
(`set` :104-107 `setDirty()`), and the next save **OVERWRITES** the existing `global_portal.dat` — silent
data loss, not a crash. IP's per-portal tolerance (`getPortalsFromTag` skips a bad entry via `Helper.err`,
verbatim) is preserved, but a TOP-LEVEL failure would otherwise be swallowed into an empty-and-overwrite.
The guard **never swallows** — it rethrows so vanilla's own signature also fires — and adds a loud,
greppable, mod-branded line a busy log cannot hide. `catch (RuntimeException)` covers all realistic
failures (Validate → IAE/NPE, ClassCastException on unknown entity_type, malformed structure) and is
`throw`-compatible; `fromNbt` throws nothing checked.

### S13 relog check — grep signatures (D-R11-3)

The S13 global-portal relog check must grep the server log for **BOTH** vanilla silent-loss lines (this
file's SavedDataType id in brackets), plus the mod-branded guard line:

```
Error loading saved data: SavedDataType[minecraft:global_portal]           <- signature A (read/fix exception)
Failed to parse saved data for 'SavedDataType[minecraft:global_portal]':    <- signature B (codec-rejects, no stacktrace)
[IP][R11] GlobalPortalStorage load FAILED for dimension                     <- the added guard (rethrows into A)
```

A successful relog with pre-existing global portals must show **NONE** of these and must re-render the
global portals in their dimensions. (Signature A is where a vanilla null-NPE and file corruption both
land; signature B is the codec-rejects case, logged WITHOUT a stacktrace — the easy-to-miss one.)

---

## 5. RPC FQN wire-protocol note (D2 / seam inventory A7)

`McRemoteProcedureCall` addresses handlers by **literal fully-qualified method-path strings** resolved at
runtime via `ImplRemoteProcedureCall.findMethodByPath` → `Class.forName(classPath)` (with a `$`-inner-class
retry) + reflective `Method` lookup (security gate: the path MUST contain `"RemoteCallable"`). **These
strings ARE the wire protocol.** Fidelity is answered wholesale by **D2 verbatim `qouteall.*` package
retention**: the ported classes keep IP's exact packages, so every caller's FQN literal (e.g.
`qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos`) still
resolves via `Class.forName` with zero edits.

**No literal FQN string appears in `ImplRemoteProcedureCall`/`McRemoteProcedureCall` themselves** —
callers (later stages) pass them — so there was nothing to translate here; the constraint binds the SENDER
stages, satisfied automatically by D2. No rename, no whitelist, no reflection-site patch: the entire RPC
hazard class is zero by construction (S00-U0-decisions D2 rationale 1). `api/McRemoteProcedureCall.java`
is 100% verbatim (§1) precisely because of this — `player.connection.send(packet)` and
`Minecraft.getInstance().getConnection().send(packet)` both survive unchanged (S22/S14 SAME).

---

## 6. Category-(c) resolutions + api-map amendments

### (c-1) `CustomTextOverlay` + `MixinGui_Overlay` — 26.2 GUI-render rewrite (render-family, out of network.md scope)

**Finding.** 26.2 rewrote the client GUI render surface into an *extract* model that network.md does not
cover (and I was not given the render api-maps). The two files land HERE because the q_misc_util mixin
JSON groups the whole `qouteall.q_misc_util.mixin` package, so its client members ride S7 (plan mandate).
They land with a **compile-clean 26.2 re-derivation** whose render CORRECTNESS is a documented residual to
verify at **S11/S12** with the render api-map + D4.4 sign-derivation gate. Both are HELD + unregistered
until S13 — zero runtime impact until then. **Ledger amendment recorded here per D4.2** (a vanilla-API
rewrite the S7(b) paper ledger could not foresee; probe-verified compile-clean, §7).

The 26.2 facts driving the re-derivation: `GuiGraphics` no longer exists (→`GuiGraphicsExtractor`,
`client/gui/GuiGraphicsExtractor.java`); `Gui.render(GuiGraphics, float)` → `Gui.extractRenderState(
DeltaTracker, boolean shouldRenderLevel, boolean resourcesLoaded)` (`Gui.java:148`); `MultiLineLabel`
dropped `renderCentered`/`renderLeftAligned` for the `visitLines(...)` model; `Minecraft.getProfiler()`→
`Profiler.get()`; `Gui.getFont()` gone (→`minecraft.font`); `GuiGraphics.pose().pushPose/popPose`→
`Matrix3x2fStack.pushMatrix/popMatrix`.

- **`CustomTextOverlay.render`** — param `GuiGraphics`→`GuiGraphicsExtractor`. `MultiLineLabel` cache
  replaced by a pre-split `List<FormattedCharSequence>` (`Font.split(component, guiScaledWidth-20)`
  reproduces the exact explicit-newline + word-wrap behaviour `MultiLineLabel.create` gave); centered path
  → `guiGraphics.centeredText(font, line, w/2, y, 0xffffffff)` per line (+9 line height), left path →
  `guiGraphics.text(font, line, 10, y, 0xffffffff)`. `Profiler.get().push/pop`; `minecraft.font`;
  `pose().pushMatrix/popMatrix`. The cache-lifecycle (null on putText/remove/expiry) is byte-preserved.
  **Verify at S11/S12:** exact line height (9 assumed), drop-shadow (centeredText default), y-anchor.
- **`MixinGui_Overlay`** — IP injects at `Gui.render` RETURN; re-targeted to `Gui.extractRenderState`
  RETURN, capturing the local `GuiGraphicsExtractor` via **classic Sponge
  `LocalCapture.CAPTURE_FAILHARD`** (MixinExtras `@Local` is NOT on the common compile classpath at S7 —
  verified: no landed mixin imports `com.llamalad7.mixinextras`). Gate `!minecraft.options.hideGui` →
  `shouldRenderLevel` (26.2 relocated hideGui off `Options`; `shouldRenderLevel` is the closest
  compile-clean signal). **Verify at S11/S12:** exact injection point, FAILHARD captured-local order
  (`ProfilerFiller, int xMouse, int yMouse, GuiGraphicsExtractor graphics`), and the
  `shouldRenderLevel`-vs-`hideGui` gate semantics — all runtime concerns (unregistered until S13).

### (c-2) api-map/portal-generation.md — amended in the api-map FILE (git-tracked this stage)

Both amendments are written into `migration/api-map/portal-generation.md` (not just this note):

- **Row 1 (R11 mechanism) — ERRATUM added per SPIKE-R11 D-R11-5:** the corpus's "per-dimension file
  location semantics survive / `data/global_portal.dat` inside each dimension folder is unchanged" is
  empirically REFUTED. 26.2 per-dimension saved data lives at
  `<world>/dimensions/<dimNs>/<dimPath>/data/<typeNamespace>/<typePath>.dat` for ALL dimensions INCLUDING
  the overworld (`DimensionType.getStorageFolder`, `DimensionType.java:116-118`); `<world>/data/` belongs
  to the SEPARATE server-global store (`MinecraftServer.savedDataStorage`). No legacy-name migration exists
  — an old IP world's `data/global_portal.dat` is silently ignored (fine for this mod — it never shipped
  GlobalPortalStorage; rules out importing IP worlds' global portals without a manual step).
- **Row 5 (EntityType construction) — [S7] R11(i) propagation added:** the 3 `global_portals` subtypes
  (co-ported this stage) also consume the 2-arg `createPortalEntityType(factory, key)` —
  `GlobalTrackedPortal`→`portalEntityTypeKey("global_tracked_portal")`, `WorldWrappingPortal`→
  `"border_portal"`, `VerticalConnectingPortal`→`"end_floor_portal"`. Because these live in a different
  package from `Portal`, `Portal.portalEntityTypeKey(String)` was widened package-private → **`public
  static`** (single-source id helper, mirrors `Mirror.java`; package-private stays inaccessible to a
  cross-package subclass, so widening — not inheritance — is required). This wiring lands at S13
  registration; the widening is an S6-owned `Portal` change already reflected there.

### (c-3) fidelity default flagged for adversarial review

`BuiltInRegistries.ENTITY_TYPE.get(id)`→`.getValue(id)` in `GlobalPortalStorage` (row 15) replicates
1.21.3's *defaulted*-registry behavior (unknown id → PIG). Observable behavior is identical (an unknown
type still fails the `(Portal) e` cast exactly as on 1.21.3), so IP's implicit tolerance is preserved.
Flagged for adversarial review per portal-generation.md §3 note 3 — noted here so the S13/verify pass
re-examines it.

### Seam-routing boundary (S0 B2) recorded for both slices

**Play-phase payload registration → PlatformHelper seam (S0 B2, task-mandated).** `PacketRedirection.init`,
`ImmPtlNetworking.init/initClient`, `MiscNetworking.init/initClient`, `ImplRemoteProcedureCall.init/
initClient` route through `registerClientboundPayload` / `registerServerboundPayload` /
`registerServerPayloadHandler` / `registerClientPayloadHandler` (the exact B2 surface). The seam's
`StreamCodec<? super RegistryFriendlyByteBuf, T>` bound accepts BOTH the `RegistryFriendlyByteBuf` codecs
(`Payload`, `PortalSyncPacket`, `C2SRPCPayload`/`S2CRPCPayload`) AND the `FriendlyByteBuf` codecs
(`TeleportPacket`, `GlobalPortalSyncPacket`, `DimIdSyncPacket`) — probe-confirmed, no bound error.
`PacketRedirection` registers **only the clientbound payload type, no receiver** — correct: the redirected
payload is dispatched by the R7 §A HEAD-inject mixin (§2), never a Fabric receiver.

**Config-phase networking (`ImmPtlNetworkConfig`) → held VERBATIM as loader-facade debt (resolves S10),
NOT seamed.** The S0 B2 seam was scoped to PLAY payloads only (seam inventory B2 names
`ImmPtlNetworking`/`MiscNetworking`); no config-phase seam exists. `ImmPtlNetworkConfig` is entirely
configuration-phase networking + connection/login events; per the S06 §5.4 convention these Fabric-API
imports are held verbatim (same cutover as `@Environment`/`EnvType`), and the GONE/CHANGED **members**
inside that held region are translated now (F2/F4/C14) so it compiles against Fabric v6 the moment the
facade lands. Rationale for the asymmetry: play payloads are the cross-loader core the seam was built for
and the task mandates routing; the config handshake is Fabric-specific (NeoForge integration deliberately
unwired) and handled wholesale by the S10 facade cutover.

---

## 7. Probe-vs-U5-union triage (D4.2 — the S5 lesson: reduce to the union, don't stop at the self-diff-gate)

The `-Pip_scc_closed=true` probe compiles the **entire** held tree (S2 + S4 + S5 + S6 + S7). The
per-slice snapshots (472 for Slice B, 495 for Slice A — different landing points) span all landed-held
units; the count moves only with landing order. **The probe is the authoritative ledger** and every error
whose file is a U5 file reduces to exactly the documented S7(b) forward-ref union, the loader-facade debt,
or the two governed category-(c) amendments. **Grep-verified error shapes on U5 files are only "cannot
find symbol" / "package … does not exist" — zero "incompatible types" / "method cannot be applied" /
vanilla-symbol-not-found (a translation slip's signature) on any of the 19 files.**

**(i) U5-file errors reduce to the documented union ONLY:**

| Unresolved symbol (in-probe) | On U5 files | Owning stage | Kind |
|---|---|---|---|
| `net.fabricmc.api` `@Environment`/`EnvType` | MiscNetworking, ImplRemoteProcedureCall, McRemoteProcedureCall, CustomTextOverlay, PortalAPI, GlobalPortalStorage, all 4 network files, … | loader facade / **S10** | S4-exit loader-facade debt (S06 §5.4) |
| `net.fabricmc.fabric.api.{networking,client.networking}.v1.*` (config-phase) + F8 interface-injected `addTask`/`completeTask` | ImmPtlNetworkConfig (7 imports + :194,:217) | loader facade / **S10** | held Fabric-API verbatim (§6) |
| `ClientWorldLoader` (class + static-field refs) | MiscNetworking (:27 import; `:136` `dimIdToDimTypeId`; `:162` `dimIdToDimSeaLevel` R1), ImmPtlNetworking (:28,:183,:222), PacketRedirectionClient (:15,:59,:100), GlobalPortalStorage (:33) | U8 / **S10** | forward-ref (S7(b); the R1 ref subsumes under the same class-missing symbol — §3 HANDOFF) |
| `teleportation.ServerTeleportationManager` | ImmPtlNetworking (:34,:80), PortalAPI | U6 / **S8** | forward-ref (S7(b)) |
| `ChunkLoader` / `ImmPtlChunkTracking` | PortalAPI | U7 / **S9** | forward-ref (S7(b)) |
| `mixin.common.entity_sync.MixinServerGamePacketListenerImpl_Redirect` | PacketRedirection (:34, javadoc `@link`) | **S10** | held mixin (S7(b)) |
| `mixin.common.other_sync.IEServerConfigurationPacketListenerImpl` | ImmPtlNetworkConfig (:30, :164,:236) | **S10** | held mixin (S7(b); paper said :29, probe :30 — ±1 net import edits, symbol exact) |
| `mixin.client.sync.MixinMinecraft_RedirectedPacket` | PacketRedirectionClient (:16, javadoc `@link`) | **S12** | held CLIENT mixin (S7(b)) |

**Resolved in-probe (NOT errors — proof the slice is honest):** `PortalAPI` + `GlobalPortalStorage` (the
Slice-C co-ports — landed this stage, so `PacketRedirection`/`ImmPtlNetworking`'s refs to them produce no
error); `LimitedLogger` and other S2-held-but-present `q_misc_util.my_util` files (on disk, only excluded
in the shipping build); `Portal`/`PortalExtension`/`PortalManipulation` (S6), `IEClientWorld`/`O_O`/
`IPConfig`-family/`DimensionIntId` (S4), `CHelper`/`IPCGlobal`/`IPGlobal`/`McHelper`/`Helper`/`MiscHelper`/
`DQuaternion`/`IntBox` (S2/S5), `DimensionAPI` (DimLib F11 stub). The Slice-A mixins
(`MixinPlayerList_Misc`/`MixinMinecraftServer_Misc`/`IEClientPacketListener_Misc`) produce **no errors at
all** — their translations are fully in-vanilla and `MiscNetworking` (same package) resolves in-probe.

**(ii) The two category-(c) render files compile CLEAN.** `CustomTextOverlay` + `MixinGui_Overlay`
produce ONLY the `@Environment` loader debt above — **no** `GuiGraphicsExtractor` / `centeredText` /
`Font.split` / `Profiler` / `Matrix3x2fStack` / `LocalCapture` / `extractRenderState` errors. The §6
re-derivations are probe-verified to compile against 26.2; only their render CORRECTNESS is deferred to
S11/S12.

**(iii) Non-U5 errors are the S2/S4/S5/S6 carry-over baseline, unchanged** (IPConfig cloth/autoconfig,
the compat invoker bases, render ducks, etc.) — they resolve at their owning stages and are not S7's
concern.

**Verdict:** the U5 slice reduces to exactly the documented S7(b) forward-ref union + the loader-facade
debt + the two governed category-(c) amendments (R1 `dimIdToDimSeaLevel` field, GUI-render re-derivation),
with **zero translation slips**. Gate satisfied — probe = U5 union + carry-over only.

---

## 8. Handoffs for downstream stages

- **S8 (`ServerTeleportationManager`):** `ImmPtlNetworking`/`PortalAPI` forward-refs resolve when U6 lands.
- **S9 (`ChunkLoader`/`ImmPtlChunkTracking`):** `PortalAPI` forward-refs resolve when U7 lands.
- **S10 (R7 §A mixin, F7):** apply the §A netty-pass `scheduleIfPossible`+`ci.cancel()` correction (§2 code
  block) — do NOT port the stock IP inline-handle-on-netty mixin. Guard = `packetProcessor().isSameThread()`.
  Lands with the `IECustomPayloadPacket` duck.
- **S10 (R1 field, F8):** declare + null + consume `ClientWorldLoader.dimIdToDimSeaLevel` (§3 HANDOFF).
  Without the field the probe stays red on `dimIdToDimSeaLevel` from S10 onward.
- **S10 (loader-facade cutover):** puts `net.fabricmc.*` on common's compile classpath (S06 §5.4). Then
  `ImmPtlNetworkConfig`'s held config-networking + `@Environment`/`EnvType` across all held files resolve;
  the §1 GONE/CHANGED member translations make them compile then. The stale `PacketRedirectionClient`
  `@link …ensureRunningOnSameThread(…BlockableEventLoop)` (G1-GONE overload) is non-load-bearing — update
  to the `PacketProcessor` overload only if a doclint pass is ever enabled.
- **S11/S12 (render api-map):** verify the two GUI-render re-derivations (§6) — the
  `Gui.extractRenderState` injection point + FAILHARD local order + `shouldRenderLevel` gate + `centeredText`
  shadow/line-height. Runtime-only (unregistered until S13).
- **S12 (`MixinMinecraft_RedirectedPacket`):** the `wrapRunnable`/`scheduleExecutables` narrowing (§A step
  3); javadoc-`@link`-imported by `PacketRedirectionClient`.
- **S13 (mixin registration):** register `seamlessportals-ip-qmisc.mixins.json` (step 2) behind the
  `SeamlessMixinConfigPlugin` `entityPortals` gate, with the other three IP configs. It lists all 5
  q_misc_util mixins (`IELevelStorageAccess_Misc` from S4 + the 4 from this stage).
- **S13 relog check (R11):** grep the server log for the three signatures in §4 — a clean relog with
  pre-existing global portals must show NONE and must re-render the global portals in their dimensions.
- **Login order (DEPENDENCY_ORDER §4.2 / seam inventory A3):** `ImmPtlNetworkConfig`'s config handshake runs
  in the configuration phase (before play); the `DimIdSyncPacket` mid-`placeNewPlayer` slot + global-portal
  `onPlayerLoggedIn` order is owned by the Slice-A `MixinPlayerList_Misc` + the S10 `other_sync/MixinPlayerList`.
