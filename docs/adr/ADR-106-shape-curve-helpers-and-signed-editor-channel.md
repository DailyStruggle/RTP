# ADR-106 — Shape Curve Helpers, Curve-Space Run Layers, and the Signed Two-Way Editor Channel

**Status:** Accepted
**Date:** 2026-10-04
**Authors:** RTP Core Team
**Extends:** [ADR-104](ADR-104-ephemeral-web-editor-and-packed-docs-integration.md) (Ephemeral Web Editor), [ADR-001](ADR-001-archimedean-spiral-1d-mapping.md) (1D Spiral Mapping), [ADR-034](ADR-034-memory-shape-catalog.md) (Memory Shape Catalog), [ADR-052](ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md) (Cause-Tagged Bad Locations), [ADR-085](ADR-085-spiral-addressed-hilbert-key-space.md) (Spiral-Addressed Hilbert Key Space)
**Related REQs:** S-004, S-005, S-007, REQ-RTP-F-013
**Extended by:** [ADR-107](ADR-107-addon-editor-extensions-and-protocol-negotiation.md) (Addon Editor Extensions: type namespace, protocol negotiation, delivery classes, budget shares; see its section 8 for the changes to sections 5.1-5.5 and 8)

---

## 1. Context and Problem Statement

The main editor target is the hosted page at `https://dailystruggle.github.io/RTP/editor/` (ADR-104 section 4.2). `/rtp editor` uploads one gzip snapshot to bytebin (`EditorCmd` -> `EditorSessionManager.createPayloadJson()` -> `EditorHttpTransport.postPayload`), and the static page reads it once. The hosted page has no live feed, no path tiles and no loopback channel. It gets only a 2,048-point sketch of each region's walk path and a 2D hazard grid taken at export time. The full-resolution path (`WalkPathTiles`) and live hazards (`HazardTiles`) exist only for local exports (ADR-104 section 4.6).

The page can't ship its own model of the curves because **shapes are registered at runtime**:
- `RTP.addShape(Shape)` registers the built-ins. `CIRCLE` and `SQUARE` are `CircleOptimizedDualLayer` / `SquareOptimizedDualLayer` instances registered under a second name.
- Add-on jars register their own shapes.
- `ChunkyRTPShape` instances are created on the fly from Chunky borders.

A page-side copy of the built-in curves drifts from the Java code, and it can never cover add-ons. A build-time compile of this repository's shapes would miss the same shapes.

Two-way features (Hot-Apply, walk-path previews of edited geometry, focus-driven land detail) exist only over the local loopback socket (`EditorLoopbackChannel`). Its messages are unsigned and token-gated, and each handler parses its own JSON.

---

## 2. Decision Drivers

- **Hosted-first.** The hosted page shall draw the complete walk path and the hazards at any zoom from the snapshot alone, with no further requests.
- **Open shape set.** Every registered shape, including add-on and runtime-created shapes, can take part. No page change is needed for a new shape.
- **Settings are editable.** When the operator edits a setting, the page redraws the path without a server round-trip.
- **Fail closed.** The page shall never draw a wrong curve. Any doubt falls back to the sketch (hosted) or the path tiles (local), and the badge says why.
- **No page-origin code execution.** Code carried in a payload is untrusted. A crafted bytebin upload shall not run script on the `dailystruggle.github.io` origin (extends ADR-104 section 4.5).
- **Zero inbound attack surface.** The hosted channel is outbound only. The only listener remains the loopback channel (ADR-104 section 2).
- **One protocol.** Hosted, local and test runs share all protocol code except the socket.
- **S-004 / S-005.** Relay failures are logged and shown, never silent. Payload building, signing and socket work stay off the main thread and are scheduled through `RTP.scheduler`.
- **Curve stability.** The shapes' Java curve code is not changed, so curve positions and existing `.bin` data stay valid.

---

## 3. Considered Options

### Curve on the page
- **Option A: Page-side models of the built-in shapes** (the client models previously in `docs/editor/index.html`). *Rejected:* they drift from Java without a check, and add-on and Chunky shapes are never covered.
- **Option B: Build-time translation of this repository's shapes to JS or WASM.** *Deferred:* it misses shapes registered at runtime. It is kept as a later generator for the contract below (section 8).
- **Option C: Stream full-resolution path tiles over a relay.** *Rejected:* a large region needs thousands of tiles (up to 4,096 per region), well past the relay frame and rate caps (section 5.5). Tiles stay as the local fallback.
- **Option D: Each shape supplies its own curve helper as JavaScript (`toJavaScript()`), run in a sandbox, checked by a hash.** *Accepted.*

### Two-way channel for hosted sessions
- **Option E: Embedded HTTP / WebSocket listener.** *Rejected:* ADR-104 Option 1 (port forwarding, inbound attack surface).
- **Option F: Polling bytebin for new uploads.** *Rejected:* one upload per update, high latency, and it can't carry page-to-plugin messages without a listener.
- **Option G: LuckPerms-style signed messages over a public WebSocket relay (bytesocks), joined outbound by the plugin.** *Accepted.* LuckPerms has used this design in production with the same trust model.

---

## 4. Shape Curve Helpers

### 4.1 Shape SPI
- `MemoryShape.toJavaScript()` shall return the curve helper source for the shape, or `null` when there is none.
  - The default implementation loads the resource `editor-curve/<SimpleClassName>.js` through the shape class's own classloader (`getClass()`). If that is missing, it walks up the superclasses, so a subclass inherits its parent's helper. The lookup stops at `MemoryShape`.
  - The result (including "none") is cached per class.
  - Sources over **32 KiB** (UTF-8) are refused: the method returns `null` and logs a warning once per class.
  - A shape may override the method, for example to build its helper in code.
- `MemoryShape.curveState()` shall return the runtime values the curve depends on that are not settings, as a `Map<String, Object>` of numbers. The default is an empty map.
  - `AbstractDualLayerShape` and `CircleOptimizedDualLayer` return `{p: getPointEdgeChunks(), rEff: getEffectiveRadius()}`. `getEffectiveRadius()` grows with `expand`, and the point edge cache only ratchets up, so neither can be derived from settings alone.
  - Other shapes override it only when their curve reads such state.
- **Add-on convention:** an add-on shape gets a hosted curve by shipping one resource, `editor-curve/<SimpleClassName>.js`, in its own jar, or by overriding `toJavaScript()`. It needs no other code. A shape without a helper keeps the sketch (hosted) or the tiles (local).
- Built-in helpers ship in `rtp-core/src/main/resources/editor-curve/`:

| Resource | Shapes (registered names) |
|----------|---------------------------|
| `CircleOptimizedDualLayer.js` | `CIRCLE` and the dual-layer circle's own name |
| `SquareOptimizedDualLayer.js` | `SQUARE` and the dual-layer square's own name |
| `Circle.js`, `Square.js` | legacy spirals |
| `Rectangle.js`, `Ellipse.js`, `Polygon.js` | box, ellipse, polygon |
| `Circle_Normal.js`, `Square_Normal.js` | Normal-distribution variants |

  Every helper is self-contained: shared parts (the Hilbert walk and the orientation table of ADR-085) are inlined in each dual-layer helper rather than imported.

### 4.2 Helper contract
A helper is **one JavaScript expression** that evaluates to an object with three pure functions:

```js
({
  range(params, state),             // number of curve positions (>= 0)
  locationToXZ(loc, params, state), // [x, z] in chunks, or null when loc is off the curve
  xzToLocation(x, z, params, state) // curve position, or -1 when (x, z) is not on the curve
})
```

- **`params` are inputs, not constants.** The object is keyed by the shape's setting names (`radius`, `centerRadius`, `centerX`, `centerZ`, `vertices`, ...). The plugin normalises the values with its own parsers before sending: distances in chunks, numbers as numbers, booleans as booleans, enums as their names. A helper shall not hard-code a setting's value.
- **`state` is optional.** It holds the `curveState()` values. A helper shall work with `state` missing or partial, using its own rule for the missing values. The rule shall match a freshly built shape with the same settings: for the dual-layer shapes, `rEff = radius` and `p` derived from the radius as the Java constructor derives it. Output drawn this way is labelled "estimated" on the page.
- **Numbers** are IEEE doubles, exact below 2^53. Helpers use `Math.floor`-based floor division to match Java's `Math.floorDiv` for negative coordinates. Positions above 2^53, or a 1-ulp difference between `Math.sin` / `Math.atan2` and Java, show up as a hash mismatch (section 4.4) and the page falls back.
- **Purity.** Helpers keep no state between calls, use no randomness, clock or globals beyond `Math`, and return only numbers, `null` or arrays of numbers.
- **Parity.** For built-in shapes, a test-only GraalJS dependency in `rtp-core` runs every helper against the Java `locationToXZ` / `xzToLocation` / `getRange` across radii, centre radii, centres (negative included), P sizes and `expand` on and off, with and without `state`. GraalJS is never shipped.

### 4.3 Typed settings in the schema
`EditorSessionManager.buildSchemaJson` / `describeParam` extends ADR-104's `{type, default, options}` per setting with values taken from the shape's `getParameters()` (`Map<String, CommandParameter>`):
- `kind`: `distance` (`DistanceParameter`), `integer` (`IntegerParameter`), `number` (`FloatParameter`), `boolean` (`BooleanParameter`) or `enum` (`EnumParameter`);
- `description`: the parameter's description;
- `suggestions`: the parameter's suggested values (for example `radius`: 64-1024);
- plus `curveParams` per shape: the setting names the helper reads.

After the shape dropdown, the page builds one input per declared setting:
- distance values accept the unit suffixes (`b` / `c` / `r` / `km` / `mi`);
- integers and numbers get number inputs with the suggestions as presets;
- booleans get a checkbox, and enums a select.

Settings the shape doesn't declare are not shown. Add-on shapes get a form the same way. An edit to a setting in `curveParams` redraws the path from the helper immediately. When the channel is connected, a `curve-state` request (section 5.3) replaces the estimated state with the exact one.

### 4.4 Snapshot fields and the curve hash
- **`curveCode: {SHAPE: {sha256, js, sample?}}`** at the payload top level, one entry per registered shape name with a helper, whether or not a region uses it, keyed by that name. `sha256` is the lowercase hex SHA-256 of the UTF-8 `js`. Each source is sent once per snapshot, however many regions use it.
  - `sample` is the `curve` block (below) of a clone of the registered shape at its defaults. For a shape no region uses (a shape planned on the page), the page checks its helper against `sample` instead of a region's `curve`.
- **Per region, `curve: {shape, params, state, hash}`**, about 100-200 bytes:
  - `shape`: the registered name, the key into `curveCode`;
  - `params`: the normalised settings in `curveParams`;
  - `state`: `curveState()`;
  - `hash`: `CurveHash.of(shape)`.
- **`CurveHash.of(MemoryShape)`**, with `R = getRange()`:
  - The sample positions are `s_i = floor(i * R / 256)` for `i = 0..255`, computed in exact integer arithmetic (Java `long` / `BigInteger`, page `BigInt`). For `R <= 256` they are every position `0..R-1`. For `R = 0` there are none.
  - The hash is 32-bit FNV-1a over the little-endian bytes of `R` as a 64-bit integer, followed by each sample's `x` and `z` from `locationToXZ(s_i)` as 32-bit signed integers. An off-curve result (`null`) is hashed as the pair `(0x80000000, 0x80000000)`.
  - It is sent as 8 lowercase hex digits.
  - The page computes the same hash from the helper with the region's `params` and `state`. Only a match makes the region "verified".
  - Separately, the page checks `xzToLocation(locationToXZ(s_i)) == s_i` on the same samples. A failure disables only features that need `xzToLocation` (pointer readout, zoomed-in chunk scan).
- Regions whose shape has a helper drop the 2,048-point sketch (about 30 KB of JSON per region) and the 2D hazard RLE. Regions without a helper, and regions whose snapshot-time self-check fails, keep both.

### 4.5 Sandboxed runner
Helper code shall never run with page privileges, and shall never be `eval`'d on the page origin.
- **`CurveSandbox`** (page code) creates one `<iframe sandbox="allow-scripts">`. It has no `allow-same-origin`, so its origin is opaque. Its `srcdoc` is a fixed runner written by the page, never taken from the payload, with the CSP `default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' blob:; worker-src blob:`.
  - The opaque origin blocks `parent.document`, cookies, `localStorage`, `indexedDB` and the page's WebCrypto keys.
  - `default-src 'none'` blocks `fetch`, XHR, WebSocket, images and nested frames, so helper code has no network.
  - No `allow-popups`, `allow-forms`, `allow-modals` or `allow-top-navigation`.
- Where a blob Worker can be created inside the opaque origin, the runner evaluates helpers inside the Worker, so a runaway helper can be terminated without blocking the iframe. Where it can't, the runner evaluates in the iframe with smaller batches, and a timeout removes the whole iframe. Per-browser support is confirmed by the headless probe.
- **Loading:** before sending a helper, the page checks its SHA-256 against `curveCode[SHAPE].sha256` with `crypto.subtle.digest`. It sends `{op: "load", shape, js}`, and the runner evaluates the expression once and checks that it has the three functions.
- **Calls:** `{op: "toXZ" | "toLoc" | "range", id, shape, params, state, data: Float64Array}`, with up to 16,384 positions per message (transferred, not copied). The reply is `{id, data: Float64Array}`, with `NaN` for `null` / `-1`.
- **Page-side checks:** the page accepts a message only from the iframe's `contentWindow`. It accepts only the expected `id`, a `Float64Array` of the expected length, and integral or `NaN` values. Runner strings are never inserted into the DOM: failures map to a fixed set of reason codes.
- **Timeouts:** 500 ms per load and 200 ms per batch. On timeout the Worker is terminated, or the iframe removed and recreated, and the shape is marked failed for the session.
- Hosted and local pages use the same runner, so `file://` exports work offline: the helpers travel in the embedded payload.

### 4.6 Drawing and fallbacks
- **Path:** each frame has a time budget (8 ms for the path layer).
  - Zoomed out, the page samples curve positions evenly over `[0, range)` through `locationToXZ`, with a stride chosen so the batch fits the budget.
  - Zoomed in, once the visible chunk count fits the budget, it evaluates `xzToLocation` for every visible chunk and joins each on-curve chunk to the chunk of the next position, as `WalkPathTiles` does (ADR-104 section 4.6 item 7). The path is then exact at any zoom.
- **Fallback order:** helper (verified) -> path tiles (local feed) -> 2,048-point sketch -> no path. The status badge names the shape and the reason:

| Reason | Trigger |
|--------|---------|
| `no-helper` | no `curveCode` entry for the shape |
| `sha256` | source digest differs from `sha256` |
| `load-error` | evaluation failed or the object lacks the three functions |
| `throw` | a call threw |
| `timeout` | load or batch exceeded its timeout |
| `malformed` | output of the wrong type, length or value |
| `hash-mismatch` | page hash differs from `curve.hash` |
| `sandbox-unavailable` | the iframe could not be created |

  A failure never produces a page script error. Drawing from the "estimated" state (state missing, no channel) is labelled as such.

### 4.7 Curve-space run layers and hazard runs
A **run layer** is per-region data in curve-position space, `{v, runs}`. `runs` is the base64 of an unsigned LEB128 varint stream of `[deltaStart, len, value]` triples, where `deltaStart` is the gap since the previous run's end (the first run is relative to position 0). The page maps runs to chunks through the helper's `locationToXZ`, so a layer lines up with the path by construction.
- **Hazards** are the only layer built in this phase: `hazardRuns: {v: 1, runs}`.
  - They are built from the region's `badLocationsSnapshot` (the authoritative keys and prefix sums).
  - `value` is the `LocationGenerator.FailTypes` ordinal + 1, the same convention as `HazardTiles`.
  - About 2-4 bytes per run.
- `uniquePlacement` runs are filtered out. They are spacing marks that retire used ground (ADR-052), not hazards, and the page doesn't show them.
- **Drawing:** zoomed in, each run's chunks are painted by cause. Zoomed out, each screen cell is painted by coverage (the share of its sampled positions inside runs) under the same frame budget as the path.
- **Cap:** at most 256 KiB of encoded runs per region in a snapshot. A region over the cap keeps the 2D RLE layer instead, with the badge reason `hazard-cap`.
- Regions drawn from code skip `WalkPathTiles` and `HazardTiles` in the local feed once the page reports them verified (`focus`, section 5.3). Locally, a hazard change shows up within one feed cycle.

---

## 5. Signed Two-Way Editor Channel

### 5.1 Keys and envelope
- **Plugin key:** one RSA-2048 key pair per server (`EditorKeys`), persisted in `<dataFolder>/editor/keys/` (PKCS#8 private key, X.509 SPKI public key, owner-only file permissions where the platform supports them). A corrupt key file is logged and replaced with a new pair (S-004). The browser then sees a new `pluginKey` in the next snapshot.
- **Browser key:** WebCrypto `RSASSA-PKCS1-v1_5`, 2048-bit, SHA-256. The private key is non-extractable and kept in IndexedDB, so the same browser stays trusted across sessions. Where IndexedDB is unavailable (some `file://` contexts), the key lives only for the tab and must be trusted again.
- **Fingerprint:** lowercase hex SHA-256 of the SPKI DER.
- **Envelope** (the LuckPerms format): every frame is `{"msg": "<JSON string>", "signature": "<base64>"}`. The signature is SHA256withRSA (RSASSA-PKCS1-v1_5) over the UTF-8 bytes of `msg`. Java `Signature("SHA256withRSA")` and WebCrypto produce and verify the same bytes with no format conversion.
- **Inner message:** `{type, channel, seq, from, challenge?, to?, ...}`.
  - `channel` is the channel id, so a message can't be replayed into another session.
  - `from` is the sender's fingerprint.
  - `seq` increases strictly per sender, within a challenge (page) or over the channel lifetime (plugin).
  - `to` addresses a reply to one browser key when several tabs share the relay.
  - `channel`, `seq`, `from` and `to` are reserved: a message body shall not use them for its own fields, and `EditorChannel.send` refuses a body that does.

### 5.2 Handshake and trust
1. The snapshot carries `channel: {relay, id, pluginKey}`. The page accepts only messages signed by that `pluginKey` and drops anything else without acting on it.
2. Page -> `hello {publicKey}` (base64 SPKI). The plugin answers `hello-reply {to, state: "trusted" | "untrusted", challenge}`. `challenge` is 128 random bits, new for every `hello`. Every later page message shall carry it with `seq` starting at 1. A replayed old `hello` therefore earns a challenge the replayer can't sign for.
3. **Untrusted key:**
   - The plugin creates a nonce (8 characters, single-use, bound to the session and that key, expires after 5 minutes).
   - It sends **one** in-game prompt per key per session to the operator who created the session (the console when the console created it), with a clickable `/rtp editor trust <nonce>`.
   - The page shows "awaiting trust" and the same nonce, so the operator can match them.
4. `/rtp editor trust <nonce>` (`TrustCmd`, permission `rtp.editor`) adds the fingerprint to `TrustedEditors`. That is persisted as `<dataFolder>/editor/trusted-editors.json` with an atomic replace. The plugin then sends `hello-reply {state: "trusted"}`.
   - An expired or unknown nonce is refused with a configurable message.
   - A corrupt trusted-editors file is logged and treated as empty, and it is replaced on the next successful trust write.
5. **Acceptance rules (plugin side):** from untrusted keys the plugin accepts only `hello` and `ping`. Any other message needs a trusted key, a valid signature, the current challenge and a higher `seq`. Forged, replayed, out-of-order, unknown-type and oversized messages are dropped and logged, rate-limited to one line per key per minute.
6. The trust prompt, trusted / rejected replies, and channel opened / lost / expired notices are configurable in `messages.yml`, with locale parity (REQ-RTP-F-013, S-007).

### 5.3 Message types

| Type | Direction | Content |
|------|-----------|---------|
| `hello` / `hello-reply` | page -> plugin / plugin -> page | handshake (section 5.2) |
| `ping` / `pong` | both | keepalive: the page checks every 15 s and, on a paced relay, pings only after 40 s without sending (any verified plugin frame counts as a pong); 3 missed pongs mark the link lost. The relay's proxy drops a socket silent for about a minute, so `BytesocksTransport` also sends a WebSocket ping control frame every 20 s, which bytesocks does not count as a message |
| `feed` | plugin -> page | local feed head push (ADR-104 section 4.6 item 6), unchanged in meaning; pushed only when it changes, after a `focus`, or as a 40 s heartbeat (section 5.5), which keeps the page's socket under the proxy's idle limit. A hosted page applies its telemetry, survey progress and region stats; its tile versions name local files |
| `curve` | plugin -> page | `{region, curve: {shape, params, state, hash}}` when a region's settings or state change (for example `expand` grows `rEff`); the page re-verifies and redraws |
| `curve-state` | page -> plugin, reply plugin -> page | request `{reqId, region, shape: {name, key: YAML scalar}}` for edited settings; reply `{reqId, state, hash, range}` or `{reqId, error}`; evaluated on a temporary shape built as `EditorWalkPathPreview` builds one, under its build limits (4 per feed tick, newest per client and region, 1,024 vertices, scalar values only) and the section 5.5 frame cap |
| `hazard-delta` | plugin -> page | `{region, base, version, add: runs, remove: runs}` against hazard version `base`; `{region, version, reset: true, runs}` when the page's version is unknown or stale |
| `land` | plugin -> page | `{world, y, palette, bins}`: focus-driven land bins `[rx, rz, level, base64Runs]` (`BiomeBinCodec`, ADR-104 section 4.6 item 1); every frame carries the world's whole palette, so a page that connects late or misses a frame still names every cell. A `focus` repeating the current view pushes its bins again unless they are still queued. Bins go out in walk order: those inside a tracked region by the earliest curve position of five probe chunks (centre outward along the region's curve), the rest after them by Chebyshev ring around the view centre |
| `land-ref` | plugin -> page | sessions with a bytebin hand-off (hosted): `{world, y, bins, bytebinKey, sha256}`; the bytebin body is a `land` message `{world, y, palette, bins}`. The plugin uploads the bins of the page's view plus 2 bins of padding whose version the page does not hold (the snapshot's embed, then earlier uploads), in walk order, at most one upload per 30 s and none while nothing in view changed. A failed upload is retried with the next period; a trusted hello or a `focus` with `landReset: true` (the page could not fetch or verify a `land-ref`) drops everything but the snapshot from what the page holds, so it is sent again. A hosted session sends no `land` frames |
| `focus` | page -> plugin | debounced view rectangle `{world, y, minRx, minRz, maxRx, maxRz}`, plus `verified: [regions]`, `hazardVersions: {region: v}` and, after a lost `land-ref`, `landReset: true` |
| `walkpath` | page -> plugin, reply plugin -> page | walk-path preview of edited geometry (ADR-104 section 4.6 item 6), for shapes without a verified helper; a reply over the frame cap is handed off through bytebin (section 5.5) |
| `apply` / `apply_ack` | page -> plugin / plugin -> page | Hot-Apply: `{files}` inline (every staged file that differs from the server copy: worlds, regions, config), or `{bytebinKey, sha256}` above the frame cap; runs the full `/rtp editor apply` validation pipeline; acknowledged with the result, after which the page treats the sent text as the server copy |
| `bundle` | plugin -> page | paced transports only (section 5.5): `{items: [..]}` of held `feed`, `curve`, `land`, `land-ref` and `hazard-delta` pushes, signed once; the page dispatches each item to its own handler and ignores any other item type |

The handlers (`EditorWalkPathPreview`, `EditorLoopbackApply`, the feed's focus queue) register with `EditorChannel` by `type` instead of parsing loopback frames themselves.

### 5.4 Transports
`EditorChannel` owns the envelope, signing and verification, trust, the challenge and `seq` checks, routing by `type` and the outbound caps. `ChannelTransport` is only `send(String)`, an inbound callback and a state / close callback. There are three implementations:
- **`BytesocksTransport` (hosted):**
  - It creates a channel with `GET <relay>/create` (the bytesocks API used by the LuckPerms and spark clients: the relay answers `201` with the channel id in the `Location` header; `POST` gets `405`) and reads the id from that header.
  - The relay is `editor.relayUrl` in `advanced/network.yml`, next to `editor.bytebinUrl`, read off the main thread for every session. Blank means the default, and an invalid value is logged and replaced by the default. The interim default is LuckPerms' public instance `https://usersockets.luckperms.net`, because `bytesocks.lucko.me` does not resolve.
  - It then joins `wss://<relay host>/<id>` outbound through the JDK `java.net.http.WebSocket` on the existing `HttpClient`. There is no new dependency and no own threads. Timers and retries run through `RTP.scheduler`.
  - It reconnects with backoff (1 s, doubling, at most 60 s) and expires 30 minutes after the session starts, the same lifetime as the local feed.
- **`LoopbackTransport` (local):** wraps `EditorLoopbackChannel`. The loopback handshake rules (loopback peer and Host, Origin check, token, at most 4 clients) stay as defence in depth. The local export's `channel.relay` is the loopback `ws://127.0.0.1:<port>/rtp-editor-ws?token=...` address, so the page runs the identical handshake and messages.
- **`InMemoryTransport` (tests):** a connected pair, so JUnit drives the full protocol, including a page stub, without sockets.

The page has one `EditorChannelClient` (keys, signing, verification, handshake, reconnect) that connects to `snapshot.channel.relay`. The existing `focus` / `walkpath` / `apply` callers move onto it.

### 5.5 Caps and the bytebin hand-off
- Frames are capped at **32 KiB** (the envelope included) on every transport, so local runs test the hosted limits. Larger inbound frames are refused and logged.
- Outbound traffic is capped at **2 MiB per session per minute**. Over the cap, `land` batches are deferred first, then `hazard-delta`. `curve` and replies are never dropped, only delayed. Deferral is logged once per minute.
- Data larger than a frame (a large apply, a hazard reset over the cap) goes through bytebin and is referenced as `{bytebinKey, sha256}`, the LuckPerms pattern. The receiver fetches it, checks the digest and then processes it as if it had been sent inline.
- Frame pacing: bytesocks counts frames per IP (30 per 2 minutes by default, all channels together), closes a socket over the limit with 1008 and deletes a channel once its last client has left. A transport may therefore declare a frame share per 2-minute window (`ChannelTransport.framesPerWindow`). The bytesocks transport declares 18 and the page keeps to 10 on any `wss://` relay, since page and server often share one IP; loopback and in-memory transports are unpaced.
  - On a paced channel, replies go first and wait in order when the share is used up. Broadcast pushes are held (newest `feed`, newest `curve` per region, `land` and `hazard-delta` in order) and leave as one `bundle` at most every 8 s, leaving 4 frames of the share for replies. A new push is refused while a frame's worth is already held, so its producer resends later. `bye` is never held.
  - The page coalesces queued `focus` messages, counts every outbound frame (hello and ping included), and treats any verified plugin frame as proof of liveness. The plugin skips a `pong` within 10 s of other outbound traffic.
  - On every channel the feed pushes a head only when the page would see something new: `seq` and `timestamp` alone do not count, and the telemetry gauges count only beyond their jitter (TPS 0.5, MSPT 1 ms or 20 %, tick budget 5 %, heap 0.25 GiB, measured against the last pushed value). A `focus` (sent first by a page that has just connected) forces the next head, and an unchanged head is still pushed every 60 s as a heartbeat. An idle session therefore costs about 2 plugin frames per 2 minutes instead of a bundle every 8 s; the local `feed.js` file is still rewritten every tick.
  - The page marks a connected link `feed stale` when no head has arrived for 90 s (heartbeat plus a bundle gap and margin).

### 5.6 Relay failure behaviour (S-004)
- A failed channel create or connect, a disconnect, a reconnect, the expiry and plugin disable are each logged with their reason. The operator gets a configurable in-game notice for "opened", "lost" and "expired".
- If the relay can't be created, `/rtp editor` still uploads the snapshot without a `channel` block (snapshot-only session) and logs the reason.
- A rejoin refused with HTTP 400 or 404 means the relay has deleted the channel. The transport then closes for good, with a reason telling the operator to run `/rtp editor` again, instead of retrying until expiry.
- Opening never blocks a thread (S-005, `RTPArchitectureTest` rule 2, no exclusion): `ChannelTransport.start` and `EditorChannel.start` return futures, and the hosted create-then-join is one chain bounded by its timeouts that completes with no channel on any failure. `/rtp editor` composes it with the payload build and upload.
- The page's status badge shows one of: `connecting`, `awaiting trust`, `connected` (or `feed stale (<age>)`, section 5.5), `reconnecting`, `snapshot only (<reason>)`. The page keeps working from the snapshot in every state. Without the channel, drawing stays exact for verified regions, and edited state is labelled "estimated".
- Expiry and plugin disable close the transport. The page sees the close and switches to `snapshot only (expired)` / `snapshot only (closed)`.

### 5.7 Security notes
- Helper code runs only in the sandbox (section 4.5). Channel messages are passive JSON, never code.
- The relay sees message contents: they are signed but not encrypted, the same as LuckPerms. No secrets go over the channel: no loopback tokens and no keys. Hot-Apply carries only YAML.
- A stolen channel id gives read access to live map data. It is as sensitive as the bytebin key, which is already unlisted. Writes need a trusted, signed browser key.
- The plugin only connects outbound, so the zero-inbound rule of ADR-104 section 2 still holds.

### 5.8 Amendment (2026-10-05): Snapshot redaction, apply limits, trust lifetime
- **Redaction:** secret-bearing config values (block and inline/flow style) are replaced by a `<redacted>` sentinel in every snapshot, hosted or local. On apply, a value still equal to the sentinel keeps the on-disk value, so a round trip never writes the placeholder.
- **Apply limits:** only `.yml` files inside the plugin folder, never under `editor/`, resolved through symlinks before containment is checked. A bytebin hand-off requires its `sha256`; outbound URLs are https / wss except loopback; gzip bodies are capped at 4 MiB.
- **Trust lifetime:** `/rtp editor untrust key=<fingerprint prefix|all>` revokes trusted browser keys. `editor.trust.maxAgeDays` in `advanced/network.yml` (default `0`, no expiry) expires old trust entries. The trust prompt shows the full fingerprint, and wrong trust codes are limited to 5 per minute.
- **Sessions:** sessions expire after 30 minutes, at most 32 are open at once, and tokens are logged by prefix only. The key file and local exports are owner-only, Windows included. Each local export closes the previous loopback socket and opens a new one with a fresh token.
- **Hosted page:** `no-referrer` and a CSP meta tag; `?token=` is removed from the address bar after load; fetched docs HTML is sanitised, and links are limited to an allow-list.

---

## 6. Snapshot Size

| Item | Size |
|------|------|
| Built-in helper | a few KB each, once per shape type per snapshot (cap 32 KiB) |
| `curve` per region | ~100-200 bytes |
| `hazardRuns` | ~2-4 bytes per run (cap 256 KiB per region) |
| Removed for verified regions | 2,048-point sketch (~30 KB JSON) and the 2D hazard RLE |

`editor-data.json` (shipped configs, field docs, synonyms) is still uploaded in every snapshot. Loading it from the site instead is the next traffic saving (section 8).

---

## 7. Consequences

### Positive
- The hosted page draws the complete walk path and hazards at any zoom from the snapshot alone, for every shape that ships a helper, add-ons included.
- Setting edits redraw the path locally with no server request. The typed settings form works for any registered shape.
- Hosted sessions become two-way: live curve changes, hazard deltas, focus-driven land detail, walk-path previews and Hot-Apply, with in-game trust.
- Local, hosted and test runs share one protocol, so a local session and the in-memory tests exercise everything hosted mode does except the relay hop.
- Snapshots shrink for regions drawn from code.

### Negative / Trade-offs
- Helpers are written by hand per shape and can drift from Java. This is guarded by the parity test (built-ins) and the runtime hash (every shape), with a visible fallback.
- Some browsers may not allow a Worker inside an opaque-origin iframe. The iframe-only runner then uses smaller batches, and a timeout costs recreating the iframe.
- Hosted two-way features depend on a third-party relay, as uploads already depend on bytebin. The default, `usersockets.luckperms.net`, is run for LuckPerms and its use by other clients is not documented, so it may be rate-limited or blocked. The URL is configurable (`advanced/network.yml editor.relayUrl`, for example a self-hosted bytesocks), and the snapshot-only session is always available.
- Each new browser needs one in-game trust action, and per-tab keys (no IndexedDB) need it every time.
- A test-only GraalJS dependency is added to `rtp-core`'s test classpath.

---

## 8. Later Phases (not decided here)
- **Generated helpers:** translating a shape's bytecode to JS or WASM to fill in `toJavaScript()` automatically. The contract in section 4.2 is fixed so a generator can produce it without page changes.
- A helper for `ChunkyRTPShape` and other add-on shapes that ship none (they keep the sketch or tiles).
- **More run layers:** landing-heat and learned-biome runs in curve space, using the section 4.7 format. Landing heat stays on the local feed files until then.
- **Viewport-interval queries:** the page asks for runs only within the curve intervals in view.
- **Binary frames and deflate** on the channel instead of base64 JSON.
- **A self-hosted RTP relay** (a bytesocks instance) as the default, replacing the interim LuckPerms relay. The relay URL is configurable now, but no relay is run.
- Loading `editor-data.json` from the site next to the hosted page instead of uploading it in every snapshot.
- Removing local `feed.js` polling. It stays as the one-way fallback for a page copied off the server machine.

---

## 9. Changes to ADR-104
- Section 4.2 item 3: Hot-Apply and back-propagation for hosted sessions run over the relay channel of section 5. `/rtp editor apply <token>` stays the fallback.
- Section 4.3: the page holds no built-in curve model. Curve code comes only from the payload, per shape, and runs in the sandbox of section 4.5. For verified regions the snapshot carries neither the walk-path seed nor the 2D hazard RLE.
- Section 4.4: the local export carries the same snapshot fields and `channel` block as an upload, and its helpers travel in the embedded payload.
- Section 4.6: the loopback channel carries the signed protocol of section 5 through `LoopbackTransport`. The feed skips path and hazard tiles for regions the page reports verified.
- Section 5 (Consequences): hosted sessions get live updates. This replaces the trade-off that live updates exist only for local exports.

---

## 10. References
- `MemoryShape`, `AbstractDualLayerShape`, `CircleOptimizedDualLayer`, `SquareOptimizedDualLayer` (`rtp-core/.../selection/region/selectors/memory/shapes/`).
- `EditorSessionManager`, `EditorHttpTransport`, `EditorCmd`, `EditorLiveFeed`, `EditorLoopbackChannel`, `EditorWalkPathPreview`, `EditorLoopbackApply`, `WalkPathTiles`, `HazardTiles`, `BiomeBinCodec` (`rtp-core/.../commands/editor/`).
- `docs/editor/index.html` (hosted and packaged editor page).
- LuckPerms web editor socket protocol (bytesocks relay, `{msg, signature}` envelope, in-game trust).
