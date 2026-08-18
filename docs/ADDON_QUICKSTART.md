# Addon Quickstart - Register a Custom Shape

**Current Plugin Version:** `@version@`

This is the shortest path from nothing to a working RTP addon that registers a custom
region shape. It is the "hello world" companion to the router in
[`FOR_ADDON_DEVELOPERS.md`](FOR_ADDON_DEVELOPERS.md) and the fuller walkthrough in
[`addons/LeafRTPCountdownAddon/README.md`](https://github.com/dailystruggle/RTP/blob/V3/addons/LeafRTPCountdownAddon/README.md).

An RTP addon is platform-agnostic: it implements the `RTPAddon` SPI, is discovered via
`ServiceLoader`, and runs unchanged on Bukkit / Spigot / Paper / Folia and Fabric. No
`org.bukkit.*` imports, no plugin loader. See
[ADR-057](adr/ADR-057-platform-agnostic-addon-spi.md) for the loading model and
[ADR-051](adr/ADR-051-two-tier-api-extension-model.md) for why shape registration is the
typed `RTP.addShape(Shape<?>)` call.

---

## 1. Depend on RTP

Compile against `rtp-api` (the `RTPAddon` SPI) and `rtp-core` (the `Shape` hierarchy and
the `RTP` facade). Both are compile-only - RTP provides them at runtime (Gradle `compileOnly`,
Maven `<scope>provided</scope>`).

Your addon is its own project, so pull the published artifacts from **Maven Central** - it is
in the default repository set of both Gradle and Maven, so no repository block or credentials
are needed.

Gradle (`build.gradle`):

```gradle
repositories {
    mavenCentral()
}

dependencies {
    compileOnly 'io.github.dailystruggle:rtp-api:3.2.1'   // RTPAddon SPI
    compileOnly 'io.github.dailystruggle:rtp-core:3.2.1'  // RTP.addShape, Shape, built-in shapes
}
```

Maven (`pom.xml`):

```xml
<dependencies>
    <dependency>                                  <!-- RTPAddon SPI -->
        <groupId>io.github.dailystruggle</groupId>
        <artifactId>rtp-api</artifactId>
        <version>3.2.1</version>
        <scope>provided</scope>
    </dependency>
    <dependency>                                  <!-- RTP.addShape, Shape, built-in shapes -->
        <groupId>io.github.dailystruggle</groupId>
        <artifactId>rtp-core</artifactId>
        <version>3.2.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

Prefer to track a git tag / branch / commit instead of a released version? **JitPack** serves
the same modules on demand.

Gradle:

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.DailyStruggle.RTP:rtp-api:3.2.1'   // RTPAddon SPI
    compileOnly 'com.github.DailyStruggle.RTP:rtp-core:3.2.1'  // RTP.addShape, Shape, built-in shapes
}
```

Maven:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.DailyStruggle.RTP</groupId>
        <artifactId>rtp-api</artifactId>
        <version>3.2.1</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>com.github.DailyStruggle.RTP</groupId>
        <artifactId>rtp-core</artifactId>
        <version>3.2.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

(On JitPack the version is a git tag; a branch like `master-SNAPSHOT` or a commit SHA also
works. See [`dev/PUBLISHING.md`](dev/PUBLISHING.md) for all publishing channels, including the
credentialed GitHub Packages registry.)

Building your addon **as a sub-project of this repository** instead? Skip the coordinates and
depend on the modules directly:

```gradle
dependencies {
    compileOnly project(':rtp-api')   // RTPAddon SPI
    compileOnly project(':rtp-core')  // RTP.addShape, Shape, built-in shapes
}
```

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

Register your shape from `onLoad()` (runs once, after `rtp-core` is up). A genuine custom
shape changes the geometry *in code*, not just a re-configured clone. Below, `DiamondShape`
subclasses `Square` and overrides `select()` to rotate each pick 45 degrees into a diamond -
a bounded transform, no reroll ([ADR-001](adr/ADR-001-archimedean-spiral-1d-mapping.md)).

Because bounds are Chebyshev (max-axis), rotation alone overshoots `radius` by ~1.41x, so
`select()` also divides by `sqrt(2)` to refit. And since the base bounds test
`Shape.contains(int x, int z)` still speaks the square parameterization, override it to invert
the rotation first - exactly as the built-in `Polygon` refines `super.contains(...)`.

```java
package com.example.myrtpaddon;

import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;

/** A square spawn ring rotated 45 degrees into a diamond, computed programmatically. */
public final class DiamondShape extends Square {

  public DiamondShape() {
    super("DIAMOND"); // operators select it as shape=DIAMOND, like any built-in
  }

  @Override
  public int[] select() {
    // Rotate the square pick 45 degrees; the extra 1/sqrt(2) refits the
    // diamond onto the configured radius (max-axis bounds), no reroll.
    int[] xz = super.select();
    double inv = 1.0 / 2.0; // (1/sqrt(2)) for the rotation, times (1/sqrt(2)) to re-fit bounds
    int rx = (int) Math.round((xz[0] - xz[1]) * inv);
    int rz = (int) Math.round((xz[0] + xz[1]) * inv);
    return new int[] {rx, rz};
  }

  @Override
  public boolean contains(int x, int z) {
    // Invert select()'s rotation, then use the parent's square bounds test.
    return super.contains(x + z, z - x);
  }
}
```

Register the instance from your `RTPAddon.onLoad()`:

```java
public final class MyRtpAddon implements RTPAddon {
  @Override
  public void onLoad() {
    RTP.addShape(new DiamondShape());
    RTP.log(java.util.logging.Level.INFO, "[MyRtpAddon] registered shape DIAMOND");
  }
}
```

That is the whole addon. Build it, drop the jar on RTP's classpath (see
[`dev/ADDON_LOADING.md`](dev/ADDON_LOADING.md)), and `DIAMOND` is available everywhere a
built-in shape is. Because `select()` is overridden the new geometry comes entirely from your
code; the inherited `radius` / `centerRadius` knobs still tune the size of the ring you rotate.

---

## 4. Fully custom geometry (when a re-config is not enough)

The `select()` override above reuses the parent square's spiral mapping. To define brand-new
geometry from scratch - so that the bad-location cache, `uniqueplacements`, and the scan
bitmap all stay consistent with your shape - extend `MemoryShape<E extends Enum<E>>` (the base
for all spiral-mapped shapes) and implement its `xzToLocation` / `locationToXZ` / `getRange` /
`rand` contract as a matched pair, then register the instance with the same `RTP.addShape(...)`
call. Read
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
- [`addons/LeafRTPCountdownAddon/`](https://github.com/dailystruggle/RTP/tree/V3/addons/LeafRTPCountdownAddon) - a working reference addon covering config (`ConfigParser`), safety verifiers (`RTPAPI.hooks()`), and teleport-lifecycle callbacks.
- [`dev/ADDON_LOADING.md`](dev/ADDON_LOADING.md) - how RTP discovers, loads, and unloads addons per platform.
- [`dev/EXTERNAL_HOOKS.md`](dev/EXTERNAL_HOOKS.md) - the `RTP.addShape` / `RTP.addVerticalAdjustor` factory entry points and the hook catalog.
