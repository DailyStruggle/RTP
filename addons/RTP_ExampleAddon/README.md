# RTP_ExampleAddon

A reference implementation showing **how to write an addon** for the RTP plugin on Bukkit / Spigot /
Paper / Folia servers. The goal is to demonstrate the four interfaces most addons need without
introducing any real business logic.

> **Before the move.** The claim-plugin integrations (WorldGuard, Towny, Factions, …) used to ship
> as a separate addon. They are now bundled in the main `rtp-plugin` jar — see
> [`ADR-019`](../../docs/adr/ADR-019-claim-plugin-integrations-folded-into-plugin.md). This addon
> exists as a compact teaching example so third-party authors have a starting point.

---

## Files

 File | Role
------|------
 `build.gradle` | Gradle sub-project definition. Uses `compileOnly` for everything — you only need `rtp-api` (and, optionally, `rtp-core` / `rtp-plugin`) on your compile classpath.
 `src/main/resources/plugin.yml` | Standard Bukkit plugin descriptor. `depend: [ RTP ]` guarantees RTP is loaded first.
 `src/main/resources/example.yml` | Example YAML config your addon ships with. Keys must match the enum constants in `ExampleKeys`.
 `src/main/java/.../ExampleKeys.java` | Typed enum of the YAML keys. RTP's `ConfigParser<E>` is generic over this enum.
 `src/main/java/.../RTPExampleAddon.java` | The main `JavaPlugin` class. Wires everything up in `onEnable`.
 `src/main/java/.../ExampleTeleportListener.java` | Bukkit listener for `PostTeleportEvent`.

---

## The four interfaces you should know

### 1. Configuration — `ConfigParser<E extends Enum<E>>`

Create an enum (`ExampleKeys`) whose constants name your YAML keys. Register a `ConfigParser`
against RTP's `Configs` registry so that `/rtp reload` picks up your file and `/rtp config`
tab-completes it:

```java
RTP.configs.putParser(
    new ConfigParser<>(
        ExampleKeys.class,
        "example",           // YAML basename => example.yml
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

Rules of engagement (see `AGENTS.md`, `docs/dev/REQUIREMENTS.md §3`):

- Must return quickly; the call happens on a worker thread.
- **No synchronous chunk I/O** on the main thread (**S-005**).
- **Do not swallow failures** silently (**S-004**). Log with `RTP.log(Level.WARNING, …, t)`.
- `return true` to accept the location, `return false` to reject it (RTP will reroll).

For an async variant (e.g., when your check awaits a database or network call) use
`RTPAPI.hooks().verifiers().registerAsync(...)`.

> The legacy static API `GlobalRegionVerifiers.addGlobalRegionVerifier(...)` still works for
> source compatibility but is no longer the recommended path for new addons.

### 3. Events — Bukkit listeners

RTP publishes standard Bukkit events under
`io.github.dailystruggle.rtp.bukkit.events` (`PreTeleportEvent`, `PostTeleportEvent`,
`TeleportCancelEvent`, `PlayerQueuePopEvent`, …). Register a `Listener` as you would for any
Bukkit event:

```java
Bukkit.getPluginManager().registerEvents(new ExampleTeleportListener(), this);
```

### 4. Reload hook — `Configs.onReload(Runnable)`

When operators run `/rtp reload`, RTP drops its parsers and replays the hook list. Re-register your
parser inside the callback so your config survives the reload:

```java
Configs.onReload(() -> RTP.configs.putParser(buildParser()));
```

---

## Build

From the repository root (PowerShell):

```powershell
.\gradlew :addons:RTP_ExampleAddon:build
```

The jar lands in `addons/RTP_ExampleAddon/build/libs/`. Drop it into the server's `plugins/` folder
alongside the RTP jar.

---

## Safety checklist (for your own addon)

- [ ] `plugin.yml` has `depend: [ RTP ]`.
- [ ] `onEnable` registers a parser **and** a `Configs.onReload` callback for it.
- [ ] Long-running work is scheduled (`RTP.scheduler`), never executed on the main thread.
- [ ] Any allocated chunk ticket or pipeline task is released on every exit path (see
      `MemoryTracker`).
- [ ] Failures go through `RTP.log(Level.WARNING, msg, t)` — never `printStackTrace()`.
