# Publishing the addon-facing API artifacts

RTP supports two addon-development dependencies: `rtp-api` (the `RTPAddon` SPI and the
configurable selection model) and `rtp-core` (the `RTP` facade, the `Shape` hierarchy,
`ConfigParser`, etc.). Out-of-repo addon authors cannot use the in-build
`compileOnly project(':rtp-api')` references, so those two modules (plus their
inter-module project-dependency closure) are published to a public Maven coordinate.

This document covers the channels:

1. **Maven Central** - active now; the durable, discoverable, zero-credentials channel for
   consumers (`io.github.dailystruggle:rtp-api` / `:rtp-core`, currently `3.2.1`).
2. **JitPack** - active now, zero-credentials, builds straight from a git tag / branch / commit.
3. **GitHub Packages** - repo-scoped Maven registry, credentialed; wiring is in place.

The publication wiring itself lives in the root `build.gradle` (`maven-publish` applied to
the `publishedModulePaths` closure) and is shared by all channels.

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

### Consumer config (out-of-repo addon)

Gradle (`build.gradle`):

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.DailyStruggle.RTP:rtp-api:3.2.1'
    compileOnly 'com.github.DailyStruggle.RTP:rtp-core:3.2.1'
}
```

Maven (`pom.xml`) - JitPack is not a default Maven repository, so declare it explicitly
(`compileOnly` maps to Maven `<scope>provided</scope>`):

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

Replace `3.2.1` with any git tag, a branch (`master-SNAPSHOT`), or a commit SHA. The first
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

## GitHub Packages (repo-scoped Maven registry)

GitHub Packages hosts a Maven registry per repository at
`https://maven.pkg.github.com/DailyStruggle/RTP`. It needs credentials on both ends (publish
*and* consume), so it is not as frictionless as JitPack, but it requires no Sonatype account
or GPG signing and it lives right next to the code. Use it when you want authenticated,
immutable coordinates without the Maven Central onboarding.

### Configuration in this repo

The root `build.gradle` publishing closure already declares the GitHub Packages repository
(`name = 'GitHubPackages'`) for the same `publishedModulePaths` closure. It is only wired
when a token is present, so a credential-free `.\gradlew build` is unaffected and no
unauthenticated `publish` target is exposed. Credentials are resolved in this order:

1. Gradle properties `gpr.user` / `gpr.key` (put them in `~/.gradle/gradle.properties`, never
   commit them).
2. Env vars `GITHUB_ACTOR` / `GITHUB_TOKEN` (GitHub Actions injects these automatically).

The publish token needs the **`write:packages`** scope (a classic PAT with `write:packages`,
or the workflow `GITHUB_TOKEN` with `packages: write` permission). Reading needs
**`read:packages`**.

### Publishing locally

Add credentials to `~/.gradle/gradle.properties`:

```properties
gpr.user=<your-github-username>
gpr.key=<personal-access-token-with-write:packages>
```

Then publish the closure to GitHub Packages (mirror the smoke-test task list, swapping
`publishToMavenLocal` for `publishMavenJavaPublicationToGitHubPackagesRepository`). Do **not**
pass `-PexcludeJdk25` here: that flag is only for JDK-21-only hosts like JitPack, and this
publish runs on a box that has JDK 25, so the JDK-25 Loom modules should stay in the build
graph:

```powershell
.\gradlew --no-daemon `
    :commands-api:publishMavenJavaPublicationToGitHubPackagesRepository `
    :yaml-api:publishMavenJavaPublicationToGitHubPackagesRepository `
    :metrics-api:publishMavenJavaPublicationToGitHubPackagesRepository `
    :maps-api:publishMavenJavaPublicationToGitHubPackagesRepository `
    :effects-api:publishMavenJavaPublicationToGitHubPackagesRepository `
    :rtp-proxy:rtp-proxy-common:publishMavenJavaPublicationToGitHubPackagesRepository `
    :rtp-api:publishMavenJavaPublicationToGitHubPackagesRepository `
    :rtp-core:publishMavenJavaPublicationToGitHubPackagesRepository
```

The shorter `.\gradlew publish` also works but fans out to every publishable
module and every declared repository; the explicit task list keeps it to the addon-API
closure and the GitHub Packages target only.

### Publishing from CI (GitHub Actions)

A minimal workflow needs `packages: write` permission and the built-in token:

```yaml
permissions:
  contents: read
  packages: write
# ...
    - name: Publish to GitHub Packages
      run: ./gradlew publishMavenJavaPublicationToGitHubPackagesRepository
      env:
        GITHUB_ACTOR: ${{ github.actor }}
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

For the Jenkins pipeline, inject a PAT as `gpr.user` / `gpr.key` (or `GITHUB_ACTOR` /
`GITHUB_TOKEN`) secrets and swap the `publishToMavenLocal` tasks in `PUBLISH_TASKS` for the
`...ToGitHubPackagesRepository` targets.

### Consumer config (out-of-repo addon)

GitHub Packages requires the consumer to authenticate too (there is no anonymous read).

Gradle (`build.gradle`):

```gradle
repositories {
    mavenCentral()
    maven {
        url = 'https://maven.pkg.github.com/DailyStruggle/RTP'
        credentials {
            username = findProperty('gpr.user') ?: System.getenv('GITHUB_ACTOR')
            password = findProperty('gpr.key')  ?: System.getenv('GITHUB_TOKEN')
        }
    }
}

dependencies {
    compileOnly 'io.github.dailystruggle:rtp-api:3.2.1'
    compileOnly 'io.github.dailystruggle:rtp-core:3.2.1'
}
```

Maven (`pom.xml`) - declare the repository and put the credentials in `~/.m2/settings.xml`
under a matching `<server><id>github</id>...</server>` (username = GitHub user, password = a
PAT with `read:packages`):

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/DailyStruggle/RTP</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>io.github.dailystruggle</groupId>
        <artifactId>rtp-api</artifactId>
        <version>3.2.1</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>io.github.dailystruggle</groupId>
        <artifactId>rtp-core</artifactId>
        <version>3.2.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

The consumer's token only needs `read:packages`. Because of this read-time credential
requirement, JitPack remains the friendlier choice for public addon authors; GitHub Packages
is best for internal / org-scoped consumers who already authenticate to GitHub.

---

## Maven Central (first-party wiring, manual Portal upload)

Maven Central is **live** - `io.github.dailystruggle:rtp-api` / `:rtp-core` (and their published
closure) are available at `3.2.1`, resolvable with a bare `mavenCentral()` and no credentials.
It gives discoverable, permanent coordinates with no per-tag build step on the consumer side.
The rest of this section is the release procedure for cutting the *next* version; consumers only
need the [consumer snippet below](#consumer-buildgradle-maven-central).

**Trust posture:** this build deliberately uses **only first-party Gradle tooling** for the
Central path - `maven-publish` and the built-in `signing` plugin, both of which ship inside
the Gradle distribution. No third-party publish plugin is applied, so no external, auto-
updating build code ever gets access to the Central token, the GPG signing key, or the
compiled jars before they are signed. The trade-off is one manual step at release time:
Gradle signs the artifacts into a local staging directory, and that bundle is uploaded to the
Central Portal by hand (or a single `curl`). Sonatype's new Portal has no first-party Gradle
upload protocol, so automating that last step would mean adding a community plugin - which is
exactly the trust surface this posture avoids.

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

### 3. Build-script changes (already in place)

The root `build.gradle` `configure(publishedModulePaths.collect { project(it) })` block
already implements the full first-party Central wiring - you do **not** need to edit the build
for a release. What it does:

- Applies the built-in **`signing`** plugin alongside `maven-publish`.
- Produces the mandatory **`-sources` and `-javadoc` jars** for the published closure
  (`withSourcesJar()` / `withJavadocJar()`), with doclint relaxed for those javadoc tasks so
  a missing `@param`/`@return` on `rtp-api`/`rtp-core` cannot fail a release.
- Adds the **POM metadata** Central validates (name, description, url, license, developers, scm).
- Declares a local **`CentralStaging`** Maven repository at `<root>/build/central-staging/`,
  shared by every module so the whole closure lands in one directory ready to bundle.
- Wires **GPG signing** in two mutually exclusive modes, both gated so a credential-free
  `.\gradlew build` never signs and never fails:
  - **In-memory key** from the `signingKey` / `signingPassword` Gradle properties (best for
    CI: no gpg binary or keyring needed).
  - **Local `gpg` CLI**, enabled with `-PsignWithGpgCmd` (Gradle shells out to `gpg`).
- Skips the `rtp-core` **test-fixtures variant** from the published component, so no stray
  `-test-fixtures` jar is published (Central rejects those) and the "cannot be mapped to
  Maven" POM-capability warning is gone.

Because everything is gated on the credentials being present, none of this affects a normal
developer build, JitPack, or the GitHub Packages path.

> **Modern-GnuPG note (verified 2026-08-16):** a secret key exported by a very recent GnuPG
> (2.4+/2.5+; this box runs 2.5.18) can be **unreadable by the BouncyCastle bundled in
> Gradle's `signing` plugin**, failing the sign task with `Cannot perform signing task ...
> because it has no configured signatory`. If you hit that with the in-memory `signingKey`
> path, switch to the `gpg` CLI path (`-PsignWithGpgCmd`), which signs with whatever key
> `gpg` itself resolves (respecting `GNUPGHOME` / `gpg.conf`) and side-steps the parse issue.
> The full staging + signing flow (`.asc` for jar, sources, javadoc, module, and pom) was
> verified end-to-end via the `gpg` CLI path.

### 4. Decouple the artifact version (Phase 5.1)

The plugin currently versions everything at `3.0.1` (root `build.gradle`). For Central,
give the API artifacts a version line that moves on the `rtp-api`/`rtp-core` semver cadence
rather than the plugin release cadence, and document the policy (Phase 5.2 of
`CHECKLIST-rtp-api-devux.md`). A practical approach is an `apiVersion` ext property applied
only to the published closure.

### 5. Credentials (never commit)

The signing key + passphrase feed the build; the Portal token is used only by the upload
step (not the build).

**Local dev box** - put the signing key in `~/.gradle/gradle.properties` (never in the repo):

```properties
signingKey=-----BEGIN PGP PRIVATE KEY BLOCK----- ...
signingPassword=<key-passphrase>
```

**CI (the recommended place to publish from)** - do not write a properties file. Gradle maps
any env var named `ORG_GRADLE_PROJECT_<name>` to the project property `<name>`, so expose the
signing key as CI secrets with exactly these names:

```
ORG_GRADLE_PROJECT_signingKey       = <ASCII-armored PGP private key>
ORG_GRADLE_PROJECT_signingPassword  = <key passphrase>
```

That is the "repo variable" the signing step reads - no build-script change is needed to feed
it. Store the armored key as a single multi-line secret (GitHub Actions / Jenkins credentials
both support multi-line values). The Central Portal token (`central-token-user` /
`central-token-pass`) is a **separate** secret used only by the upload command below; keep it
out of the build entirely.

### 6. Publish (sign locally, upload the bundle)

Step 1 - sign the closure into the local staging dir (mirror the smoke-test task list,
targeting the `CentralStaging` repo). With the signing key present, each publish task also
runs the `sign...` task automatically. Do **not** pass `-PexcludeJdk25`: that flag is only for
JDK-21-only hosts like JitPack, and a release runs on a JDK-25 box, so leave the JDK-25 Loom
modules in the build graph:

In-memory-key form (CI):

```powershell
.\gradlew --no-daemon `
    :commands-api:publishMavenJavaPublicationToCentralStagingRepository `
    :yaml-api:publishMavenJavaPublicationToCentralStagingRepository `
    :metrics-api:publishMavenJavaPublicationToCentralStagingRepository `
    :maps-api:publishMavenJavaPublicationToCentralStagingRepository `
    :effects-api:publishMavenJavaPublicationToCentralStagingRepository `
    :rtp-proxy:rtp-proxy-common:publishMavenJavaPublicationToCentralStagingRepository `
    :rtp-api:publishMavenJavaPublicationToCentralStagingRepository `
    :rtp-core:publishMavenJavaPublicationToCentralStagingRepository
```

Local `gpg` CLI form (recommended on a box with a modern GnuPG, e.g. this dev box) - add
`-PsignWithGpgCmd` and drop the `signingKey` properties; `gpg` supplies the key:

```powershell
.\gradlew --no-daemon -PsignWithGpgCmd `
    :commands-api:publishMavenJavaPublicationToCentralStagingRepository `
    :yaml-api:publishMavenJavaPublicationToCentralStagingRepository `
    :metrics-api:publishMavenJavaPublicationToCentralStagingRepository `
    :maps-api:publishMavenJavaPublicationToCentralStagingRepository `
    :effects-api:publishMavenJavaPublicationToCentralStagingRepository `
    :rtp-proxy:rtp-proxy-common:publishMavenJavaPublicationToCentralStagingRepository `
    :rtp-api:publishMavenJavaPublicationToCentralStagingRepository `
    :rtp-core:publishMavenJavaPublicationToCentralStagingRepository
```

The signed artifacts (jar, sources, javadoc, `.pom`, and their `.asc` signatures) land under
`build/central-staging/io/github/dailystruggle/...`.

Step 2 - zip that tree and upload it to the Portal. The Portal expects a zip whose internal
layout is the Maven repository layout (i.e. the contents of `build/central-staging`):

```powershell
Compress-Archive -Path build/central-staging/* -DestinationPath build/central-bundle.zip -Force
$pair = '<central-token-user>:<central-token-pass>'
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
curl.exe -H "Authorization: Bearer $auth" `
    -F bundle=@build/central-bundle.zip `
    https://central.sonatype.com/api/v1/publisher/upload
```

Step 3 - go to <https://central.sonatype.com> → **Deployments**, review the validated
deployment, and click **Publish** (or drop the automatic-release query param on the upload).
That manual click is the price of not handing a third-party plugin your signing key.

### 7. CI publishing (GitHub Actions)

The workflow `.github/workflows/maven-central.yml` automates steps 1-2 above (sign into the
`CentralStaging` dir, zip, and `curl` the bundle to the Portal). It runs **only on a push to a
release-line branch** (`V1`, `V2`, `V3`, ... and their lowercase forms), plus manual
`workflow_dispatch`. Because those branches are protected, a push happens only when a pull
request is merged - so publication is gated behind a PR, never an ad-hoc direct commit or a
feature branch.

Required repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Purpose |
|--------|---------|
| `SIGNING_KEY` | ASCII-armored PGP private key (multi-line), fed to Gradle as `ORG_GRADLE_PROJECT_signingKey` |
| `SIGNING_PASSWORD` | passphrase for the key (empty string if none) |
| `CENTRAL_TOKEN_USER` | Central Portal user-token username (upload only) |
| `CENTRAL_TOKEN_PASS` | Central Portal user-token password (upload only) |

The workflow uses the **in-memory-key** signing path (no `gpg` binary on the runner), verifies
that `.asc` signatures were actually produced before uploading (fail-loud per S-004), and does
**not** auto-release - the deployment lands VALIDATED and a human clicks **Publish** on the
Portal. To auto-release instead, append `?publishingType=AUTOMATIC` to the upload URL in the
workflow.

### Consumer config (Maven Central)

Central is in the default repository set of both Gradle and Maven, so no repository block or
credentials are needed.

Gradle (`build.gradle`):

```gradle
repositories { mavenCentral() }

dependencies {
    compileOnly 'io.github.dailystruggle:rtp-api:3.2.1'
    compileOnly 'io.github.dailystruggle:rtp-core:3.2.1'
}
```

Maven (`pom.xml`) - `compileOnly` maps to Maven `<scope>provided</scope>`:

```xml
<dependencies>
    <dependency>
        <groupId>io.github.dailystruggle</groupId>
        <artifactId>rtp-api</artifactId>
        <version>3.2.1</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>io.github.dailystruggle</groupId>
        <artifactId>rtp-core</artifactId>
        <version>3.2.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

This is the recommended dependency for out-of-repo addon authors (see
`docs/FOR_ADDON_DEVELOPERS.md` and `docs/ADDON_QUICKSTART.md`).

---

## Compatibility policy (to document with the first stable release)

- Semantic versioning for `rtp-api`/`rtp-core` independent of the plugin version.
- State clearly which `rtp-core` types are contract (e.g. `RTP.addShape`, `Shape`,
  `ConfigParser`) vs. internal-and-may-break, since `rtp-core` exposes more than `rtp-api`
  (see ADR-051 and `CHECKLIST-rtp-api-devux.md` Phase 2).
- Link the published Javadoc from `FOR_ADDON_DEVELOPERS.md`.
