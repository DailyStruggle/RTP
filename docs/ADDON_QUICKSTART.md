# Addon Quickstart - Register a Custom Shape

**Current Plugin Version:** `3.0.0-beta.1`

This is the shortest path from nothing to a working RTP addon that registers a custom
region shape. It is the "hello world" companion to the router in
[`FOR_ADDON_DEVELOPERS.md`](FOR_ADDON_DEVELOPERS.md) and the fuller walkthrough in
[`../addons/LeafRTPCountdownAddon/README.md`](../addons/LeafRTPCountdownAddon/README.md).

An RTP addon is platform-agnostic: it implements the `RTPAddon` SPI, is discovered via
`ServiceLoader`, and runs unchanged on Bukkit / Spigot / Paper / Folia and Fabric. No
`org.bukkit.*` imports, no plugin loader. See
[ADR-057](adr/ADR-057-platform-agnostic-addon-spi.md) for the loading model and
[ADR-051](adr/ADR-051-two-tier-api-extension-model.md) for why shape registration is the
typed `RTP.addShape(Shape<?>)` call.

---

## 1. Depend on RTP (`build.gradle`)

Compile against `rtp-api` (the `RTPAddon` SPI) and `rtp-core` (the `Shape` hierarchy and
the `RTP` facade). Both are `compileOnly` - RTP provides them at runtime.

In-repo addon (a sub-project of this build):

```gradle
dependencies {
    compileOnly project(':rtp-api')   // RTPAddon SPI
    compileOnly project(':rtp-core')  // RTP.addShape, Shape, built-in shapes
}
```

Building **outside** this repository? Pull the published artifacts from JitPack instead:

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.DailyStruggle.RTP:rtp-api:3.0.1'   // RTPAddon SPI
    compileOnly 'com.github.DailyStruggle.RTP:rtp-core:3.0.1'  // RTP.addShape, Shape, built-in shapes
}
```

(`3.0.1` is a git tag; a branch like `master-SNAPSHOT` or a commit SHA also works. See
[`dev/PUBLISHING.md`](dev/PUBLISHING.md) for the publishing setup and the Maven Central path.)

---

## 2. Register the addon for `ServiceLoader`

Create one file so RTP can discover your addon on every platform:

```
src/main/resources/META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon
```

with a single line naming your implementation class:

```
com.example.myrtpaddon.MyRtpAddon
```

---

## 3. Register a custom shape in ~20 lines

`onLoad()` runs once, after `rtp-core` has finished initialising, on an RTP task thread.
That is the moment to register your shape. The simplest custom shape is a re-configured
clone of a built-in one, registered under a new name so operators can select it as a region
shape named `BIGSQUARE` exactly like any built-in shape:

```java
package com.example.myrtpaddon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

public final class MyRtpAddon implements RTPAddon {

  @Override
  public void onLoad() {
    // A 4096-radius square spawn ring, registered under a new name.
    Square bigSquare = new Square("BIGSQUARE");
    bigSquare.set(GenericMemoryShapeParams.radius, 4096);
    bigSquare.set(GenericMemoryShapeParams.centerRadius, 512);
    RTP.addShape(bigSquare);
    RTP.log(java.util.logging.Level.INFO, "[MyRtpAddon] registered shape BIGSQUARE");
  }
}
```

That is the whole addon. Build it, drop the jar on RTP's classpath (see
[`dev/ADDON_LOADING.md`](dev/ADDON_LOADING.md)), and `BIGSQUARE` is available everywhere a
built-in shape is.

---

## 4. Fully custom geometry (when a re-config is not enough)

To define brand-new geometry rather than re-configuring an existing shape, extend
`MemoryShape<E extends Enum<E>>` (the base for all spiral-mapped shapes) and implement its
`xzToLocation` / `locationToXZ` / `getRange` / `rand` contract, then register the instance
with the same `RTP.addShape(...)` call. Read
[`dev/CONCEPTS.md`](dev/CONCEPTS.md) (the spiral 1D mapping) and
[ADR-001](adr/ADR-001-archimedean-spiral-1d-mapping.md) first - the bounded-distribution
contract is mandatory, and unbounded reroll loops are prohibited.

---

## 5. Clean up in `onUnload()`

If your real addon allocates anything (scheduled tasks via `RTP.scheduler`, chunk tickets,
DB writes), release it in `onUnload()`. A pure shape registration needs no teardown.

```java
@Override
public void onUnload() {
  // cancel tasks / release tickets / flush state here
}
```

---

## Where to go next

- [`FOR_ADDON_DEVELOPERS.md`](FOR_ADDON_DEVELOPERS.md) - recommended reading order and the API stability contract.
- [`../addons/LeafRTPCountdownAddon/`](../addons/LeafRTPCountdownAddon/) - a working reference addon covering config (`ConfigParser`), safety verifiers (`RTPAPI.hooks()`), and teleport-lifecycle callbacks.
- [`dev/ADDON_LOADING.md`](dev/ADDON_LOADING.md) - how RTP discovers, loads, and unloads addons per platform.
- [`dev/EXTERNAL_HOOKS.md`](dev/EXTERNAL_HOOKS.md) - the `RTP.addShape` / `RTP.addVerticalAdjustor` factory entry points and the hook catalog.
