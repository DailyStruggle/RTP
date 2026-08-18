# LeafRTPCountdownAddon

A shippable addon that adds two live, per-second teleport **countdowns** to RTP - a delay countdown
for immediate teleports ("Teleporting in N...") and a queue-position countdown for players waiting in
the public wait queue ("you are #N in the queue" -> "you're up!"). Both render as a personal on-screen
**boss-bar** (not chat spam) via the per-player `RTPPlayer.showProgressBar` / `clearProgressBar` API,
so the per-second updates replace one bar in place. It also doubles as the **canonical reference
addon**, demonstrating the four API touch-points most addons need (config + reload, an async safety
verifier, and a post-teleport observer).

It is **platform-agnostic**: it implements the `RTPAddon` SPI and is discovered via `ServiceLoader`,
so the same jar runs on Bukkit / Spigot / Paper / Folia and Fabric (and proxy JVMs, for the
`RTPAPI`-only surface) without any `org.bukkit.*` imports or a Bukkit plugin loader. See
[`ADR-057`](../../docs/adr/ADR-057-platform-agnostic-addon-spi.md).

> **Before the move.** The claim-plugin integrations (WorldGuard, Towny, Factions, …) used to ship
> as a separate addon. They are now bundled in the main `rtp-plugin` jar — see
> [`ADR-019`](../../docs/adr/ADR-019-claim-plugin-integrations-folded-into-plugin.md). This addon
> exists as a compact teaching example so third-party authors have a starting point.

---

## Files

 File | Role
------|------
 `build.gradle` | Gradle sub-project definition. Uses `compileOnly` for `rtp-api` (the `RTPAddon` SPI) and `rtp-core` (`Configs` / `ConfigParser` / `TeleportPipelineTask`). No `spigot-api`.
 `src/main/resources/META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon` | ServiceLoader descriptor naming the `RTPAddon` implementation. This is how RTP discovers the addon on every platform.
 `src/main/resources/countdown.yml` | YAML config the addon ships with. Keys must match the enum constants in `CountdownKeys`.
 `src/main/java/.../CountdownKeys.java` | Typed enum of the YAML keys. RTP's `ConfigParser<E>` is generic over this enum.
 `src/main/java/.../RTPCountdownAddon.java` | The `RTPAddon` implementation. Wires everything up in `onLoad()`.
 `src/main/java/.../CountdownHooks.java` | Two platform-agnostic teleport countdowns (immediate-teleport delay countdown via `TeleportPipelineTask.setupPreActions`; queue-position countdown via `Region.onPlayerQueuePush` / `onPlayerQueuePop`), rendered as per-player boss-bars through `RTPPlayer.showProgressBar` / `clearProgressBar`. Uses `RTP.scheduler` / `RTP.serverAccessor`, no `org.bukkit.*`.

---

## The four interfaces you should know

### 1. Configuration — `ConfigParser<E extends Enum<E>>`

Create an enum (`CountdownKeys`) whose constants name your YAML keys. Register a `ConfigParser`
against RTP's `Configs` registry so that `/rtp reload` picks up your file and `/rtp config`
tab-completes it:

```java
RTP.configs.putParser(
    new ConfigParser<>(
        CountdownKeys.class,
        "countdown",         // YAML basename => countdown.yml
        "1.0",                // schema version
        RTP.serverAccessor.getPluginDirectory(),
        null,
        RTP.configs.fileDatabase,
        this.getClass().getClassLoader()));
```

### 2. Safety contribution — `RTPAPI.hooks().verifiers()`

Contribute a predicate that the pipeline evaluates **asynchronously** for every candidate
location. Register through the public `RTPHooks` facade (ADR-026 — see
[`docs/dev/EXTERNAL_HOOKS.md`](../../docs/dev/EXTERNAL_HOOKS.md)):

```java
RTPAPI.hooks().verifiers().register(coords -> myCheckReturnsTrueIfSafe(coords));
```

Rules of engagement (see `AGENTS.md`, `docs/dev/REQUIREMENTS.md section 3`):

- Must return quickly; the call happens on a worker thread.
- **No synchronous chunk I/O** on the main thread (**S-005**).
- **Do not swallow failures** silently (**S-004**). Log with `RTP.log(Level.WARNING, …, t)`.
- `return true` to accept the location, `return false` to reject it (RTP will reroll).

For an async variant (e.g., when your check awaits a database or network call) use
`RTPAPI.hooks().verifiers().registerAsync(...)`.

> The legacy static API `GlobalRegionVerifiers.addGlobalRegionVerifier(...)` still works for
> source compatibility but is no longer the recommended path for new addons.

### 3. Events — platform-agnostic post-action runnables

Instead of Bukkit events, observe the teleport lifecycle through the platform-agnostic runnable
lists on `TeleportPipelineTask` (and `RTPTeleportCancel`). These fire on every platform and are the
same lists the built-in Bukkit and Fabric effects handlers consume:

```java
TeleportPipelineTask.teleportPostActions.add(task -> onPostTeleport(task));
```

Available lists: `setupPreActions` / `setupPostActions`, `loadPreActions` / `loadPostActions`,
`teleportPreActions` / `teleportPostActions`, `cleanupPreActions` / `cleanupPostActions`, and
`RTPTeleportCancel.postActions`. These callbacks may run off the main thread, so bounce any
platform-facing work onto the appropriate thread via `RTP.scheduler`.

### 4. Reload hook — `Configs.onReload(Runnable)`

When operators run `/rtp reload`, RTP drops its parsers and replays the hook list. Re-register your
parser inside the callback so your config survives the reload:

```java
Configs.onReload(() -> RTP.configs.putParser(buildParser()));
```

---

## Adding a combat checker (PvP / combat-tag gate)

RTP ships an optional pre-flight gate that refuses (or delays) `/rtp` while a player is
combat-tagged (off by default; operators enable it with `pvpCheckEnabled: true` in `safety.yml`).
The gate asks one question — *"is this player in combat right now?"* — through the single-binding
`PvPCombatStateRegistry` SPI. RTP answers it with a native damage-event tracker unless an addon (or
a combat plugin) **binds its own authority**, which then *replaces* the native check.

If your combat plugin is not one of the bundled integrations (PvPManager, CombatLogX, Simple Combat
Log), you do **not** need to wait for RTP to add a `*Checker` for it. Bind your own provider from
your addon's `onLoad()`:

```java
// Single-binding: the last bind wins and replaces RTP's native tracker (and any bundled adapter).
RTPAPI.hooks().pvpCombatState().bind(uuid -> myCombatPlugin.isTagged(uuid));
```

`Provider` is a functional interface — `boolean isInCombat(UUID player)` — so a lambda or method
reference is all you need. To restore RTP's native fallback, call
`RTPAPI.hooks().pvpCombatState().clear()` (do this from `onUnload()` so your provider does not
outlive your addon).

Rules of engagement (see `AGENTS.md`, `docs/dev/REQUIREMENTS.md section 3`, and
[`docs/dev/EXTERNAL_HOOKS.md` section 6](../../docs/dev/EXTERNAL_HOOKS.md)):

- **Thread-safe and non-blocking.** `isInCombat(UUID)` is called from the command thread *and* the
  teleport pipeline. Never perform synchronous I/O or block a region/tick thread — read an
  in-memory tag, not a database. **No synchronous chunk I/O** (**S-005**).
- **Never block a teleport on a bug** (**S-004**). A provider that throws is logged once at WARNING
  and treated as "not in combat", so a broken integration can never trap players. Do not rely on an
  exception to *deny* a teleport.
- **`true` = in combat** (teleport refused/delayed per `safety.yml`), **`false` = free to teleport**.
- **Offline / unknown UUIDs** are never combat-tagged — return `false` early if you cannot resolve
  the player.
- **Single binding.** Binding replaces whatever was bound before (native tracker or a bundled
  adapter). Only bind when your plugin is actually the combat authority on that server.

If you are adding a checker for a *Bukkit* combat plugin to the main RTP jar instead of an addon,
mirror the bundled adapters in `rtp-plugin/.../softdepends/pvp/` (compile against the plugin's
published API as a `compileOnly` dependency, gate on `Bukkit.getPluginManager().isPluginEnabled(...)`,
and declare the plugin as a `softdepend` in `plugin.yml` so its classes resolve at runtime). See
`CombatLogXChecker` / `PvPManagerChecker` for the pattern and `PvPIntegrations` for the bind site.

---

## Build

From the repository root (PowerShell):

```powershell
.\gradlew :addons:LeafRTPCountdownAddon:build
```

The jar (`LeafRTPCountdownAddon-<version>.jar`) lands in `addons/LeafRTPCountdownAddon/build/libs/`.
Because the addon is discovered via
`ServiceLoader`, it must be on RTP's classpath. For the full deployment / loading guide (classpath
placement per platform, lifecycle, and how to verify it loaded) see
[`docs/dev/ADDON_LOADING.md`](../../docs/dev/ADDON_LOADING.md) and
[`ADR-057`](../../docs/adr/ADR-057-platform-agnostic-addon-spi.md).

---

## Safety checklist (for your own addon)

- [ ] A `META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon` entry names your `RTPAddon`.
- [ ] `onLoad()` registers a parser **and** a `Configs.onReload` callback for it.
- [ ] `onUnload()` releases anything `onLoad()` allocated.
- [ ] Long-running work is scheduled (`RTP.scheduler`), never executed on the main thread.
- [ ] Any allocated chunk ticket or pipeline task is released on every exit path (see
      `MemoryTracker`).
- [ ] Failures go through `RTP.log(Level.WARNING, msg, t)` — never `printStackTrace()`.
