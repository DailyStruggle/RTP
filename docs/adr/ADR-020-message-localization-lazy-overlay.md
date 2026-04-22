# ADR-020 — Message Localization via Lazy Folder-Per-Locale Overlay

**Status:** Proposed
**Date:** 2026-04-21

## Context

REQ-RTP-F-013 requires all user-facing messages to be configurable via `messages.yml`, and S-007 requires configurable "busy" and "invalid command" messages. Today the plugin ships a single English `messages.yml` resource extracted by `ConfigParser<MessagesKeys>` in `Configs.java` (line ~211). No mechanism exists to ship additional default translations.

A secondary indirection already exists: `rtp-plugin/src/main/resources/lang/messages.lang.yml` maps each internal `MessagesKeys` code constant (left side) to the user-visible YAML key name (right side) that appears in `messages.yml`. Today the mapping is an identity (e.g., `teleportMessage: teleportMessage`), but the layer already supports renaming a key — editing the right-hand side changes the YAML key the plugin reads in `messages.yml` without touching code. This enables, for example, `value: true` in `messages.yml` to be consumed as the internal key `asdf` by mapping `asdf: value` in `messages.lang.yml`. Any localization scheme must integrate with — not duplicate — this existing layer.

Community contributors and server operators have expressed interest in shipping default translations for common locales (e.g., `es`, `de`, `fr`, `zh`). Any localization scheme must:

- Impose **zero runtime or disk cost** on servers that stay on English (the overwhelming majority).
- Survive **partial translations** — a community `pt/messages.yml` missing some keys must not break the plugin.
- Preserve today's **on-disk editability** — admins can open and customize the extracted file.
- Keep **log output English-only**, so support tickets and CI remain grep-friendly.
- Cross module boundaries cleanly: translations ship from `rtp-plugin` resources, but the loader lives in `rtp-core`. Per `AGENTS.md`, this crosses a module boundary and warrants an ADR before implementation.

Prior art considered:

- Bukkit-idiomatic flat suffix layout: `messages_es.yml`, `messages_de.yml`.
- Eager loading of every shipped locale at startup.
- Bundling all locales into one multi-key YAML (`es:`, `de:` sub-trees).

## Decision

Adopt a **folder-per-locale overlay** with **lazy loading**, layered on top of the existing `.lang.yml` key-name remap:

1. **Baseline.** The English `messages.yml` remains the authoritative key set and the only file loaded by default. Its location and loader are unchanged. The existing `lang/messages.lang.yml` continues to map `MessagesKeys` code constants → YAML key names for the baseline file.
2. **Locale files.** Additional locales ship in the jar under `rtp-plugin/src/main/resources/lang/<locale>/` as a **pair**:
   - `lang/<locale>/messages.yml` — the translated message text.
   - `lang/<locale>/messages.lang.yml` — *optional* per-locale key-name remap, identical in format to the baseline `lang/messages.lang.yml`. When absent, the plugin reuses the baseline remap (identity by default). When present, its right-hand values define the YAML keys the plugin reads from the sibling `messages.yml`.

   The folder name is the ISO 639-1 locale code, optionally with region: `pt`, `pt_BR`, `zh_CN`.
3. **Configuration key.** `config.yml` gains a `language:` key. The default value is `en`. The reserved value `auto` is recognized but treated as `en` by this ADR; a future ADR may redefine `auto` as per-player locale resolution.
4. **Lazy resolution.** When `language` is unset, blank, `en`, or `auto`, the plugin performs **no** locale file I/O.
5. **Overlay semantics.** When `language` is any other value, the plugin:
   a. Extracts `lang/<locale>/messages.yml` (and `lang/<locale>/messages.lang.yml` if bundled) from the jar to `plugins/RTP/lang/<locale>/` if absent on disk (mirroring today's extract-if-missing behavior for `messages.yml` / `lang/messages.lang.yml`).
   b. Resolves the **effective key-name map** for the locale: the locale's `messages.lang.yml` if present, otherwise the baseline `lang/messages.lang.yml`. This map is used to look up entries in the locale's `messages.yml` — so `asdf: value` in the locale's `messages.lang.yml` means `MessagesKeys.asdf` reads the YAML key `value` from the locale's `messages.yml`.
   c. Parses `messages.yml` through that key-name map and registers the resulting `MessagesKeys → String` table as an **overlay** on the baseline parser: per-key lookup returns the locale value if present, otherwise the English baseline value.
   d. If the requested locale directory exists neither in the jar nor on disk, logs a `WARNING` (English) and silently falls back to the English baseline — never fails startup. A missing `messages.lang.yml` inside a present locale folder is **not** a warning; the baseline map is used.
6. **Hot reload.** `/rtp reload` re-evaluates `language:` and rebuilds the overlay.
7. **Log language invariance.** All `RTP.log(...)` output remains English regardless of `language:`. Localization applies only to player-facing messages routed through `MessagesKeys` lookups.

The loader change is confined to one seam in `rtp-core` (the `MessagesKeys` parser construction site in `Configs.java`) plus a new small `LocaleOverlay` helper. No changes to `rtp-api`, command dispatch, or platform adapters are required.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Flat suffix layout `messages_<locale>.yml` at resource root | Clutters the plugin data folder as locales multiply; does not scale when future files (`help.yml`, etc.) also need translation; splits convention away from the existing `lang/` directory. |
| Eager-load every shipped locale at startup | Imposes I/O and memory cost on English servers (the majority) for no benefit; complicates cold-start latency budgets on Folia. |
| Single multi-locale YAML (`es: { ... }, de: { ... }`) in one file | Forces full-file re-download for any single-locale edit; makes community PRs for one locale touch a shared file; harder for admins to hand-edit one language without risking another. |
| Bypass `.lang.yml` and hard-code YAML key names in locale files | Duplicates an already-working indirection; prevents admins from renaming YAML keys per locale; inconsistent with the baseline loader and breaks the `value → asdf` style of key-name customization the existing layer supports. |
| Runtime download of locale files from a CDN | Introduces a network dependency at startup; incompatible with air-gapped servers; out of scope for a "default languages" feature. |
| Per-player locale via `player.getLocale()` | Higher implementation cost (every `SendMessage` call site becomes locale-aware); deferred to a future ADR. The `language: auto` token is reserved here to enable that evolution without a config migration. |

## Consequences

- **Positive:**
  - Zero cost for English servers — no extra file extraction, parse, or memory.
  - Partial translations are first-class; missing keys transparently fall back to English.
  - Community contributors can submit a single-folder PR (`lang/<locale>/messages.yml`) without touching shared files.
  - Future localization of additional files (e.g., `lang/<locale>/help.yml`) drops into the same structure.
  - Reversible: removing all locale folders and the `language:` key returns the plugin to current behavior.
  - Supports REQ-RTP-F-013 and S-007 without weakening either.

- **Negative / Trade-offs:**
  - Adds one indirection (overlay lookup) on the message resolution path; negligible compared to the surrounding `ConfigParser` lookup cost.
  - Extraction creates an extra directory in the plugin data folder per active locale.
  - Log output deliberately stays English, which some operators may find inconsistent with localized player messages; this is an explicit, documented trade-off for supportability.
  - Introduces a new file-format contract (locale files must share the `MessagesKeys` key set) that community contributors must follow. Mitigated by a dev-only completeness test.

## References

- REQ-RTP-F-013 in [`docs/dev/REQUIREMENTS.md`](../dev/REQUIREMENTS.md) — Configurable User Messages.
- S-007 in [`docs/dev/REQUIREMENTS.md §3`](../dev/REQUIREMENTS.md) — Configurable "busy" / "invalid command" messages.
- [`docs/dev/TRACEABILITY.md`](../dev/TRACEABILITY.md) row `REQ-RTP-F-013` — current implementing class `ConfigParser`.
- [`rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/Configs.java`](../../rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/Configs.java) — `MessagesKeys` parser construction site.
- [`rtp-api/src/main/java/io/github/dailystruggle/rtp/api/configuration/enums/MessagesKeys.java`](../../rtp-api/src/main/java/io/github/dailystruggle/rtp/api/configuration/enums/MessagesKeys.java) — authoritative key set.
- [`rtp-plugin/src/main/resources/lang/messages.lang.yml`](../../rtp-plugin/src/main/resources/lang/messages.lang.yml) — existing baseline key-name map; reused when a locale omits its own `messages.lang.yml`, and served as the format template for per-locale overrides.
- Execution plan: [`docs/dev/MESSAGE_LOCALIZATION_PLAN.md`](../dev/MESSAGE_LOCALIZATION_PLAN.md).
