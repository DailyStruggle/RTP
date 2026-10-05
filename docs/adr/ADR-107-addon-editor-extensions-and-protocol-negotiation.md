# ADR-107 - Addon Editor Extensions: Shared Data, Addon Tabs and Protocol Negotiation

**Status:** Accepted (2026-10-05)
**Date:** 2026-10-05
**Authors:** RTP Core Team
**Extends:** [ADR-106](ADR-106-shape-curve-helpers-and-signed-editor-channel.md) (Signed Two-Way Editor Channel), [ADR-104](ADR-104-ephemeral-web-editor-and-packed-docs-integration.md) (Ephemeral Web Editor), [ADR-057](ADR-057-platform-agnostic-addon-spi.md) (Platform-Agnostic Addon SPI), [ADR-051](ADR-051-two-tier-api-extension-model.md) (Two-Tier API Extension Model)
**Related REQs:** S-004, S-005, S-006, S-007, REQ-RTP-F-013

---

## 1. Context and Problem Statement

The web editor (`docs/editor/index.html`, hosted at `https://dailystruggle.github.io/RTP/editor/` and packaged into local exports) shows four hardcoded tabs: Visual Region Editor, Diagnostics & Radar, Config & Prefabs and Shipped Docs. Addons have no way to put their own data or their own tab in it. `LeafRTPActionAddon` is the first addon that needs one: its actions and physical trigger zones are configured in addon YAML and are best edited next to the region map.

The in-game GUI addon already solves the same problem for menus. It never reads network heartbeats. It reads typed `rtp-api` objects (`RtpTarget`, `RtpTargetStatus` through `RTPAPI`), and core fills them from the network snapshot on its own schedule. The editor needs the same split: core owns the schedule and the transport, addons supply data and handle their own messages.

The editor contract is harder to change later than ordinary API, for two reasons:
- **Version skew.** The hosted page is deployed separately from the plugin, and local exports embed a copy of the page from the plugin's build. A page and a plugin of different versions meet in every session.
- **Frozen API.** Once third-party addons compile against an editor API, its shape is frozen (ADR-051 contract tier).

Five parts of the ADR-106 channel would break addons if extended after addons ship:
1. **Type names.** `EditorChannel.TYPE` is `[a-z][a-z0-9_\-]{0,31}` with no namespace, so an addon type and a later core type can collide.
2. **Bundled pushes.** On paced transports, only the fixed set `BUNDLED = {feed, curve, land, hazard-delta}` is bundled (`DROPPABLE` likewise), and the page's `CHANNEL_BUNDLED` drops every other bundle item as `bad bundle item`. ADR-106 section 5.3 says the page ignores other items; the page instead drops and logs them.
3. **No feature negotiation.** The payload carries `"version": 1`, which names the payload format only. Nothing tells the page which protocol features the plugin supports.
4. **Where addon UI code runs.** The page holds the trusted, non-extractable signing key, and `EditorChannelClient.send('apply', ...)` writes config. Any addon script in the page's origin could Hot-Apply anything.
5. **Write paths.** Hot-Apply (`apply`) is the one audited write path with visible failures. The `files` map is built from core's config parsers only (`EditorSessionManager.collectCurrentConfigs`), so addon YAML can't use it.

Handing addons the `EditorChannel` itself is not an option. It is a `final` class in `rtp-core` (`commands.editor.channel`). Its wiring (`EditorChannelWiring.register`) is package-private and registers four handlers (`focus`, `walkpath`, `curve-state`, `apply`). Publishing it would freeze the transport, the signing and the caps, and ADR-106 section 8 still plans binary frames and a self-hosted relay.

---

## 2. Decision Drivers

- **Contract now, UI later.** Every part listed in section 1 shall be fixed in 3.3.0. Rendering richer addon UI may follow as additive changes.
- **Core owns the transport.** Addons never sign, never see keys or channel ids, and never bypass the frame cap, the outbound budget or the pacing of ADR-106 section 5.5.
- **No addon code in the page origin.** A crafted descriptor or a hostile addon shall not run script on the page origin or reach the signing key (extends ADR-106 driver "No page-origin code execution").
- **One write path.** Addon config changes go through Hot-Apply with its validation, `.bak` copies, atomic writes and visible `apply_ack` errors (S-004).
- **Forward and backward compatible.** An older page shall ignore what it doesn't know. A newer page shall detect what an older plugin lacks without guessing.
- **Isolation.** An addon exception, slow call or oversized output shall not break the core feed, other extensions, or the session (DESIGN.md *Exception Isolation*).
- **Off the main thread (S-005).** No extension callback runs on a main or region thread.
- **Fail closed (S-006).** The registry throws `IllegalStateException` before core loads, like every `RTPHooks` registry.

---

## 3. Considered Options

| Option | Outcome |
|--------|---------|
| **A. Publish `EditorChannel` / `register(type, handler)` to addons** | *Rejected:* freezes the transport, signing and caps in `rtp-api`; addon types share the core namespace; addons could flood the channel past the core feed. |
| **B. Addon JavaScript loaded into the page origin** | *Rejected:* the script could sign `apply` with the trusted key and rewrite any config (section 1 item 4). |
| **C. One relay channel per addon** | *Rejected:* bytesocks counts frames per IP across all channels (ADR-106 section 5.5); a second channel halves the core share. |
| **D. Declarative tab descriptors rendered by the page** | *Accepted* as tier 1. Covers forms over addon YAML, tables, status, markdown and action buttons with no addon code in the page. |
| **E. Addon script in a sandboxed opaque-origin iframe, talking over `postMessage`** | *Accepted* as tier 2: the contract is fixed now, the runner may ship later. Same sandbox model as the curve helpers (ADR-106 section 4.5). |
| **F. Extension types multiplexed over the existing channel, routed and budgeted by core** | *Accepted* (section 5). |

---

## 4. Public API (`rtp-api`)

### 4.1 Registry
- `RTPHooks` shall gain `default EditorExtensionRegistry editorExtensions()`, following `claimBoundaries()`: a default method, so other `RTPHooks` implementations stay binary compatible. `RTPAPI.hooks()` keeps throwing `IllegalStateException` before core loads (S-006).
- `EditorExtensionRegistry` (`io.github.dailystruggle.rtp.api.hooks`, `@PublicApi`):
  - `void register(EditorExtension extension)`: throws `IllegalArgumentException` on an invalid or reserved id (section 5.1) and `IllegalStateException` when another extension holds that id.
  - `boolean unregister(String id)`.
  - `List<EditorExtension> registered()`: a snapshot in id order.
- Registration takes effect at the next `/rtp editor` session. A session's extension list is fixed when its snapshot is built. An extension unregistered during a session gets `sessionClosed` for that session, and its inbound types are dropped from then on.
- Addons register from `RTPAddon` enable (ADR-057) and unregister on disable. Core unregisters every extension on plugin disable.

### 4.2 Extension contract
New package `io.github.dailystruggle.rtp.api.editor`, all `@PublicApi`:

```java
public interface EditorExtension {
    String id();                                   // section 5.1 grammar; stable across releases
    int version();                                 // the addon's own descriptor/message version, >= 1
    String displayName();                          // plain text, shown in tab tooltips and errors
    default List<EditorTab> tabs() { return List.of(); }                 // tier 1 descriptors (section 6.1)
    default Map<String, EditorDelivery> pushTypes() { return Map.of(); } // local type -> delivery
    default Set<String> inboundTypes() { return Set.of(); }              // local types the page may send
    default List<String> configFiles() { return List.of(); }             // section 7
    default EditorFrame frame() { return null; }                         // tier 2 (section 6.2)
    default String snapshotJson() { return null; }  // JSON value under extensions[].snapshot
    default String stateJson() { return null; }     // polled live state, pushed as <id>.state when changed
    default void onMessage(EditorMessage message) {}
    default List<String> validate(String path, String yaml) { return List.of(); } // errors block the apply
    default void applied(String path) {}
    default void sessionOpened(EditorSession session) {}
    default void sessionClosed(EditorSession session) {}
}

public enum EditorDelivery { LATEST, ORDERED }

public interface EditorSession {
    String id();          // opaque per-session id, not the channel id
    boolean isOpen();
    boolean push(String localType, String bodyJson);                     // ORDERED, or LATEST keyed by type
    boolean push(String localType, String coalesceKey, String bodyJson); // LATEST keyed by type + key
}

public interface EditorMessage {
    String type();                 // local type, without the "<id>." prefix
    Map<String, Object> body();    // parsed JSON object without header fields
    String sender();               // browser key fingerprint (already trusted)
    EditorSession session();
    boolean reply(String localType, String bodyJson);
}

public record EditorTab(String id, String title, String icon, List<EditorWidget> widgets) {}
public record EditorWidget(String kind, Map<String, Object> props) {}
public record EditorFrame(String resource, String sha256) {}
```

- `bodyJson` and the `*Json()` returns are JSON texts. Core parses them with `EditorLoopbackJson`, and a value that doesn't parse is refused and logged (S-004).
- `push` and `reply` return `false` when the session is closed, the type isn't declared, the body is over a cap or the budget refuses it (section 5.4). The caller decides whether to retry. Core logs every refusal, rate-limited per extension and reason.
- `EditorWidget.props` values are JSON values only: `String`, `Number`, `Boolean`, `List`, `Map` and `null`. Records keep the Java surface small, while the widget catalog grows through `kind` strings (section 6.1) instead of new Java types.

### 4.3 Threading (S-005)
| Callback | Thread | Budget |
|----------|--------|--------|
| `snapshotJson`, `tabs`, `configFiles`, descriptor getters | the async payload build of `/rtp editor` | logged when over 50 ms |
| `stateJson` | the `EditorLiveFeed` async tick, at most once per tick per extension | logged when over 5 ms |
| `onMessage`, `sessionOpened`, `sessionClosed` | the channel's transport thread | must not block; logged when over 5 ms |
| `validate`, `applied` | the Hot-Apply pipeline thread, off the main thread | `validate` logged when over 50 ms |

No callback runs on a main or region thread. An extension that needs world or player access schedules that work through `RTP.scheduler` (ADR-054) and answers later with `push` or `reply`. Every call is wrapped: a thrown exception is logged (rate-limited to one line per extension per minute) and never reaches core. Three failures in one session mark the extension `failed` for that session: its tabs show the failure, its types are dropped, and core keeps running.

---

## 5. Wire Protocol (extends ADR-106 section 5)

### 5.1 Type namespace
- Core types keep the grammar `[a-z][a-z0-9_\-]{0,31}` and are reserved to core: core may add any undotted type in any release.
- Extension types are `<id>.<local>`:
  - `id`: `[a-z][a-z0-9\-]{1,23}`;
  - `local`: `[a-z][a-z0-9_\-]{0,31}`;
  - so a full type is at most 56 characters. The channel's `TYPE` pattern becomes `[a-z][a-z0-9_\-]{0,31}(\.[a-z][a-z0-9_\-]{0,31})?`, with the id part checked against the id grammar.
- Reserved ids: `rtp`, `core`, `editor`, `bundle`, `ext`, `sys`.
- The local type `state` is reserved for core's `stateJson()` pushes, and `error` for core's error replies.
- Addons pass local types only. Core adds and strips the prefix, so an extension can never send or receive another extension's or core's types.

### 5.2 Negotiation
The snapshot (`EditorSessionManager.createPayloadJson`) shall gain two top-level members. `"version": 1` stays and still names the payload format.

```json
"protocol": { "channel": 2, "extensions": 1 },
"extensions": [
  {
    "id": "leafrtp-action", "version": 1, "name": "Actions",
    "tabs": [ { "id": "triggers", "title": "Action Triggers", "icon": "⚡", "widgets": [ ... ] } ],
    "push": { "state": "latest", "zones": "latest", "log": "ordered" },
    "inbound": [ "run", "refresh" ],
    "files": [ "addons/LeafRTPActionAddon/actions.yml" ],
    "frame": null,
    "snapshot": { ... },
    "error": null
  }
]
```

- `protocol.channel`: 1 is ADR-106 as shipped, and 2 adds sections 5.1, 5.3 and 5.4. `protocol.extensions`: the version of this ADR's descriptor format (sections 6 and 7). A missing `protocol` means `{channel: 1, extensions: 0}`.
- `extensions` is in id order. An extension whose descriptor fails validation, or whose snapshot is over its cap, is listed with only `id`, `version`, `name` and `error` (`descriptor-invalid`, `snapshot-cap`, `failed`). The page shows that error in place of the tab, and the plugin logs it.
- `hello-reply` with `state: "trusted"` shall repeat `protocol` and carry `extensions: [{id, version}]`, so a page that reconnects after a plugin reload notices a changed set and offers to reload the session.
- **Rules for older and newer peers:** a page ignores unknown top-level members, unknown descriptor fields and unknown widget kinds (section 6.1). A plugin answers an inbound type it doesn't know with the ADR-106 drop, which is unchanged. A page shall not send extension types to a plugin with `protocol.channel < 2`.

### 5.3 Delivery classes and bundles
- Delivery becomes a per-type attribute instead of the fixed `BUNDLED` / `DROPPABLE` sets:
  - **reply**: every message sent with `to`. Ordered, never dropped, only delayed (ADR-106 section 5.5).
  - **latest**: a broadcast push that the next push with the same coalescing key replaces. It may be dropped over budget. Core's `feed`, `curve` (keyed by region) and `land` map here.
  - **ordered**: a broadcast push held in order and never coalesced. Over budget or backlog it is refused and the producer is told (`push` returns `false`). Core's `hazard-delta` maps here.
- Extension pushes take their class from `pushTypes()`. `<id>.state` is always `latest`.
- Every `latest` and `ordered` push is bundle-eligible on paced transports.
- **Page rule (changes ADR-106 section 5.3):** the page shall dispatch every bundle item that has a registered handler, and shall ignore an item with a well-formed type and no handler, counting it without logging a drop. Only malformed items (not an object, or no valid type) are dropped as `bad bundle item`. The page shall apply the same rule to unbundled frames, so frames that arrive alone or in bundles behave the same.

### 5.4 Caps and budget shares
- The frame cap (32 KiB) and session budget (2 MiB/min) of ADR-106 section 5.5 apply unchanged. Extension traffic counts against them.
- **Per-extension share:** an extension's outbound bytes are capped at 256 KiB per minute. All extensions together are capped at 25 % of the session budget (512 KiB/min).
- **Paced channels:** extension pushes may take at most 2 frames of each pacing window and hold at most 8 KiB of backlog per extension.
- **Priority order when over budget:** extension `latest`, then extension `ordered`, then core `land`, then core `hazard-delta`. Replies and `curve` are never dropped (ADR-106).
- Snapshot caps: 64 KiB per extension `snapshot`, 16 KiB per extension descriptor without the snapshot, and 256 KiB for the whole `extensions` member. `stateJson()` results over 16 KiB are refused.
- No bytebin hand-off for extension traffic in protocol 2. An oversized extension message is refused and logged (S-004).

### 5.5 Inbound routing
- A page message `<id>.<local>` is accepted only under the existing ADR-106 rules (trusted key, valid signature, live challenge, higher `seq`) and only when `local` is in that extension's `inboundTypes()`. Anything else is dropped with the ADR-106 drop log.
- Inbound extension messages are limited to 20 per second per extension per session. Excess messages are dropped, logged, and answered with `<id>.error {reason: "rate"}`.
- Core strips header fields before `onMessage` and passes the sender's fingerprint as `sender`. Trust is session-wide: a trusted browser may use every extension. Per-extension permissions are deferred (section 9).

---

## 6. Addon UI

### 6.1 Tier 1: declarative tabs (page-rendered)
- Extension tabs follow the core tabs in `nav-tabs`, with DOM id `ext-<extensionId>-<tabId>` (tab ids use the extension id grammar). The page renders every widget itself: text is set as text, and `markdown` goes through the page's existing sanitising renderer.
- Descriptors carry no HTML, no script and no styles. Colours are chosen from the page's palette tokens by name. Links are `https:` only and open with `noopener`.
- Widget kinds for `protocol.extensions = 1`:

| Kind | Props | Binding |
|------|-------|---------|
| `markdown` | `text` | static |
| `status` | `label`, `source`, `levels` | `source` |
| `keyValue` | `source`, `labels?` | `source` |
| `table` | `source`, `columns: [{key, title, format?}]`, `rowAction?` | `source` |
| `form` | `file`, `fields: [{path, title, kind, options?, description?}]` | `file` (section 7) |
| `button` | `label`, `send: local type`, `body?`, `confirm?` | sends `<id>.<send>` |

- `source` is `snapshot:<JSON Pointer>` or `state:<JSON Pointer>` (RFC 6901). The newest `<id>.state` push replaces the `state` root, and a missing state shows the widget's empty state.
- Prop details:
  - `status.levels` maps a value (as text) to a palette name: `green`, `yellow`, `red`, `blue`, `accent`, `mauve`, `teal`, `text`, `subtext`.
  - `table.columns[].format`: `text` (default), `number`, `percent` (a 0..1 fraction), `time` (epoch ms) or `bool`. A table shows at most 500 rows and 16 columns.
  - `table.rowAction`: `{label, send, keys?, confirm?}`. It sends `<id>.<send>` with body `{row: {...}}`, holding the row's scalar cells for `keys` (default: the column keys).
  - `button.body` must be a JSON object; `confirm` is a plain-text question asked before sending.
  - `form.fields[].path` is a dotted key path of at most 8 plain keys (`[A-Za-z0-9_-]`). An edit is checked against its kind, then written into the staged file text in place, keeping comments and other keys. Missing parents are created; an existing block is never replaced by a scalar.
- The page sends `button` / `rowAction` messages only when the type is in the extension's `inbound`, the link is trusted and `protocol.channel >= 2`, and the extension has not failed. A form only edits files in the extension's `files`.
- An extension listed with `error` gets one tab named after it that shows the reason. When a trusted `hello-reply` carries a different extension set (ids or versions) than the snapshot, every extension tab shows a notice to reopen the editor.
- `form` field kinds reuse the ADR-106 section 4.3 settings form: `distance`, `integer`, `number`, `boolean`, `enum`, plus `string`.
- **Reserved kinds** (later releases, additive): `mapOverlay` (zones and markers drawn on the region map, with drag-to-draw producing a staged YAML edit) and `chart`.
- An unknown `kind` is rendered as a placeholder ("needs a newer editor"), never as an error, so a newer addon still works on an older page.

### 6.2 Tier 2: sandboxed frame (contract fixed; runner may follow)
- `EditorFrame.resource` names a classpath resource in the addon jar of at most 64 KiB. Core sends it once per snapshot, with its lowercase hex SHA-256, under `extensions[].frame: {sha256, js}`. The page refuses a source whose hash differs.
- It runs only in an `<iframe sandbox="allow-scripts">` with an opaque origin and the CSP of the curve-helper sandbox (ADR-106 section 4.5). It never runs in the page origin.
- **`postMessage` API, frame to page:**
  - `{op: "send", type, body}`: forwarded as `<id>.<type>` only when `type` is in `inbound`;
  - `{op: "stage", path, text}`: stages an edit to a file in this extension's `files` only;
  - `{op: "resize", height}`.
- **Page to frame:** `{op: "init", snapshot, state, files}` (this extension's own data only), `{op: "push", type, body}` for this extension's types, and `{op: "staged", path, ok, error?}`.
- The frame never receives keys, the channel id, the relay URL, other extensions' data or core data beyond its own descriptor. It can't trigger `apply`: staged edits wait for the operator's Hot-Apply in the page.
- A frame that throws, times out on `init` (5 s) or sends a malformed message is torn down and its tab shows the reason.

---

## 7. Addon Configuration Writes
- `configFiles()` lists paths relative to the plugin data folder. Each shall:
  - normalise inside the data folder (no absolute paths, no `..`, no symlink escape when resolved);
  - end in `.yml` or `.yaml`;
  - not name a core config file: no file in the plugin folder root, nothing under `regions/`, `worlds/`, `advanced/`, `definitions/`, `lang/` or `schematics/`, and no hidden (`.`) path segment. Addon files normally live under `addons/<Addon>/`.
  Invalid paths are rejected at registration and logged. When two extensions declare one path, the first in id order owns it and the later claim is logged and ignored.
- `collectCurrentConfigs` adds the declared files, read off the main thread, to the session `files` map. Their contents count toward the existing payload limits.
- The Hot-Apply pipeline (`EditorLoopbackApply.handleAuthorized` -> `EditorSessionManager.applyPayload`) accepts a declared file and handles it like a core file:
  - structural YAML checks, then the symlink check, then the owning extension's `validate(path, yaml)`;
  - any returned error, or a `validate` that throws, fails the whole apply, with every message (prefixed `<path> (<id>):`) in `apply_ack` (S-004);
  - then the `.bak` copy and the atomic write, then `applied(path)` for the owning extension, before core's own reload. A throwing `applied` is logged and does not undo the write.
  Core does not reload addon config: `applied` is the addon's signal to reload.
- No other write path exists for extensions. An `onMessage` handler may change addon state, but changes to files on disk shall go through Hot-Apply. This keeps one audited write path.

---

## 8. Changes to ADR-106
- **Section 5.1:** the type grammar gains the dotted extension namespace of section 5.1 here.
- **Section 5.2:** `hello-reply {state: "trusted"}` carries `protocol` and `extensions: [{id, version}]`.
- **Section 5.3:** the `bundle` row's "ignores any other item type" is made normative as section 5.3 here, and applies to unbundled frames too. Delivery classes replace the fixed `BUNDLED` / `DROPPABLE` sets.
- **Section 5.5:** extension shares and the priority order of section 5.4 here.
- **Section 8:** addon editor tabs and data are decided here, not deferred.

---

## 9. Scope and Phasing

**3.3.0 (contract; breaking later if skipped):**
- the `rtp-api` interfaces of section 4 and `RTPHooks.editorExtensions()`;
- the type namespace, delivery classes, budget shares and inbound routing in `EditorChannel` / `EditorChannelWiring`;
- `protocol` and `extensions` in the snapshot and `hello-reply`;
- the tolerant bundle and frame dispatch in the page;
- addon `files` through Hot-Apply;
- tier 1 rendering of `markdown`, `status`, `keyValue`, `table`, `button` and `form`;
- in-memory transport tests covering namespacing, routing, budgets, the old-page/new-plugin and new-page/old-plugin cases, and the isolation of a throwing extension.

**Later (additive under `protocol.extensions` bumps):**
- the tier 2 frame runner;
- `mapOverlay` with drag-to-draw trigger zones and `chart`;
- per-extension permissions beyond key trust;
- bytebin hand-off for extension messages;
- the first consumer tab in `LeafRTPActionAddon`.

---

## 10. Consequences

### Positive
- Addons get editor tabs and live data the way the GUI addon gets typed API objects: core keeps the schedule, the transport and the limits.
- Core's protocol stays free to change (binary frames, a self-hosted relay) behind a small, stable addon surface.
- Older and newer pages and plugins tell each other what they support instead of guessing, and unknown things degrade to placeholders, not errors.
- No addon code runs in the page origin. The signing key and Hot-Apply stay under the page's control.
- Addon config gets the same validation, backups and visible failures as core config.

### Negative / Trade-offs
- Tier 1 widgets can't express every UI an addon wants until tier 2 or new kinds ship.
- Extension traffic shares one relay budget with the core feed. The fixed shares can feel tight for chatty addons.
- Trust is all-or-nothing per browser key until per-extension permissions exist.
- More protocol surface to test: version-skew cases and per-extension caps need explicit tests.

---

## 11. References
- `EditorChannel`, `ChannelTransport`, `BytesocksTransport` (`rtp-core/.../commands/editor/channel/`).
- `EditorChannelWiring`, `EditorSessionManager.createPayloadJson` / `collectCurrentConfigs`, `EditorLiveFeed`, `EditorLoopbackApply` (`rtp-core/.../commands/editor/`).
- `RTPHooks`, `RootActionRegistry`, `ClaimBoundaryRegistry` (`rtp-api/.../api/hooks/`).
- `docs/editor/index.html` (`EditorChannelClient`, `CHANNEL_BUNDLED`, `nav-tabs`).
- [DESIGN.md](../dev/DESIGN.md) section *Web Editor Extensions*.
