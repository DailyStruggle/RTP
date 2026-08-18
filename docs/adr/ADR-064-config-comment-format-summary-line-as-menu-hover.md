# ADR-064 — Config-Comment Format: Summary Line as Menu Hover Text

**Status:** Accepted (amended 2026-08-14)
**Date:** 2026-06-11

## Context

Every shipped YAML config file (`config.yml`, `safety.yml`, `performance.yml`,
`economy.yml`, `logging.yml`, `messages.yml`, per-region and per-world configs,
and the `lite` variants under `rtp-plugin/src/lite/resources/`) documents each
option with a `#` comment block. Those same comment blocks now serve a second,
non-cosmetic purpose: the generalized config menu (ADR-035 book-first menus,
ADR-044 command-tree reflector) surfaces the YAML block comment of a parameter
as its **hover tooltip**. The lookup path is
`RtpYamlSection#getComment(key)` → `ConfigMenuConsumerProfile#commentLookup`
→ `CommandTreeMenuBuilder#resolveParamHover`.

Two pressures result:

1. **The first comment line is load-bearing.** In a menu tooltip the first line
   reads as the option's title/summary; the admin scans it before (or instead
   of) the rest of the block. A comment whose first line is a sentence fragment,
   a bare `@type:` directive, or a detail that only makes sense after later
   lines produces a confusing hover.
2. **Comments must stay machine-parseable.** The menu also discovers an option's
   type, range, and valid values from structured `# @…` directive lines without
   re-parsing English prose, and the locale TSV pipeline
   (`scripts/locale-files-*.ps1`) carries every comment block through 13 locales.
   Free-form, inconsistent comment shapes break both.

A detailed style guide already exists at
[`docs/dev/CONFIG_COMMENT_STYLE.md`](../dev/CONFIG_COMMENT_STYLE.md), accreted
from prior cleanup passes. What was missing was an architectural record that
(a) fixes the format as a decision rather than a convention that can quietly
drift, and (b) names the first-line-as-hover constraint explicitly.

## Decision

Adopt a fixed two-part comment template for every documented config option, with
[`docs/dev/CONFIG_COMMENT_STYLE.md`](../dev/CONFIG_COMMENT_STYLE.md) as the
canonical, example-rich style guide and this ADR as the governing decision.

The template, immediately above the key it documents (no blank line between):

```yaml
# <Summary line: one line, capitalized, says what the option does, <= ~80 chars>
# <Optional prose detail lines: units, side effects, dependencies, caveats>
# @type: <enum|integer|number|boolean|string|material|biome|world|tag|list<...>>
# @options: [...]      # enum only; mutually exclusive with @source
# @range: [min, max]   # integer/number only; null = open-ended
# @unit: <seconds|ticks|blocks|chunks|percent|bytes|ms>
# @default: <scalar>   # only when shipped value differs from default
# @source: <registry>  # runtime-sourced values; mutually exclusive with @options
optionKey: value
```

Binding rules:

1. **First line is the hover summary.** The first comment line shall be a single,
   standalone, capitalized clause stating what the option does, readable on its
   own as a menu tooltip title. It shall not begin with a `# @…` directive, a
   sentence continued from an absent prior line, or a detail that is meaningless
   without later lines.
2. **Prose detail lines** follow the summary and carry the information an admin
   needs to set the value (units, ranges expressed in prose, interactions,
   S-00x touchpoints, admin-doc pointers).
3. **`@…` directive lines** are the single machine-readable source of truth for
   type/options/range/unit/default/source, per the parsing contract in
   `CONFIG_COMMENT_STYLE.md`. Prose shall not duplicate what a directive states.
4. **File header** (first block of each file) is a title plus an admin-doc link,
   distinct from the first option's summary line.
5. **Version sentinel** keeps its canonical `# DO NOT TOUCH VERSION NUMBER`
   wording.
6. **Locale parity.** Comment edits to a baseline file flow through the locale
   TSV pipeline (`locale-files-to-csv` → `reconcile-locale-csvs` → translate the
   `preceding_comment` column → `locale-files-from-csv`), never by hand-editing
   `lang/<locale>/*.yml`. Directive lines and doc-tags stay verbatim across
   locales; only the summary/prose lines are translated.

Scope: admin-facing shipped YAML resources only. Java/Kotlin code comments and
KDoc are unaffected. Reformatting an existing file's comments is a dedicated
change, not bundled with behavior changes (per the *Stay-On-Task Policy*).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Leave the convention informal in `CONFIG_COMMENT_STYLE.md` only | A style doc with no ADR drifts; the first-line-as-hover coupling is an architectural contract between the config files and the menu reflector, not a cosmetic preference. |
| Render the full block in hover, ignore first-line discipline | The tooltip leads with whatever the first line happens to be; fragments and bare directives produce confusing titles. The reader cost is paid every time the menu opens. |
| Add a dedicated `# @summary:` / `# @hover:` directive instead of using line 1 | Adds redundant text to every option, duplicates the prose summary, and another field to keep in locale parity; the existing first line already serves the purpose. |
| Move hover text into `messages.yml` keys per option | Hundreds of new keys to translate and keep in sync with the config tree; defeats the point of co-locating documentation with the option. |

## Consequences

- **Positive:** Config-menu tooltips have predictable, readable titles; comment
  blocks remain machine-parseable for the menu and any future config tooling;
  the format is now a recorded decision that lint/review can cite.
- **Positive:** Translators have a stable shape to work against; the summary line
  is the obvious unit to translate first.
- **Negative / Trade-offs:** Authoring a new option costs slightly more
  discipline (summary line must stand alone). Existing files are not retrofitted
  in bulk by this ADR — they are brought into line opportunistically as options
  are edited, so heterogeneity persists during the transition.

## Amendment (2026-08-14): directives drive finite-value pickers

When this ADR was accepted, the `# @…` directive lines were consumed only by
build-time tooling (the locale TSV pipeline) and by the hover-summary contract;
no runtime code parsed them. The approved proposal
[`docs/dev/scratch/PROPOSAL-config-menu-options-picker.md`](../dev/scratch/PROPOSAL-config-menu-options-picker.md)
(D-005, approved 2026-08-14) makes the in-game config editor the **first runtime
consumer** of the directive tags, resolving the earlier issue that finite-domain
keys (e.g. `shape.name`, `database.type`) accepted confusing arbitrary free-text
input.

This amendment records the following, without changing the comment format itself:

1. **Runtime directive consumer.** A small in-memory directive parser reads the
   `@type` / `@options` / `@source` lines from the already-cached YAML block
   comment (`ConfigParser#getYamlRoot` → `RtpYamlSection#getComment`, the same
   in-memory path `resolveConfigHover` uses — no new file I/O). When a key
   declares a finite domain, the config menu routes it to a generic option
   picker instead of the free-text anvil prompt; keys with no directives (or an
   unparseable directive line) fall back to free text and never break the menu.
2. **`@source` registry vocabulary extended.** In addition to the existing
   runtime registries (`material`, `biome`, `tag`), the config editor resolves
   `@source: shape` / `@source: vert` (from `RTP.factoryMap` —
   `ShapeParameter.values()` / `VertParameter.values()`) and the live
   server-derived registries `@source: world`
   (`RTP.serverAccessor.getRTPWorlds()`) and `@source: region`
   (`RTP.selectionAPI.regionNames()`). The latter two are the "server value"
   case: values that are not static and change at runtime, yet are not authored
   by this plugin — so the picker enumerates them live on each menu open rather
   than baking a fixed `@options` list that would immediately drift. This
   annotates the region/world discriminator keys (`world`, region redirect
   `override`, per-world `region`, per-region `override`) in the shipped region
   and world templates. Using `@source` keeps every finite domain authoritative
   against its registry, preventing drift that a hardcoded `@options` list
   would risk. `@options` and `@source` remain mutually exclusive.
3. **No format change.** The two-part template, first-line-as-hover rule, and
   locale-parity handling of directive lines are unchanged. `@options` /
   `@source` continue to be the single machine-readable source of truth for a
   key's valid values; the menu now honors that source of truth at runtime.

The amendment is additive: existing files remain valid, and finite keys are
annotated with directives incrementally (un-annotated finite keys keep free text
until annotated).

## References

- [`docs/dev/CONFIG_COMMENT_STYLE.md`](../dev/CONFIG_COMMENT_STYLE.md) — canonical style guide (examples, parsing contract, layout).
- [ADR-035](ADR-035-interactive-menus-book-first.md) — book-first interactive menus.
- [ADR-044](ADR-044-command-tree-menu-reflector.md) — command-tree menu reflector and hover-text resolution (section 4).
- [ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md) — locale-aware ConfigParser / YAML baseline.
- `rtp-core/.../commands/menu/ConfigMenuConsumerProfile.java`, `CommandTreeMenuBuilder#resolveParamHover` — hover source.
- `scripts/locale-files-to-csv.ps1`, `scripts/reconcile-locale-csvs.ps1`, `scripts/locale-files-from-csv.ps1` — locale comment pipeline.
- [`docs/dev/scratch/PROPOSAL-config-menu-options-picker.md`](../dev/scratch/PROPOSAL-config-menu-options-picker.md) — approved D-005 proposal introducing the runtime directive-driven picker (this amendment).
