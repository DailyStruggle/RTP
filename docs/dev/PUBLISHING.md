# Publishing the addon-facing API artifacts

RTP supports two addon-development dependencies: `rtp-api` (the `RTPAddon` SPI and the
configurable selection model) and `rtp-core` (the `RTP` facade, the `Shape` hierarchy,
`ConfigParser`, etc.). Out-of-repo addon authors cannot use the in-build
`compileOnly project(':rtp-api')` references, so those two modules (plus their
inter-module project-dependency closure) are published to a public Maven coordinate.

This document covers the two channels:

1. **JitPack** - active now, zero-credentials, builds straight from a git tag.
2. **Maven Central** - the durable, discoverable channel; not yet enabled, full how-to below.

The publication wiring itself lives in the root `build.gradle` (`maven-publish` applied to
the `publishedModulePaths` closure) and is shared by both channels.

---

## What gets published

`maven-publish` is applied to exactly this closure (root `build.gradle`):

| Module | Why it is in the closure |
|--------|--------------------------|
| `rtp-api` | addon SPI (direct addon dependency) |
| `rtp-core` | `RTP` facade + `Shape` (direct addon dependency) |
| `commands-api` | `api` dep of `rtp-api` / `rtp-core` |
| `yaml-api` | `api` dep of `rtp-api` / `rtp-core` |
| `metrics-api` | `api` dep of `rtp-api` |
| `effects-api` | `api` dep of `rtp-core` |
| `maps-api` | `api` dep of `rtp-core` |
| `rtp-proxy:rtp-proxy-common` | `api` dep of `rtp-core` |

Every coordinate that can appear in a generated POM must itself be published, otherwise a
consumer's transitive resolution fails. Platform adapters (`rtp-bukkit`, `rtp-paper`,
`rtp-folia`, `rtp-fabric`), the shaded `rtp-plugin` jar, and the JDK-25 Fabric/Loom
submodules are deliberately **not** published - they are runtime artifacts, not compile
dependencies for addons.

The artifacts are thin (un-shaded) jars: RTP provides the classes at runtime, so addons
depend on them with `compileOnly`.

---

## JitPack (active)

### Configuration in this repo

- Root `build.gradle` applies `maven-publish` to the closure above (publication name
  `mavenJava`, `from components.java`).
- `jitpack.yml` (repo root) pins **JDK 21** and overrides the install command to publish
  only the closure with `publishToMavenLocal -PexcludeJdk25`. The `-PexcludeJdk25` flag
  (handled in `settings.gradle`) drops the JDK-25 / unobfuscated-Loom modules
  (`effects-api:effects-api-fabric-unobf`, `rtp-fabric:rtp-fabric-common-unobf`,
  `rtp-fabric:rtp-fabric-v26_1_R1`, and the shaded `rtp-plugin` aggregator that bundles
  them) from the build graph entirely. Without it, Gradle still *configures* those modules
  and fails resolving their Java 25 toolchain, which is absent on JitPack's JDK-21-only
  host - the published closure never depends on them, so excluding them is safe.

### How JitPack serves it

JitPack builds the tag, runs the `install:` command, and harvests the artifacts from the
build's local Maven repo. For multi-module builds JitPack publishes every module it finds
and exposes each as:

```
com.github.DailyStruggle.RTP:<module>:<tag>
```

JitPack handles the inter-module references (the generated POMs use the
`io.github.dailystruggle` group; JitPack maps the same-group cross-references to the
`com.github.DailyStruggle.RTP` group it serves), so a consumer only needs the JitPack repo.

### Consumer build.gradle (out-of-repo addon)

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.DailyStruggle.RTP:rtp-api:3.0.1'
    compileOnly 'com.github.DailyStruggle.RTP:rtp-core:3.0.1'
}
```

Replace `3.0.1` with any git tag, a branch (`master-SNAPSHOT`), or a commit SHA. The first
build for a new tag triggers JitPack to compile; subsequent resolves are cached.

### Triggering / verifying a build

1. Push a git tag (e.g. `3.0.1`) to `github.com/DailyStruggle/RTP`.
2. Visit `https://jitpack.io/#DailyStruggle/RTP/3.0.1` and click **Get it** (or just let a
   consumer's first dependency resolve trigger the build).
3. Inspect the build log on that page if a module fails to resolve.

### Local smoke test (no JitPack needed)

The exact command JitPack runs can be reproduced locally to confirm the publication graph:

```powershell
.\gradlew --no-daemon -PexcludeJdk25 `
    :commands-api:publishToMavenLocal :yaml-api:publishToMavenLocal `
    :metrics-api:publishToMavenLocal :maps-api:publishToMavenLocal `
    :effects-api:publishToMavenLocal :rtp-proxy:rtp-proxy-common:publishToMavenLocal `
    :rtp-api:publishToMavenLocal :rtp-core:publishToMavenLocal
```

`-PexcludeJdk25` reproduces JitPack's JDK-21-only environment by dropping the JDK-25 Loom
modules from the build graph (see `settings.gradle`). On a workstation that has JDK 25
installed the flag is optional, but include it to mirror exactly what JitPack runs.

The artifacts land under `~/.m2/repository/io/github/dailystruggle/`.

### CI publishing on push / merge to V3

The Jenkins pipeline (`Jenkinsfile`) runs a **Publish API Artifacts** stage on every push or
merge to the `V3` (and `V3-beta`) branch. It runs the same `publishToMavenLocal` closure as the
local smoke test (`PUBLISH_TASKS` env var, kept in sync with `publishedModulePaths` in the root
`build.gradle` and the `install:` block in `jitpack.yml`). This validates that the addon-API
publication graph still builds and that every transitive coordinate is publishable, catching a
broken POM before a consumer's JitPack resolve hits it.

Note that JitPack itself needs **no push** from CI: it builds the branch on demand the first
time a consumer resolves a snapshot. So out-of-repo addon authors can track the development
line directly:

```gradle
repositories { maven { url 'https://jitpack.io' } }
dependencies {
    compileOnly 'com.github.DailyStruggle.RTP:rtp-api:V3-SNAPSHOT'
    compileOnly 'com.github.DailyStruggle.RTP:rtp-core:V3-SNAPSHOT'
}
```

`V3-SNAPSHOT` resolves to the latest commit on the `V3` branch (JitPack refreshes it; add
`changing = true` / a short cache TTL on the consumer side to pick up new commits). Tagged
releases (`com.github.DailyStruggle.RTP:rtp-api:<tag>`) remain the stable, immutable channel.

To turn the CI stage into a **remote** publish (e.g. Maven Central snapshots or GitHub
Packages), replace the `publishToMavenLocal` tasks in `PUBLISH_TASKS` with the credentialed
`publish` target and inject the repository credentials via Jenkins secrets - see the Maven
Central section below for the publication/signing wiring.

---

## Maven Central (how to enable later)

Maven Central is the right destination once the `rtp-api` contract is stable and you expect
real third-party volume. It is more work than JitPack (one-time account + signing setup) but
gives discoverable, permanent coordinates with no per-tag build step on the consumer side.

> Sequencing note: ideally land Phase 2 of `docs/dev/scratch/CHECKLIST-rtp-api-devux.md`
> (the typed `rtp-api` registration surface) before the first Central release, so the
> permanent public contract is not pinned to `rtp-core` internals.

### 1. One-time account setup

1. Create a **Sonatype Central** account at <https://central.sonatype.com>.
2. Verify ownership of the `io.github.dailystruggle` namespace. For a `io.github.<user>`
   group this is automatic via a GitHub verification (Central asks you to create a short-lived
   public repo named after a generated code). The group already matches the GitHub org
   (`DailyStruggle`), so no group-id change is needed.
3. Generate a **user token** (username + password pair) under your Central account; these are
   the credentials Gradle uses to upload.

### 2. GPG signing key

Maven Central requires every artifact to be GPG-signed.

```powershell
gpg --gen-key                              # create a key
gpg --list-secret-keys --keyid-format short
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # publish the public key
```

Export the secret key (in-memory ASCII-armored form is easiest for CI):

```powershell
gpg --armor --export-secret-keys <KEY_ID>
```

### 3. Build-script changes

Apply the `signing` plugin alongside `maven-publish` for the published closure, and add the
POM metadata Central requires (name, description, url, license, developers, scm). In the
root `build.gradle` block that already configures `mavenJava`:

```gradle
configure(publishedModulePaths.collect { project(it) }) {
    apply plugin: 'maven-publish'
    apply plugin: 'signing'

    afterEvaluate {
        // sources + javadoc jars are mandatory on Central
        java { withSourcesJar(); withJavadocJar() }

        publishing {
            publications {
                mavenJava(MavenPublication) {
                    from components.java
                    pom {
                        name = project.name
                        description = 'RTP addon-facing API module: ' + project.name
                        url = 'https://github.com/DailyStruggle/RTP'
                        licenses {
                            license {
                                name = 'GPL-3.0'   // confirm against repo LICENSE
                                url  = 'https://www.gnu.org/licenses/gpl-3.0.txt'
                            }
                        }
                        developers {
                            developer { id = 'DailyStruggle'; name = 'DailyStruggle' }
                        }
                        scm {
                            connection = 'scm:git:https://github.com/DailyStruggle/RTP.git'
                            developerConnection = 'scm:git:ssh://git@github.com/DailyStruggle/RTP.git'
                            url = 'https://github.com/DailyStruggle/RTP'
                        }
                    }
                }
            }
        }

        signing {
            def signingKey = findProperty('signingKey')
            def signingPassword = findProperty('signingPassword')
            if (signingKey) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign publishing.publications.mavenJava
            }
        }
    }
}
```

For the upload itself, the simplest current path is the
**`com.vanniktech.maven.publish`** community plugin (it targets the new Central Portal and
handles the staging/release dance), or the official
`org.sonatype.central:central-publishing-maven-plugin` for a Maven-side flow. With the
vanniktech plugin the publication metadata above is configured through its `mavenPublishing { }`
DSL instead of hand-rolled POM blocks.

### 4. Decouple the artifact version (Phase 5.1)

The plugin currently versions everything at `3.0.1` (root `build.gradle`). For Central,
give the API artifacts a version line that moves on the `rtp-api`/`rtp-core` semver cadence
rather than the plugin release cadence, and document the policy (Phase 5.2 of
`CHECKLIST-rtp-api-devux.md`). A practical approach is an `apiVersion` ext property applied
only to the published closure.

### 5. Credentials (never commit)

Put tokens and the signing key in `~/.gradle/gradle.properties` or CI secrets:

```properties
mavenCentralUsername=<central-token-user>
mavenCentralPassword=<central-token-pass>
signingKey=-----BEGIN PGP PRIVATE KEY BLOCK----- ...
signingPassword=<key-passphrase>
```

### 6. Publish

```powershell
# vanniktech plugin
.\gradlew publishToMavenCentral --no-configuration-cache
# or, after staging, release via the Central Portal UI / publishAndReleaseToMavenCentral
```

### Consumer build.gradle (after Central release)

```gradle
repositories { mavenCentral() }

dependencies {
    compileOnly 'io.github.dailystruggle:rtp-api:<version>'
    compileOnly 'io.github.dailystruggle:rtp-core:<version>'
}
```

No special repository block needed - Central is in every build's default set.

---

## Compatibility policy (to document with the first stable release)

- Semantic versioning for `rtp-api`/`rtp-core` independent of the plugin version.
- State clearly which `rtp-core` types are contract (e.g. `RTP.addShape`, `Shape`,
  `ConfigParser`) vs. internal-and-may-break, since `rtp-core` exposes more than `rtp-api`
  (see ADR-051 and `CHECKLIST-rtp-api-devux.md` Phase 2).
- Link the published Javadoc from `FOR_ADDON_DEVELOPERS.md`.
