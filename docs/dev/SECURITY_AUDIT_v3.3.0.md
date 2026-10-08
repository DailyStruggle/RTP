# v3.3.0 Pre-Release Security and Severe-Bug Audit

Scope: branch `v3.3.0` against `origin/V3` (3.2.1), audited 2026-10-05 and 2026-10-06; reconciled 2026-10-07. Goal: catch severe bugs and security holes before release, not style. Every finding below was confirmed by reading the code path and its callers; unconfirmed reports were dropped.

Open items carry full detail in [`POTENTIAL_BUGS.md`](POTENTIAL_BUGS.md); fixed items are described in [`CHANGELOG.md`](../../CHANGELOG.md) where they reach a released build.

## Result

88 findings in initial audit (plus 2 tracked findings from earlier hardening): 1 critical, 15 high, 45 medium, 27 low/info.

- **Current Status:** 82 fixed, 6 open from original audit (plus 4 post-audit backlog items tracked in `POTENTIAL_BUGS.md`).
- **All 5 original open Highs are RESOLVED:**
  - **Fabric/NeoForge scheduler (RTP-3):** Fixed by driving repeating timers via wall-clock `ScheduledExecutorService` (`ASYNC_TIMER_EXECUTOR`), preventing empty modded servers from pausing heartbeat timers.
  - **Duel consent (RTP-6):** Fixed by reciprocal scoreboard/tag predicate checks, granular per-action permissions (`rtp.action.<action>.other`), and isolated prompt routing.
  - **Group placement (RTP-5):** Fixed by rewriting `GroupPlacementDispatcher` for transactional all-or-nothing placement, atomic rollback, and disconnect cleanup.
- **Zero critical or high security/fail-open vulnerabilities remain open in shipped code.** All S-001..S-005 invariants are strictly enforced across valid data.

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
| High | Fabric / NeoForge scheduler (`RTP-3`) | Repeating tasks were tick-driven, so a paused empty server stopped heartbeats and dropped backend from network routing. | Introduced wall-clock `ScheduledExecutorService ASYNC_TIMER_EXECUTOR` in `FabricScheduler` and `NeoForgeScheduler` independent of server tick loop. |
| High | Action challenge flow (`RTP-6`) | Reciprocity gates never verified sender vs target; non-ops could not accept due to `rtp.other`; prompts routed by matching English strings. | Scoreboard/tag reciprocal check (`rtp_chal_[target]`); per-action parameter permission `rtp.action.<action>.other`; prompt isolation via ADR-098. |
| High | Action group placement (`RTP-5`) | Failed teleports reported as success; players teleported twice; offline queued players matched. | Rewrote `GroupPlacementDispatcher` with transactional all-or-nothing placement, atomic rollback, single-dispatch guarantees, reservation release on exit, and disconnect cleanup. |
| Medium | GUI addon / teleport API | Action and destination clicks reused the permission snapshot from menu build; `RTPAPI.teleport` did not re-check world, biome, region or server permissions. | Action re-checked at click (`MenuModel.canUseAction`); `RTPAPI.teleport` and `getTargetStatus` share one gate (`NO_PERMISSION`), applied before any charge. |
| Medium | Fabric / NeoForge chunks | `isSafe` treated an unresolvable block id as safe (S-001). | Fails closed in every carrier. |
| Medium | Fabric 26.x worlds and adapters | `forgetChunks()` dropped the cache without releasing keep-tickets (S-002); no typed cross-dimension teleport; command dispatch and temp tickets could run off the server thread. | Ticket drain; `TeleportTransition` first, `setPos` same-dimension only; server-thread hops. |
| Medium | Placement | `SubspaceShape` slot validation recursed per candidate (stack overflow); `JumpAdjustor` aborted the whole chunk on one column's coarse miss. | Iterative drain; per-column reset and skip. |
| Medium | Config importer | Foreign region/world/zone names resolved to paths outside the plugin folder. | `resolveSafeChild` containment. |
| Medium | Authorization (x2) | Menu multiconfig add/remove only required read-only `rtp.config.view`; permission migration without `source=` mapped any plugin's nodes onto RTP (`worldedit.*` to `rtp.*`). | Mutate also requires `rtp.config`; without `source=`, sources are derived from plugin folders that look like an rtp plugin (editor thesaurus + fuzzy match), and `rtp.*` / `rtp.admin` / `rtp.reload` need a named `source=`. |
| Medium | Plugin-message network mode | Unsigned heartbeats accepted without a secret on backends and on the Velocity `rtp:net` cache. | Off unless `network.allowUnsigned: true` on each side. |
| Medium | Proxy waitlist / tokens / wire | Leader lease could fall back to always-leader on a shared waitlist; token release failure swallowed; HMAC-failed list rows dropped silently. | Fail closed; logged; whole list rejected. |
| Medium | Action session ending (`RTP-12`) | Terminal handlers were not one-shot and dropped delayed steps; boundary violations debounced poorly; bundled KOTH/teams DISARM left players in adventure mode. | Atomic single-shot terminal handler dispatch; delayed steps preserved; boundary violation debouncing; gamemode restored reliably on DISARM. |
| Medium | Action config parsing (`RTP-13`) | `boundary: NONE`, combined durations, inline step gates, and non-`rtp_*` scoreboard gates were misread silently. | Hardened parsers in `ActionConfigParser` to support `NONE` boundaries, composite duration strings, inline condition steps, and generic objective comparisons. |
| Medium | Action threading & cache (`RTP-17`) | Session startup ran on an unpinned thread; cached slots ignored `centerRadius`. | Pinned session lifecycle to server/region tick schedulers; subspace slot cache incorporates `centerRadius`. |
| Medium | GUI addon main menu & renderers (`RTP-19`, `RTP-25`) | Main menu lacked pagination (22+ destinations hid submenu row); failed modded menu open triggered teleport; hex/MiniMessage names displayed raw on modded. | Added pagination controls to destination and action pickers; failed menu open cleanly aborts teleport pipeline; MiniMessage/hex strings rendered safely on Fabric/NeoForge. |
| Medium | Public API (`RTP-16`) | `RtpTarget.coordinate` bypassed permission gate and safety pipeline and fell back to first world; `ACTION` targets routed to default region. | Enforced permission checks and safety pipeline on coordinate targets; invalid worlds fail closed; `ACTION` targets routed to action manager. |
| Medium | Region-file readers (`RTP-14`) | `.linear` preceding lengths were uncapped `int` sums; region byte cache had no file-size cap and stranded waiters on error; malformed palettes defaulted to `palette[0]`. | Linear reader extracted to external addon per ADR-077; region byte cache capped at 64 MiB; corrupt palettes reject columns as `UNKNOWN`. |
| Medium | Folia rubberband teleport (S-004) | Rubberband teleport future never completed if entity was retired; failed snaps reported success. | Fail-closed completion on retired entity; attribution of failed snaps as teleport failure. |
| Medium | Operator-triggered main-thread blocking (`RTP-8`, `RTP-9`, `RTP-10`) | `effects-api` firework/glide used `Bukkit.getScheduler()` on Folia and accepted unbounded counts; config import, permission migration, and setup confirm ran disk I/O on main thread; `executeCommandWithCapture` slept up to 3s on main thread with unfiltered JUL/Log4j hooks. | Bounded Folia regional scheduler dispatch in `effects-api`; config import, permission migration, and setup confirm moved off the main thread; console capture rewritten without thread sleeping and filtered to caller command. |
| Medium | Placement availability (`RTP-20`) | Claim/group subspace anchors hardcoded Y=64. | Dynamic surface elevation lookup using world height and collision/surface probes. |
| Medium | Redis TLS & authentication (`RTP-23`) | Backend Redis connection ignored `transport.redis.tls` and `username` unless `host` was a `rediss://` URL. | Honoured `transport.redis.tls` and `username` options regardless of URL scheme. |
| Medium | Signed heartbeats replay freshness (`RTP-24`) | Signed plugin-message heartbeats accepted replayable packets indefinitely. | Added monotonic timestamping and 15-second freshness window to heartbeat validator. |
| Low | Teleport pipeline | Distance computation could skip post-teleport cleanup; origin read after teleport. | Guarded; origin captured first. |
| Low | `ClaimAnchoredRegionTracker` | `anchorCache` had no eviction. | 4096-entry cap with idle TTL and LRU trim. |
| Low | `BukkitRTPChunk` | `isSafe` returned safe when neither a live chunk nor a snapshot was present (latent S-001). | Fails closed. |
| Low | Editor | Docs viewer `javascript:` filter bypass; flow-style secrets survived upload redaction. | Link allow-list; inline-value redaction plus placeholder guard on apply. |
| Low / Info | Proxy in-memory store, Gradle lock | Unbounded queue / correlation set / teleport-time map; full and module builds could overlap. | Caps and terminal scrubbing; global lock waits for module locks. |
| Low | Repository hygiene | Jazzer's working corpus (`.cifuzz-corpus/`) and JVM `replay_pid*` files were not ignored. | `.gitignore` entries; `<Class>Inputs/` crash reproducers stay committable as regression seeds. |
| Low | Action and GUI polish (`RTP-25`) | Bundled titles ran as player (op only); `broadcast` wasn't vanilla; no team win check; hardcoded action and menu text; dead settings; stale aliases; READY shown to locked-out players; blacklisted biomes offered. | Routed admin titles through server console; vanilla-compatible broadcast syntax; team win condition evaluation; configurable localization keys; dead settings purged; dynamic alias refresh; permission-aware READY badge; biome blacklist filters applied to menu. |
| Low | YAML and platform hardening (`RTP-26`) | YAML reader had no depth/size cap; NeoForge cannot extract bundled docs; noisy GUI renderer trace on Fabric 1.21.x; new API enums broke default-less switches; `RTPAPI.checkPermission` soft-fails pre-init; devstack staged 26.x-only GUI jar into 1.21.x mods. | Added depth cap (32) and size limit to YAML reader; fixed NeoForge docs extraction; suppressed benign Fabric GUI traces; added default cases for enum switches; `checkPermission` fails loud pre-init; devstack mod staging version-gated. |
| Low | Web Editor & Trust Store (`RTP-27`, `RTP-30`) | Apply parser had no file-count cap; `trusted-editors.json` permissions were not owner-only and vulnerable to race conditions. | Capped apply parser at 256 files; converted to AST parsing; set POSIX file permissions to owner-only with atomic replace writes. |
| Low | Network topology spoofing | Unsolicited `GetServer`/`GetServers` topology replies were trusted on backends. | Backend rejects unsolicited topology packets unless matching an outstanding nonce request. |
| Low | Lifecycle and bounds gaps (`RTP-29`) | bStats sampler not cancelled on reload; docs export ran on common pool; null-probe fail-open; hardcoded `NO_PERMISSION` string. | bStats sampler lifecycle registered with `MemoryTracker`; docs export offloaded to dedicated worker pool; null probes fail closed; `NO_PERMISSION` localized through messages config. |
| Low | Redis protocol byte delegation | `RespPool` did not delegate `executeCommandBytes`. | Implemented `executeCommandBytes` delegation in `RespPool`. |

## Open (see `POTENTIAL_BUGS.md`)

Only 6 findings from the original audit remain open, along with 4 post-audit backlog entries tracked in `POTENTIAL_BUGS.md`:

### Residual Audit Findings
- **Claim boundary providers (`RTP-15`, Medium, availability):** Providers disable on any error, axis-only 512-block probing, Towny town-block size ignored, uncapped SafeZone/WarZone enumeration, first-claim anchoring. Per-location checkers still enforce S-003.
- **Release pipeline (`RTP-7`, Medium):** Version/step outputs template-injected into `run:` scripts (limited exposure: no secrets for forks).
- **Release pipeline (`RTP-11`, Medium):** Tag pushed before Pro build, unsigned Lite jar still published, missing marketplace tokens skip silently.
- **Fuzzing assurance (`RTP-21`, Medium):** Weekly Jazzer job fuzzes only one Anvil target for 2s in CI (`-Djazzer.duration` not forwarded to test JVM; Jazzer runs one `@FuzzTest` per class).
- **Surface biome extraction (`RTP-22`, Low):** Pregen biome extraction reads surface biomes with Anvil probe regardless of format (relevant when external region format addons like Linear are registered).
- **Fuzz target completeness (`RTP-28`, Low):** Anvil targets pinned to chunk (0, 0); oracles are exception-only; parsers of untrusted input (YAML, editor JSON, plugin-message envelope) have no Jazzer targets.

### Post-Audit Backlog (from `POTENTIAL_BUGS.md`)
- **Arrived players sync-load landing chunk (`RTP-4`, High, Status: In Progress):** On Paper, arrived player first tick in `LivingEntity.baseTick` > `isInWall` triggers `ServerChunkCache.syncLoad` when arrival ticket is released before player ticket holds it (`delay-chunk-unloads-by: 0s`). Fix pending bench verification: `TeleportPipelineTask.holdArrivalReservation`.
- **ACCUMULATE chunk repeat under dense learned bad area (`RTP-2`, Medium, Status: Triaged):** Every merged rejection renumbers good indices, so a used chunk can reappear at an undrawn index; spacing loosens when learned bad area approaches a full bin. Documented limit in `REGIONS.md`.
- **Web editor reference materialize undo/redo stack (`RTP-31`, Low, Status: Open):** Materializing `@config` references via direct textarea property assignment clears native browser undo stack.
- **Bare `/rtp` early cooldown guard (`RTP-32`, Low, Status: Open):** Bare `/rtp` early spam guard in `RTPCmd.onCommand` checks global `sender.cooldown()` before destination region resolve, ignoring shorter region-specific cooldown overrides.

Known design limitations, not filed as bugs: the `sql` transport is single-proxy only (always-leader lease, no queue hand-back), and the database layer runs two independent drain timers (`flush` and `processQueries`) instead of the single batched executor it was designed around; a redesign is pending.

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
- Verified: Full `./gradlew build` passes against the repository with all uncommitted fixes and test suites green (`:rtp-fabric:rtp-fabric-common:test`, `:rtp-neoforge:rtp-neoforge-common:test`, `Rtp5And6ActionSubsystemTest`, `FabricSchedulerTimerTest`, `NeoForgeSchedulerTimerTest`, `AnvilCorruptedRegionHardeningTest`, etc.).
- Devstack acceptance: cross-server roundtrip PASSED; killmidflight PASSED; heartbeat convergence on Fabric and NeoForge PASSED following the wall-clock scheduler timer fix (`RTP-3`); both proxies and all Paper/Folia/Fabric/NeoForge instances converged.
- Node (in Docker): the editor page's inline script parses under V8, and the docs-viewer link filter blocked all 8 scheme-obfuscation payloads while allowing http(s), mailto and relative links.
- Not run: the POSIX `gradlew` lock path.
