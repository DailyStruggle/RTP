# Loading an Addon

How RTP discovers, loads, and unloads a platform-agnostic addon. This is the deployment
counterpart to the authoring guide in [`addons/RTP_ExampleAddon/README.md`](../../addons/RTP_ExampleAddon/README.md).
The mechanism is defined by [ADR-057](../adr/ADR-057-platform-agnostic-addon-spi.md).

Since ADR-057, an addon is no longer a Bukkit `JavaPlugin` with a `plugin.yml`. It is a plain
jar that:

1. contains a class implementing `io.github.dailystruggle.rtp.api.addon.RTPAddon`, and
2. declares that class in a `META-INF/services` descriptor so RTP can find it via
   `java.util.ServiceLoader`.

This works identically on Bukkit / Spigot / Paper / Folia, Fabric, and (for the `RTPAPI`-only
surface) proxy JVMs, because `ServiceLoader` is pure JDK and requires no Bukkit plugin loader.

---

## What RTP looks for

### 1. The `RTPAddon` implementation

```java
package com.example.myaddon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;

public final class MyAddon implements RTPAddon {
    public MyAddon() {}            // public no-arg constructor is REQUIRED (ServiceLoader)

    @Override public void onLoad()   { /* register parsers, verifiers, post-actions */ }
    @Override public void onUnload() { /* release tickets, cancel tasks, flush state */ }
    @Override public String name()   { return "MyAddon"; }
}
```

`ServiceLoader` instantiates the class reflectively, so it **must** have a public no-argument
constructor.

### 2. The `META-INF/services` descriptor

The jar must contain a file named exactly:

```
META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon
```

whose contents are the fully-qualified name(s) of your implementation, one per line:

```
com.example.myaddon.MyAddon
```

In a Gradle/Maven project this file lives at
`src/main/resources/META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon` and is
packaged into the jar automatically. See the reference addon's descriptor at
[`addons/RTP_ExampleAddon/src/main/resources/META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon`](../../addons/RTP_ExampleAddon/src/main/resources/META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon).

---

## Getting the addon onto RTP's classpath

RTP discovers addons with `ServiceLoader` over **the classloader that loaded `rtp-core`**
(`AddonRegistry.discover()`), so the addon jar must be visible to that classloader. Pick the
path that matches your platform:

| Platform | How to load |
|----------|-------------|
| Bukkit / Spigot / Paper / Folia | Place the addon jar on the same classloader as the RTP plugin. The simplest supported path is bundling the addon inside the RTP distribution (shaded or on the shared classpath). A thin Bukkit `JavaPlugin` shim that calls `RTP.addons.register(new MyAddon())` is also possible for back-compat. |
| Fabric | Ship the addon classes inside (or alongside) the RTP mod jar so they share the mod classloader, or have a mod entrypoint call `RTP.addons.register(new MyAddon())`. |
| Proxy (Velocity / BungeeCord) | Only addons that use the `RTPAPI` query/teleport surface are supported proxy-side: place the jar on the RTP proxy plugin's classpath. Addons touching `RTP.configs` or world state are backend-only. |

> There is currently no standalone `plugins/RTP/addons/` folder scanner; loading means the jar
> is on RTP's classpath (bundled) or its instance is handed to the registry via
> `RTP.addons.register(...)`. A per-platform folder scanner can be added later without changing
> the SPI (ADR-057).

### Programmatic registration

A platform adapter (or a back-compat shim) that has already instantiated an addon can register
it directly instead of relying on `ServiceLoader`:

```java
RTP.addons.register(new MyAddon());
```

`register(...)` ignores `null` and duplicate instances. If core has already finished its load
pass, a late registration is loaded eagerly so it is never silently dropped.

---

## Lifecycle and timing

1. **Discovery + load.** After `rtp-core` finishes initialising, a startup task runs
   `RTP.addons.discover()` then `RTP.addons.loadAll()`. `loadAll()` calls `onLoad()` exactly
   once per addon. Loading is deferred to a startup task so the `RTPAPI` delegates installed by
   the platform adapter (`serverAccessor`, `hooks`, the teleport delegate) are guaranteed
   non-null inside `onLoad()`.
2. **Failure isolation.** Each `onLoad()` / `onUnload()` is wrapped; a throwing addon is logged
   via `RTP.log(Level.WARNING, ...)` and cannot abort the load/unload of its peers.
3. **Unload.** On `RTP.stop()` (server/plugin shutdown or `/rtp reload` teardown),
   `RTP.addons.unloadAll()` calls `onUnload()` on every addon and clears the registry. Release
   anything `onLoad()` allocated here.

### Threading / safety rules for `onLoad()`

`onLoad()` runs on an RTP task thread, not the main thread. Inside it (and in any callback you
register):

- Do not block.
- No synchronous chunk I/O on the main thread (**S-005**).
- Never silently swallow a teleport failure (**S-004**): log with
  `RTP.log(Level.WARNING, msg, t)`.
- Schedule platform-facing work through `RTP.scheduler`.

---

## Verifying the addon loaded

On a successful load you will see in the server log:

```
[ADDONS] loaded addon: MyAddon
```

A discovery or instantiation failure surfaces as a `[ADDONS]` warning. If you see neither line,
the jar is not on RTP's classpath or the `META-INF/services` descriptor is missing/misnamed.

---

## See also

- [`addons/RTP_ExampleAddon/README.md`](../../addons/RTP_ExampleAddon/README.md): authoring guide (the four interfaces an addon uses).
- [ADR-057](../adr/ADR-057-platform-agnostic-addon-spi.md): the platform-agnostic addon SPI decision.
- [`EXTERNAL_HOOKS.md`](EXTERNAL_HOOKS.md): safety verifier / economy / placeholder hooks (ADR-026).
- [`FOR_ADDON_DEVELOPERS.md`](../FOR_ADDON_DEVELOPERS.md): addon-developer entry point.
