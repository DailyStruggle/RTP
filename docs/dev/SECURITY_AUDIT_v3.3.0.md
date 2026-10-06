# v3.3.0 Pre-Release Security and Severe-Bug Audit

Scope: branch `v3.3.0` against `origin/V3` (3.2.1), audited 2026-10-05 and 2026-10-06. Goal: catch severe bugs and security holes before release, not style. Every finding below was confirmed by reading the code path and its callers; unconfirmed reports were dropped.

Open items carry full detail in [`POTENTIAL_BUGS.md`](POTENTIAL_BUGS.md); fixed items are described in [`CHANGELOG.md`](../../CHANGELOG.md) where they reach a released build.

## Result

88 findings: 1 critical, 15 high, 45 medium, 27 low/info. 35 fixed, 53 open. 29 of the open findings come from the action and GUI addon pass (2026-10-06); 3 findings come from the Jazzer fuzz-target pass (2026-10-06).

Five Highs are open: four in the action addon, filed as two entries, plus the Fabric/NeoForge scheduler.

- **Fabric/NeoForge scheduler:** on Fabric (and NeoForge 26.x), RTP's timers stop when vanilla pauses an empty server, so an empty modded backend drops out of network routing (found by the devstack run, workaround `pause-when-empty-seconds=0`).
- **Duel consent:** consent is not cross-checked, and non-ops cannot accept.
- **Group placement:** failed teleports are reported as success, and offline queued players are matched.

The action Highs are functional defects in the newest feature code, not security holes. The worst abuse is forcing someone into a duel they didn't choose.

No safety fail-open (S-001..S-005) remains open on valid data; malformed region-file palettes are a latent S-001 on corrupt files only. No authorization gap found by the audit remains open. No item-duplication, command-injection, path-escape (after the fix), or memory-unsafe parsing paths remain in the audited areas.

## Fixed

| Severity | Area | Finding | Fix |
|---|---|---|---|
| Critical | Fabric / NeoForge claims | FTB Chunks and Open Parties verifiers registered with inverted polarity: claimed land allowed, wilderness rejected (S-003). | Register `!isInClaim`. |
| High | Bukkit claim addon (14 checkers) | Any exception disabled the integration for the session and reported "not claimed" (S-003). Shipped in 3.2.1. | `ClaimCheckFailure`: per-call error rejects the spot; only API absence disables. |
| High | WorldGuard | Opt-in flag registered after WorldGuard locks its registry, so regions never blocked landings; null region manager disabled protection everywhere. | Unregistered flag means any region blocks; unmanaged world means no regions. |
| High | Fabric / NeoForge claims | Same fail-open pattern in the mod checkers. | `ModClaimCheckFailure` / `NeoForgeClaimCheckFailure`. |
| High | `ClaimAnchoredRegionTracker` | Block coordinates fed into chunk-keyed memory; unbounded block-area loop per claim hit; inherited hazards never matched. | Chunk-unit keys throughout; block loop removed. |
| High | Fabric 26.3 player | Cross-dimension fallback moved the player in the origin world, then reported failure. | Never `setPos` across dimensions. |
| High | Proxy trigger source | A proxy not holding the player cancelled their shared-queue request; cross-server `/rtp` vanished on multi-proxy Redis or after an ownership-tag miss (S-004). Shipped in 3.2.1. | Conditional Redis hand-back (`requeue.lua`); cancel only after a 60 s orphan grace or on JVM-local queues. |
| High | `ProxyDirectListener` | Unbounded cached thread pool per accepted socket (DoS). | 64 concurrent / 16 per address, excess closed unread. |
| High | SQL database drain | `AbstractSQLDatabaseAccessor.flush()` never returned its pooled connection and `processQueries` skipped `disconnect` on early returns; on MySQL/PostgreSQL the pool drained within a few flushes and persistence stopped. Shipped in 3.2.1. | Release in `finally` on every exit path; regression test against a 2-slot pool. |
| High | Action forfeit | `leave`/forfeit subcommands let session metadata overwrite the caller's tokens, so `kill [player_name]` could hit the opponent; they also ran outside a match. | Caller tokens applied last; refused with the configurable `notInSession` message unless the caller is in a session of that action. |
| High | Bukkit scoreboards | Resetting an `rtp_*` score called `resetScores(entry)`, wiping the player from every objective on each session end. | Only the named objective is cleared (`Score.resetScore()`, else the other scores are snapshotted and restored). |
| Medium | GUI addon / teleport API | Action and destination clicks reused the permission snapshot from menu build; `RTPAPI.teleport` did not re-check world, biome, region or server permissions. | Action re-checked at click (`MenuModel.canUseAction`); `RTPAPI.teleport` and `getTargetStatus` share one gate (`NO_PERMISSION`), applied before any charge. |
| Medium | Fabric / NeoForge chunks | `isSafe` treated an unresolvable block id as safe (S-001). | Fails closed in every carrier. |
| Medium | Fabric 26.x worlds and adapters | `forgetChunks()` dropped the cache without releasing keep-tickets (S-002); no typed cross-dimension teleport; command dispatch and temp tickets could run off the server thread. | Ticket drain; `TeleportTransition` first, `setPos` same-dimension only; server-thread hops. |
| Medium | Placement | `SubspaceShape` slot validation recursed per candidate (stack overflow); `JumpAdjustor` aborted the whole chunk on one column's coarse miss. | Iterative drain; per-column reset and skip. |
| Medium | Config importer | Foreign region/world/zone names resolved to paths outside the plugin folder. | `resolveSafeChild` containment. |
| Medium | Authorization (x2) | Menu multiconfig add/remove only required read-only `rtp.config.view`; permission migration without `source=` mapped any plugin's nodes onto RTP (`worldedit.*` to `rtp.*`). | Mutate also requires `rtp.config`; without `source=`, sources are derived from plugin folders that look like an rtp plugin (editor thesaurus + fuzzy match), and `rtp.*` / `rtp.admin` / `rtp.reload` need a named `source=`. |
| Medium | Plugin-message network mode | Unsigned heartbeats accepted without a secret on backends and on the Velocity `rtp:net` cache. | Off unless `network.allowUnsigned: true` on each side. |
| Medium | Proxy waitlist / tokens / wire | Leader lease could fall back to always-leader on a shared waitlist; token release failure swallowed; HMAC-failed list rows dropped silently. | Fail closed; logged; whole list rejected. |
| Low | Teleport pipeline | Distance computation could skip post-teleport cleanup; origin read after teleport. | Guarded; origin captured first. |
| Low | `ClaimAnchoredRegionTracker` | `anchorCache` had no eviction. | 4096-entry cap with idle TTL and LRU trim. |
| Low | `BukkitRTPChunk` | `isSafe` returned safe when neither a live chunk nor a snapshot was present (latent S-001). | Fails closed. |
| Low | Editor | Docs viewer `javascript:` filter bypass; flow-style secrets survived upload redaction. | Link allow-list; inline-value redaction plus placeholder guard on apply. |
| Low / Info | Proxy in-memory store, Gradle lock | Unbounded queue / correlation set / teleport-time map; full and module builds could overlap. | Caps and terminal scrubbing; global lock waits for module locks. |
| Low | Repository hygiene | Jazzer's working corpus (`.cifuzz-corpus/`) and JVM `replay_pid*` files were not ignored. | `.gitignore` entries; `<Class>Inputs/` crash reproducers stay committable as regression seeds. |

## Open (see `POTENTIAL_BUGS.md`)

- **Fabric/NeoForge scheduler (High):** repeating tasks are tick-driven, so a paused empty server stops heartbeats and drops out of the network.
- **Action addon (High x2):**
  - **Challenge flow:** reciprocity gates never compare one player's target with the other's sender; the shared `player` parameter needs `rtp.other`, so non-ops cannot accept; enqueue messages are routed by matching English text.
  - **Group placement:** per-player teleport results are ignored and everyone is teleported twice; offline players stay queued, and sessions don't end on quit.
- **Action addon (Medium x3):**
  - **Session ending:** terminal handlers are not one-shot and drop delayed steps; violations count per block; bundled KOTH/teams DISARM leaves players in adventure mode.
  - **Config parsing:** `boundary: NONE`, combined durations, inline step gates and non-`rtp_*` scoreboard gates are misread silently.
  - **Threading and cache:** session start runs on an unpinned thread; cached slots ignore `centerRadius`.
- **GUI addon (Medium):** the main menu has no pagination (22+ destinations hide the submenu row, 28+ drop entries); a failed Fabric/NeoForge open teleports the player; modded renderers show hex/MiniMessage names raw.
- **Public API (Medium):** the new `RtpTarget.coordinate` path skips the permission gate and safety pipeline and falls back to the first world when the named world is missing; an `ACTION` target teleports to the default region. Only `/rtp back` uses it in-tree.
- **Region-file readers (Medium):** `.linear` preceding lengths are uncapped `int` sums; the region byte cache has no file-size cap and can strand waiters on an `Error`; malformed palettes read as `palette[0]`. Needs a corrupt or crafted world file.
- **Claim boundary providers (Medium, availability):** disable on any error, axis-only 512-block probing, Towny town-block size ignored, uncapped SafeZone/WarZone enumeration, first-claim anchoring. Per-location checkers still enforce S-003.
- **Folia (Medium):** the rubberband teleport future never completes if the entity is retired, and a failed snap reports success (S-004, shipped in 3.2.1).
- **Release pipeline (Medium x2):** version/step outputs template-injected into `run:` (limited exposure: no secrets for forks); tag pushed before the Pro build, unsigned Lite jar still published, missing marketplace tokens skip silently.
- **Operator-triggered load (Medium x5):** effects-api firework/glide use `Bukkit.getScheduler()` on Folia and accept unbounded counts; visualization export has no size cap; config import, permission migration and setup/prefab confirm run disk I/O and reload on the main thread; the Bukkit `executeCommandWithCapture` sleeps up to ~3 s per call on the main thread and hooks the root JUL/Log4j loggers unfiltered, so unrelated log lines can reach the migration parser.
- **Placement availability (Medium, fail closed):** claim/group anchors hardcode Y=64.
- **Fuzzing (Medium, assurance):** the weekly Jazzer job fuzzes only one of the five Anvil targets (Jazzer runs one `@FuzzTest` per class; the rest report SKIPPED and the job passes), for the annotation's 2 s instead of the advertised duration (`-Djazzer.duration` is the wrong key and never reaches the test JVM), can reuse a cached test result, and has no seed corpus. The LZ4, Linear and region-view parsers have effectively never been fuzzed in CI.
- **Low x11:**
  - **Action and GUI polish:** bundled titles run as the player (op only); `broadcast` isn't vanilla; no team win check; hardcoded action and menu text; dead `textReady`/`menuRows` settings; stale aliases after reload; READY shown to locked-out players; blacklisted biomes offered.
  - **YAML and platform:** the YAML reader has no depth/size cap; NeoForge cannot extract bundled docs; noisy GUI renderer trace on Fabric 1.21.x.
  - **API:** new API enum constants break default-less switches; `RTPAPI.checkPermission` soft-fails pre-init.
  - **Devstack:** the GUI step stages a 26.x-only jar into 1.21.x mods.
  - **Editor:** the apply parser has no file-count cap; the trust file is not owner-only and is last-writer-wins.
  - **Network:** unsolicited `GetServer`/`GetServers` topology replies are trusted (proxies drop client-sent ones).
  - **Fuzz targets:** the Linear target is pinned to chunk `(0, 0)`, so the open preceding-length bug is unreachable; oracles are exception-only, so the `palette[0]` fail-open cannot be seen; tolerated exception sets are broader than the parsers throw; the YAML, editor JSON, plugin-message envelope, proxy-direct wire, biome codec, tag JSON and safety-token parsers have no target.
  - **Misc:** bStats sampler not cancelled; docs export on the common pool; null-probe fail-open, palette, export-name, unpinned-action, devstack hygiene, late Fabric/NeoForge capture output and the hardcoded `NO_PERMISSION` text.

Known design limitations, not filed as bugs: the `sql` transport is single-proxy only (always-leader lease, no queue hand-back), and the database layer runs two independent drain timers (`flush` and `processQueries`) instead of the single batched executor it was designed around; a redesign is pending. Signed heartbeats have no replay-freshness window; filed in `POTENTIAL_BUGS.md` after the audit.

Found during the earlier hardening pass and tracked in `POTENTIAL_BUGS.md`, not counted above: the backend Redis connection ignores `transport.redis.tls` and `username` unless `host` is a `rediss://` URL (Medium: the password can travel in plain text), and `RespPool` does not delegate `executeCommandBytes` (Low, no callers yet).

## What held up well

- **Hosted editor channel:** signed messages with session, challenge and strictly increasing sequence checks; RSA-2048 keys stored owner-only and written atomically; single-use, expiring, constant-time trust codes shown only to the session creator.
- **File writes from the editor:** path allow-list plus symlink-resolved containment; https-only outbound URLs (http only to loopback); 4 MiB decompression cap.
- **Parsers:** LZ4 and NBT decoders bound every size before allocating, guard overflow and offsets, always make progress, verify checksums, and fail to a normal chunk load.
- **Network security posture:** Redis, SQL and proxy-direct refuse to run without a 32-byte secret; reservation tokens are single-use (CAS), expiring, bound to player and server, and signature-checked on redeem; HMAC compares are constant-time; message parsers are size and line capped.
- **Action system:** no money logic to double-charge; participants reserved atomically and released on every failure path; player text cannot reach console commands (`ActionPlaceholderSanitizer`, unresolved placeholders drop the command).
- **GUI item safety:** Bukkit menus identified by `InventoryHolder`, every click and drag type cancelled; Fabric/NeoForge `clicked()` fully overridden; no per-player leaks.
- **Action and GUI addons, second pass:**
  - **GUI:** click-time permission re-checks match the build-time filter exactly; destination status and teleport share one permission gate; biome/action pagination is clamped with no slot collisions; invalid materials fall back instead of crashing; menu opens run on the player's or server scheduler.
  - **Action config and commands:** one malformed action definition is skipped without stopping the rest; the gate expression parser is regex-only and fails closed; cancelling another player needs `rtp.other` plus `rtp.action.cancel.other`; every command, message and gate placeholder goes through the sanitizer.
  - **Action runtime:** `disarm()` is idempotent and prunes session maps; borders are clamped and reset; damage hops to the player's scheduler; chunk reservations are released on every path.
- **Teleport pipeline and chunk loading:** cleanup in `finally` on every exit path (S-004); Fabric/NeoForge tickets added on the server thread and released on completion; no synchronous main-thread chunk loads found.
- **Selection math:** `CurveHash` overflow guard, `SegmentedKeyRunTable` bounds, `Circle` origin guard, per-slot claim verification in `GroupPlacementDispatcher`.
- **Command framework:** `TreeCommand` enforces node and parameter permissions before execution; menu and book clicks re-enter the root with a live permission probe; admin panels are double-gated on `rtp.menu.admin`; prefab file names reject `..` and absolute paths.
- **Editor, remaining surface:** every channel handler runs only after signature and trust checks; `EditorLoopbackJson` is depth-capped and fail-closed; `BiomeBinCodec`/`WorldBiomeStore` loads are length-checked; the bytesocks transport caps frames and backs off; the live feed sends aggregates only (no names, IPs or coordinates).
- **Data layer:** all SQL values are bound parameters with internal table names; pool checkout is bounded (5 s) and CAS-allocated; migration is dry-run by default, append-only (never unsets), and preserves negation and contexts.
- **bStats:** honours the global `enabled: false` opt-out at start and send; no player names, UUIDs, IPs or world names; HTTPS only, async send via `RTP.scheduler`, upstream-compatible cadence; chart callbacks fail soft.
- **Shared Bukkit adapter:** the new plugin-message length reader rejects non-positive and oversized lengths and survives malformed frames; capture handlers are always removed in `finally`; `damagePlayer` hops to the player's scheduler; scoreboard objective names are fixed constants; region-file biome sampling stays off-tick and returns empty on error; Spark reflection uses fixed class names and fails soft.
- **API compatibility for 3.3.0:** every changed 3.2.1 type is a final class with private constructors and static factories, so added fields are binary-compatible; no existing interface gained an abstract method (new ones are defaults or on new types).
- **Parsers, second pass:** the YAML reader rejects anchors, aliases, tags and merge keys (no SnakeYAML, no `!!java`); `.mca` sector math uses `long`; zlib/gzip/LZ4 decompression is capped at 32 MiB; NBT reads are depth- and length-checked; thrown decode errors map to UNKNOWN, never ACCEPT.
- **Fuzz harnesses:** both classes are deterministic, in-memory and side-effect free; the LZ4 and NBT targets accept only the parser's declared checked exception; `RespProtocol` itself is depth-, line-, length- and total-element-capped, so its target raised no parser finding.
- **CI and supply chain:** no `pull_request_target`/`workflow_run`; privileged workflows pin actions to SHAs; Maven Central fails closed without signatures; HTTPS-only repositories; wrapper URL and checksum validation unchanged; no real secrets committed in devstack.

## Not covered

- Not audited: S-007 message configurability across all commands, addon modules other than the claim, GUI and action addons (Countdown and Rift are unchanged since 3.2.1), and code unchanged since 3.2.1. The claim boundary providers, anvil-api readers, yaml-api and the rtp-api surface were audited on 2026-10-06. The action system (core, commands, addon and its 11 bundled definitions) and the GUI addon had a functional second pass on 2026-10-06; cooldown bookkeeping in `PhysicalTriggerManager` was not covered. The Jazzer fuzz targets were audited on 2026-10-06, including a local `JAZZER_FUZZ=1` run of `AnvilRegionFuzzTest` (one target fuzzed for about 3 s, four reported SKIPPED).
- Verified: a full `.\gradlew.bat build` passed on 2026-10-06 against the working tree including the uncommitted fixes listed above, which were each reviewed against their finding.
- Devstack acceptance (2026-10-06): cross-server roundtrip PASSED (lobby-a to backend-a in 2.6 s over Redis with signed messages); killmidflight PASSED (a claimed reservation was reaped about 33 s after its destination backend was killed); heartbeat convergence FAILED only because the empty Fabric backend paused (the open High above); both proxies and all Paper/Folia instances converged. The modded backends first failed to boot because of a stale 26.x-only GUI jar left in their `mods/` folders by an earlier GUI run (renamed `*.disabled-by-audit`).
- Node (in Docker): the editor page's inline script parses under V8, and the docs-viewer link filter blocked all 8 scheme-obfuscation payloads while allowing http(s), mailto and relative links.
- Not run: the POSIX `gradlew` lock path.
