# ADR-020 — Language Bootstrap and Locale-Aware ConfigParser

**Status:** Accepted
**Date:** 2026-04-26

## Context

An earlier design used a `LocaleOverlay` helper that ran *after* `config.yml` was parsed: the active locale was read from `ConfigKeys.language`, then a per-key overlay was layered on top of `MessagesKeys` (and, in practice, expanding to other parsers). This produced a working translation pipeline but carried two structural costs:

1. **Bootstrap order coupling.** Because the locale lived inside `config.yml`, no parser could be locale-aware while it was being constructed. Every parser had to be built in English first, then post-processed. This made `config.yml` itself untranslatable in any clean way and forced the overlay to retrofit translations onto already-loaded parsers.
2. **Overlay complexity.** `LocaleOverlay` accreted comparison-against-English-default logic, "user modification" detection, and key-rename cleanup paths to reconcile a locale file with a parser that had already been populated from English defaults. Each of these branches existed only because the locale arrived too late.

The plugin is in beta 1 with no installed users, so a one-time format change is acceptable.

## Decision

Resolve the active locale **before any `ConfigParser` is constructed**, and make `ConfigParser` natively locale-aware:

1. **Dedicated `language.yml` at the plugin root.** A single key, `language: en`, lives in `plugins/RTP/language.yml`. This is the *only* place the active locale is read from at runtime.
2. **`LanguageBootstrap` (new, in `rtp-core/.../configuration/`).** A small dependency-free YAML reader that:
   - Opens `language.yml`, returns a sanitized locale string (same `[A-Za-z0-9_-]+` guard previously used by `LocaleOverlay`).
   - Creates the file with default `en` on first run.
   - Has no dependency on `ConfigParser`, `Configs`, or any other plugin state — it is callable at the very first step of `Configs.reloadConfigs()`.
3. **Locale-aware `ConfigParser`.** Each parser receives the locale at construction and loads its file directly:
   - `lang/<locale>/<name>.yml` from the jar (extract-if-missing) when `locale != "en"`.
   - `lang/<locale>/<name>.lang.yml` for the key-name remap, with English fallback.
   - English path is unchanged — zero overhead when `language: en`.
4. **`LocaleOverlay` is removed.** The "compare-to-English-default", "detect user modification", and "rename old YAML keys" branches all vanish: the locale-specific YAML *is* the source of truth, not an overlay on top of an English baseline.
5. **`ConfigKeys.language` is removed.** The `language:` key is stripped from the bundled `config.yml` resource and replaced with a pointer comment directing operators to `language.yml`.

Hot reload (`/rtp reload`) re-reads `language.yml` and reconstructs every parser with the resolved locale. Log output remains English regardless of locale.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep the previous lazy overlay, fix only the comments | Does not eliminate the bootstrap-order coupling that prevents `config.yml` itself from being translated; preserves the comparison-and-merge complexity of `LocaleOverlay`. |
| Two-phase reload: parse `config.yml` for `language`, then re-parse everything | Effectively the same overhead as overlay; doubles the parse work; still leaves `config.yml` itself stuck in English for the first phase. |
| Embed `language` as a JVM system property or env var | Hostile to operator workflow; conflicts with the file-based configuration convention. |
| Put `language` in `config.yml` and *also* in `language.yml` with `language.yml` winning | Two sources of truth for one setting; invites drift. Beta-1 status removes any compatibility argument for keeping the `config.yml` key. |

## Consequences

- **Positive:**
  - `LocaleOverlay` and `ConfigKeys.language` are gone — net code reduction in `rtp-core`.
  - `ConfigParser` is the only YAML loader; no parallel overlay loader to maintain.
  - Every config file (including `config.yml`) is fully translatable through the standard `lang/<locale>/<name>.yml` mechanism.
  - Bootstrap order is linear: read locale → build parsers → done. No retrofit step.
  - `/rtp reload` semantics are unchanged from the operator's perspective.

- **Negative / Trade-offs:**
  - One additional file (`language.yml`) at the plugin root.
  - Behavior change: editing `language` in `config.yml` no longer has any effect — operators must edit `language.yml`. Acceptable in beta 1 with no installed users; documented in `CHANGELOG.md`.
  - `LanguageBootstrap` duplicates a tiny amount of YAML parsing (one key) outside `ConfigParser`. Unavoidable: the full `ConfigParser` depends on the locale being known.

## References

- REQ-RTP-F-013 in [`docs/dev/REQUIREMENTS.md`](../dev/REQUIREMENTS.md) — Configurable User Messages.
- S-007 in [`docs/dev/REQUIREMENTS.md section 3`](../dev/REQUIREMENTS.md) — Configurable "busy" / "invalid command" messages.
- [`docs/dev/TRACEABILITY.md`](../dev/TRACEABILITY.md) row `REQ-RTP-F-013`.
- [`rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/LanguageBootstrap.java`](../../rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/LanguageBootstrap.java) — bootstrap reader.
- [`rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/ConfigParser.java`](../../rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/ConfigParser.java) — locale-aware loader.
- [`rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/Configs.java`](../../rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/Configs.java) — `reloadConfigs` entry point.
