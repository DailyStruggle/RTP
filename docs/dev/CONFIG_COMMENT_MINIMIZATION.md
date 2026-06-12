# Config Comment Minimization Plan

Companion worklist to [ADR-064](../adr/ADR-064-config-comment-format-summary-line-as-menu-hover.md)
and [`CONFIG_COMMENT_STYLE.md`](CONFIG_COMMENT_STYLE.md).

Goal: identify the shipped baseline YAML resources whose `#` comment blocks
should be brought into the ADR-064 two-part shape (standalone summary line +
prose detail + `# @...` directives), ordered so that **the locale translation
burden stays as small as possible**. Every comment line in a baseline file is
carried through the locale TSV pipeline into all shipped locales
(`cat, de, es, fr, it, ja, ko, nl, pl, pt, ru, zh`), so each line edited in a
baseline is a line a translator may have to re-touch. Fewer, shorter, stable
first lines == trivial translation updates.

## Scope and triviality model

Baseline files live under `rtp-plugin/src/main/resources/` (full edition) and
`rtp-plugin/src/lite/resources/` (lite variant). The locale tree carries:
`config.yml`, `economy.yml`, `effects.yml`, `integrations.yml`, `logging.yml`,
`messages.yml`, `metrics.yml`, `network.yml`, `performance.yml`, `regions.yml`,
`safety.yml`, `worlds.yml`.

A comment edit is **translation-trivial** when it does NOT change the meaning a
translator must convey:

- Adding / reordering `# @type`, `# @options`, `# @range`, `# @unit`,
  `# @default`, `# @source` directive lines. Directives stay **verbatim** across
  locales (never translated), so adding them is a zero-translation change.
- Moving an existing sentence to be the standalone first line, without rewording
  it. The TSV `preceding_comment` cell changes shape but the locale text is
  reusable.
- `version:` sentinel and file-header doc links: never translated.

A comment edit is **non-trivial** (forces real translation work in 13 locales)
when it rewords prose, splits one sentence into two, or adds new prose detail.
Prefer trivial edits; defer prose rewrites to dedicated, per-locale passes per
`TRANSLATION_GUIDE.md` section 8.

## Priority list (do these, in this order)

Ordering favors high payoff (file is menu-surfaced and heavily commented) with
low translation cost (directive-only / first-line-reorder edits).

| # | File | Comment lines | State vs ADR-064 | Minimal action | Translation cost |
|---|------|---------------|------------------|----------------|------------------|
| 1 | `performance.yml` | ~135 | Mostly conformant (summary + `@` directives already present) | Verify each option's first line is a standalone summary; add missing `@type`/`@range`/`@unit`. No prose rewrites. | Trivial (directive-only) |
| 2 | `economy.yml` | ~28 | Conformant | Spot-check only; already two-part with directives. | None expected |
| 3 | `metrics.yml` | ~24 | Conformant; uses prose "Accepted values:" alongside `@type: enum` | Add `@options: ["mean","max"]`; leave prose. | Trivial |
| 4 | `logging.yml` | ~18 | Terse one-liners, no directives | Add `# @type: boolean` per toggle; first lines already standalone summaries, keep wording. | Trivial (directive-only) |
| 5 | `integrations.yml` | ~3 | One shared block above many keys | Add a short standalone summary + `# @type: boolean` per `reroll*` key; reuse the existing sentence text. | Low (one sentence, reused) |
| 6 | `config.yml` | ~68 | Mixed | Reorder so first line is a summary; add directives. Avoid rewording prose. | Mostly trivial |
| 7 | `safety.yml` | ~110 | Mixed; list-valued options | Add directives (`@type: list<material>`, `@source: material`); keep prose. | Mostly trivial |
| 8 | `network.yml` | ~138 | Heavy prose; NOT key-translated (no `network.lang.yml`) | Tighten first lines + add directives; comments still flow to locales via TSV. | Low-to-medium |
| 9 | `messages.yml` | ~302 | User-facing **values** are the translated payload here, not comments | Do NOT bulk-touch. Only ensure section-header first lines read standalone. | High if reworded - avoid |

Files intentionally excluded:

- `language.yml`, `plugin.yml`: not part of the menu/locale comment pipeline
  (`plugin.yml` is the Bukkit manifest; `language.yml` is the bootstrap selector).
- `effects.yml`, `regions.yml`, `worlds.yml`: present in the locale tree but not
  under `resources/` root as standalone baselines here; treat opportunistically
  with the same rules when edited for substantive reasons.

## Working procedure (per file)

1. Edit the **baseline** file only (`src/main/resources/<file>.yml` and, if it
   differs, `src/lite/resources/<file>.yml`). Apply the ADR-064 shape with
   directive-only / first-line-reorder edits where possible.
2. Run the locale TSV pipeline so the change reaches every locale without hand
   editing locale YAML:
   ```powershell
   .\scripts\locale-files-to-csv.ps1
   .\scripts\reconcile-locale-csvs.ps1
   # translate only genuinely reworded prose in scripts\out\locale-<lang>.tsv
   .\scripts\locale-files-from-csv.ps1
   ```
3. Verify:
   ```powershell
   .\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"
   .\gradlew build
   ```

## Stay-on-task note

This is a transition worklist, not a mandate to bulk-reformat in one change.
Per the *Stay-On-Task Policy* and `CONFIG_COMMENT_STYLE.md` "When updating
existing files", reformat a file only when already editing it or as a dedicated,
isolated change. Delete this note once the priority list is exhausted.
