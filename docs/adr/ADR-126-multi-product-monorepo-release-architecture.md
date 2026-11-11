# ADR-126 - Multi-Product Monorepo Release Architecture (`RTP`: Real-Time Process / Program for Minecraft)

**Status:** Accepted
**Date:** 2026-10-27
**Extends:** [ADR-108](ADR-108-all-mit-licensing-pro-as-support.md), [leafrtp-ADR-087](../../resource/LeafRTP/docs/adr/leafrtp-ADR-087-release-cadence-contractual-pro-discretionary-lite.md)

## Context

The `DailyStruggle/RTP` repository has evolved from a single plugin into the **Real-Time Process / Real-Time Program (`RTP`) for Minecraft** ecosystem, backed by the private `LeafCraftDev/MC` (`main` branch) monorepo trunk: an umbrella workspace hosting 17 platform-neutral real-time SDK modules (`api/*`, `rtp-api`, `rtp-core`, `rtp-proxy-common`), multiple standalone multi-loader resources (`resource/LeafRTP`, `resource/LeafTreeGen`, `resource/LeafSleep`, `resource/LeafSleuth`, `resource/LeafConfig`, `resource/LeafArtifacts`, `resource/LeafShapeMining`), standalone and bundled addons (`addons/*`), and verification harnesses (`helpers/*`).

Retaining the `DailyStruggle/RTP` repository coordinate (`V1`, `V2`, `V3`) while framing `RTP` as *Real-Time Process / Real-Time Program* preserves every existing Git remote, documentation link, Maven Central SCM URL, and JitPack coordinate (`com.github.DailyStruggle.RTP:<module>:<tag>`). However, GitHub's native release model is repository-scoped:

1. **Flat tag namespace:** Git tags live in a single repository-wide namespace (`refs/tags/*`), and every GitHub Release is bound 1-to-1 to a Git tag. Two products in the same repository cannot both tag `v1.0.0`.
2. **Single `Latest` pointer:** GitHub maintains only one `Latest` release badge and `/releases/latest` redirect per repository. If a sibling plugin workflow publishes a release with `make_latest: true` (the GitHub API and `softprops/action-gh-release` default), `/releases/latest` on `DailyStruggle/RTP` points to that sibling plugin instead of `LeafRTP`.
3. **Downstream polling ambiguity:** Release consumers that query `/repos/DailyStruggle/RTP/releases` (such as `discord-support-bot`) must distinguish between `LeafRTP` releases (`v3.3.1` / `leafrtp-v3.3.1`) and sibling product releases (`leaftreegen-v0.2.0`, `leafsleep-v1.0.0`).
4. **Independent lifecycles:** Multi-loader resources under `resource/*` have their own semantic version lines and changelogs (`resource/LeafTreeGen/CHANGELOG.md`), and releasing a sibling resource must not require bumping `LeafRTP` or triggering Maven Central SDK publication.

## Decision

1. **Umbrella Repository Identity (`RTP` = Real-Time Process / Program) & `resource/` Layout:**
   - All multi-loader plugins and mods in the monorepo reside under `resource/<Product>/` (`resource/LeafRTP`, `resource/LeafTreeGen`, `resource/LeafSleuth`, `resource/LeafConfig`, `resource/LeafArtifacts`, `resource/LeafShapeMining`, `resource/LeafSleep`).

2. **Component-Prefixed Git Tags (`<product>-v<semver>`):**
   - Nested plugins, addons, and tools published from the monorepo shall use hyphen-delimited component-prefixed Git tags of the form `<product>-v<semver>` (for example, `leaftreegen-v0.2.0`, `leafsleuth-v0.1.0`, `leafconfig-v0.1.0`, `leafsleep-v1.0.0`, `stresstest-v1.0.0`) and product-titled GitHub Releases (`LeafTreeGen 0.2.0`, `LeafSleuth 0.1.0`, `LeafConfig 0.1.0`, `LeafSleep 1.0.0`).
   - Hyphens (`<product>-v<semver>`) shall be used instead of slashes (`<product>/v<semver>`) so tag refs remain flat under `.git/refs/tags/`, URL-safe across HTTP routers, and compatible with JitPack and artifact filename conventions.
   - `LeafRTP` (`leafrtp`) retains bare `v<semver>` tags (e.g. `v3.3.1`) as its primary tag format for backward compatibility with existing JitPack consumers and [leafrtp-ADR-087](../../resource/LeafRTP/docs/adr/leafrtp-ADR-087-release-cadence-contractual-pro-discretionary-lite.md), while release discovery tooling accepts both `v<semver>` and `leafrtp-v<semver>`.

3. **Explicit `make_latest` Invariant on GitHub Releases:**
   - Only the flagship `LeafRTP` release workflow (`.github/workflows/release.yml`) shall set `make_latest: true` (for non-prerelease versions) so `https://github.com/DailyStruggle/RTP/releases/latest` deterministically resolves to `LeafRTP`.
   - All nested plugin, addon, and helper release workflows (`.github/workflows/release-sleep.yml`, `.github/workflows/release-treegen.yml`, `.github/workflows/release-sleuth.yml`, `.github/workflows/release-config.yml`) shall explicitly set `make_latest: false` on `softprops/action-gh-release`.

4. **Per-Product Version, Changelog, Workflow Isolation, and Option A Bidirectional OSS Sync:**
   - Each multi-loader resource lives under `resource/<Product>/` (`../../resource/LeafRTP` for `LeafRTP` modules `rtp-api`, `rtp-core`, `rtp-plugin`, and `addons`; `resource/LeafSleep/` for `LeafSleep` / `OnePlayerSleep`; `resource/LeafTreeGen/` for `LeafTreeGen`, etc.). In the private `LeafCraftDev/MC` monorepo (`mc` remote, branch `main`), all resources build and navigate in a single workspace.
   - `scripts/release/sync_public_oss_pr.py` (enforced by `.git/hooks/pre-push` and `scripts/pre_push_guard.py`) projects Option A per-resource public OSS repositories (`--target rtp` -> `DailyStruggle/RTP:V3`; `--target sleep` -> `LeafCraftDev/LeafSleep:main`; `--target treegen` -> `LeafCraftDev/LeafTreeGen:main`) that share overarching `docs/` (`docs/dev/*`, `docs/adr/ADR-*.md`) while exporting only that target's `resource/<Product>/**` tree (including its `<product>-ADR-*.md` and `REQUIREMENTS.md`). By default (`--sdk-mode maven`), the shared `api/*` SDK modules are published as Maven artifacts (`io.github.dailystruggle:<module>:<version>`, with `-sources.jar`, `-javadoc.jar`, and `META-INF/leaf-sdk/<module>/` docs) from `LeafCraftDev/MC` (`main`), consumed/shaded/resupplied via Maven coordinates in the projected repositories, and their Markdown guides (`api/**/*.md`) are projected to `docs/sdk/` alongside a generated `docs/sdk/BUNDLED_SDK.md` catalog (`--import-pr` maps `docs/sdk/` edits and Gradle coordinates back to `api/` and `project(':<module>')` in `LeafCraftDev/MC`).
   - Sibling resources under `resource/<Product>/` own their version in their leaf `build.gradle` (e.g. `resource/LeafTreeGen/treegen-plugin/build.gradle`) and their release notes in `resource/<Product>/CHANGELOG.md`, while `LeafRTP` (`../../resource/LeafRTP`) keeps its flat Gradle coordinates (`:rtp-api`, `:rtp-core`, `:rtp-plugin`, `:addons:*`) via `projectDir` mappings in `settings.gradle`.
   - Each nested resource has a dedicated release workflow (`.github/workflows/release-<product>.yml`, alongside `.github/workflows/release.yml` and `release-bbb.yml` for `LeafRTP`) triggered by pushing a matching `<product>-v*.*.*` tag or via `workflow_dispatch`.
   - Every product release workflow in the monorepo enforces the same supply-chain guarantees as `LeafRTP`: in-memory PGP `.asc` signing (`SIGNING_KEY`), `.sha256` and `.sha512` checksums, and GitHub SLSA build provenance (`actions/attest-build-provenance`).

5. **Tag-Prefix Release Discovery in `discord-support-bot`:**
   - `projects.<project>.release` in `discord-support-bot/support-bot.yml` and `PluginReleaseAndStatusManager.fetch_latest_release()` support an optional `github_tag_prefix` filter (a prefix string or list of prefix strings, matched with a version digit guard right after the prefix).
   - When `github_tag_prefix` is configured, `fetch_latest_release()` verifies `/releases/latest` against the allowed prefix(es) and falls back to scanning `/repos/{gh_repo}/releases?per_page=30` for the newest release whose `tag_name` matches the product's prefix and strips that prefix to extract the clean version string.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Separate source repositories per plugin | Duplicates the 17-module `api/*` SDK, Gradle conventions, CI gates, and signing secrets across repositories; forces cross-repo version bumps for shared SPI changes. |
| Slash-separated tags (`leaftreegen/v0.2.0`) | Creates nested directories under `.git/refs/tags/`, breaks URL path segments that expect a single token, and complicates shell/artifact filename handling. |
| Leaving `make_latest` at default (`true`) on all workflows | Whichever plugin was released most recently would overwrite `/releases/latest` for the entire `DailyStruggle/RTP` repository. |
| Automated commit-bot release PRs (`release-please` / `changesets`) | Conflicts with curated Keep-a-Changelog authoring and `ADR-117` contractual Pro vs. discretionary Lite cadence. |

## Consequences

- **Positive:**
  - Multiple plugins (`leafrtp`, `leaftreegen`, `leafsleep`, etc.) ship distinct, signed, provenance-attested releases from one repository using a single set of repository secrets (`SIGNING_KEY`, `MODRINTH_TOKEN`, `HANGAR_TOKEN`, `DISCORD_BOT_TOKEN`).
  - `/releases/latest` on `DailyStruggle/RTP` never drifts away from `LeafRTP`.
  - `discord-support-bot` resolves per-plugin releases accurately whether a plugin publishes to `DailyStruggle/RTP` (via `github_tag_prefix`) or to a dedicated mirror repository.
  - Existing JitPack coordinates and `ADR-117` workflows continue to work without breaking changes.
- **Negative / Trade-offs:**
  - The chronological `/releases` feed on `DailyStruggle/RTP` lists releases across all monorepo products, distinguished by release title (`rtp-lite 3.3.1`, `LeafTreeGen 0.2.0`) and tag prefix (`v3.3.1`, `leaftreegen-v0.2.0`).

## References

- `.github/workflows/release.yml`, `.github/workflows/release-bbb.yml`, `.github/workflows/release-treegen.yml`.
- `resource/LeafTreeGen/treegen-plugin/build.gradle`, `resource/LeafTreeGen/CHANGELOG.md`.
- `discord-support-bot/discord_support_bot.py`, `discord-support-bot/support-bot.yml`.
- [`docs/dev/PUBLISHING.md`](../dev/PUBLISHING.md).
- [ADR-108](ADR-108-all-mit-licensing-pro-as-support.md), [leafrtp-ADR-087](../../resource/LeafRTP/docs/adr/leafrtp-ADR-087-release-cadence-contractual-pro-discretionary-lite.md).
