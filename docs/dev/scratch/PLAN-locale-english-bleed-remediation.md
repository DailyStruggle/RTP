# Plan: Resolve English Bleed in Shipped Locales

Working note. Delete once the remediation campaign is complete.

## Problem

Many baseline keys across `messages.yml`, `safety.yml`, `performance.yml`, `config.yml`, `logging.yml`, `integrations.yml`, `economy.yml`, and `metrics.yml` have been added or rewritten over time and the reconcile step (`scripts/reconcile-locale-csvs.ps1`) correctly seeded the new rows from the English baseline. Step 4 of the *Locale Config TSV Pipeline* in `.junie/AGENTS.md` (the translation step) has been repeatedly skipped, leaving the seeded English values in the locale TSVs and therefore in `lang/<locale>/<file>.yml`. The "Generalized Menu Framework" section in `messages.yml` is one acute case; the broader symptom is much larger.

## Current Bleed (as of this snapshot)

Counts below are rows in `scripts/out/locale-<lang>.tsv` whose `value` column is byte-identical to the matching English baseline value (i.e. an unmistakable un-translated row). Joins on `(filename, parent_path, base_key, index)`. Empty values and rows with no English baseline are excluded.

Per locale (% is English/non-empty):

```
pl   369 nonempty   349 still English   94.6%
de   361 nonempty   268 still English   74.2%
fr   361 nonempty   262 still English   72.6%
cat  232 nonempty   156 still English   67.2%   (note: cat is Internet-Cat dialect, not a Romance lang)
nl   238 nonempty   155 still English   65.1%
ja   232 nonempty   145 still English   62.5%
ko   232 nonempty   145 still English   62.5%
zh   232 nonempty   145 still English   62.5%
pt   230 nonempty   132 still English   57.4%
es   232 nonempty   131 still English   56.5%
ru   230 nonempty   127 still English   55.2%
it   238 nonempty   128 still English   53.8%
```

By file (summed across all locales):

```
messages.yml           925
safety.yml             386
performance.yml        224
config.yml             188
logging.yml            168
integrations.yml       108
economy.yml             84
metrics.yml             60
```

Total bleed: ~2143 rows. Note: `cat` should be Cat-dialect-translated, not skipped; see *Domain Analogies & Aliases* in `.junie/AGENTS.md`.

## Constraints (must hold)

1. **No hand-edits to `lang/<locale>/*.yml` or `*.lang.yml`.** The TSV pipeline is the source of truth. Hand-edits will be wiped on the next reconcile.
2. **One locale at a time** beats one ten-locale shot for quality (`TRANSLATION_GUIDE.md` §8).
3. **Mask placeholders, color codes, doc-tags** before machine-translating:
   - `[placeholder]` segments (e.g. `[player]`, `[command]`, `[type]`, `[values]`).
   - Color codes: `&a`, `&l`, etc., and hex `#RRGGBB` sequences.
   - Section markers `§`.
   - Doc-tags: `@type`, `@range`, `@unit`, `@default`, `@options` (these must remain verbatim in comments).
   - URLs, version banners, file names.
4. **UTF-8, no BOM, LF line endings.** Existing TSVs contain pre-existing mojibake (e.g. `MenÃ¼` instead of `Menü` in `locale-de.tsv`). Fix mojibake on rows you touch, do not sweep otherwise (per `Markdown Encoding Hygiene`).
5. **`@type`/`@range`/`@unit`/`@default`/`@options` lines in `preceding_comment` must not be translated.** Only the prose around them.
6. **Pre-existing native translations are precious.** Do not overwrite a non-English value just because it differs from the baseline. The audit specifically looks for value == English-baseline.

## Strategy

Translation will happen out-of-band in a separate AI session (user preference: faster interface). To make that safe and resumable, we need three deterministic helpers around the existing TSV pipeline:

1. **Auditor**: enumerate every row whose value is byte-identical to the English baseline value, exporting a small focused TSV per (locale, file) — the *Bleed Manifest*.
2. **Bundle Exporter**: package one locale's Bleed Manifest as a self-contained input file for an external AI session. Format: a markdown document containing the English baseline value, the comment block, the `base_key`/`parent_path`/`index` coordinates, and an empty `# locale_value:` field for the translator to fill. Placeholders/color codes pre-masked with sentinels `<<P0>>`, `<<P1>>`, …; a sentinel legend is printed at the top so the translator does not corrupt them.
3. **Bundle Importer**: read the translator's filled bundle back, unmask sentinels, validate that every placeholder/color code was preserved, and patch the corresponding rows in `scripts/out/locale-<lang>.tsv` in place. Validation rules:
   - Same set of `[placeholder]` tokens.
   - Same set of `&x` color codes and hex codes (order-insensitive).
   - Same trailing/leading whitespace policy as the baseline value.
   - Non-empty `locale_value` (empty = leave row English, mark `# TODO(i18n):`).
   - Mojibake check: refuse to import rows containing `â€`, `Â`, `Ã`, `âœ`, `âŒ`, `ðŸ`, or `\uFFFD`.

After bundle import for a locale:
- Run `.\scripts\reconcile-locale-csvs.ps1` (sanity round-trip).
- Run `.\scripts\locale-files-from-csv.ps1` to regenerate `lang/<locale>/*.yml`.
- Run `.\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"`.
- Run `.\gradlew build`.

## Sequencing (one locale per PR, large-bleed first)

Priority order picks worst-bleed-first while keeping each PR independently reviewable:

1. `pl` (94.6%, 349 rows) — biggest payoff.
2. `de` (74.2%, 268 rows).
3. `fr` (72.6%, 262 rows).
4. `cat` (67.2%, 156 rows) — Cat-dialect, needs the cat-themed vocabulary from `.junie/AGENTS.md` *Domain Analogies & Aliases* row. Cannot be machine-translated as a natural language.
5. `nl` (65.1%, 155 rows).
6. `ja` / `ko` / `zh` (62.5%, 145 rows each) — non-Latin scripts; placeholder masking is critical.
7. `pt` (57.4%, 132 rows).
8. `es` (56.5%, 131 rows) — also has `ReqRtpF013SpanishLocaleContentTest` content guards; do not break them.
9. `ru` (55.2%, 127 rows).
10. `it` (53.8%, 128 rows).

Each locale: one branch, one PR, one `LocaleParityTest` + full build before submitting.

## Out-of-Scope for This Plan

- Sweeping the existing mojibake in non-touched rows. Record incidental hits in `POTENTIAL_BUGS.md` per Stay-On-Task Policy. Touched-row mojibake is fixed by the import validator.
- Adding new baseline keys. If the user wants a new key, they go through the normal *Locale Config TSV Pipeline* and add the row in the same change; this plan is about catching up on already-merged baseline keys.
- The `shape/<x>.lang.yml` and `vert/<x>.lang.yml` rename-map files. They live in the locale TSV directly and follow the same workflow but are not currently part of the bleed audit (their values are short identifier strings, not prose).

## Deliverables for This Session

Below the line, the auditor + bundle scripts and a sample bundle for `pl` (largest bleed) to validate the end-to-end loop. The scripts are idempotent and operate purely on `scripts/out/*.tsv`; no `lang/` edits.

- `scripts/audit-locale-bleed.ps1` — audit only, prints summary and writes `scripts/out/bleed-<lang>.tsv`.
- `scripts/export-translation-bundle.ps1 -Locale <lang>` — emits `scripts/out/bundle-<lang>.md`.
- `scripts/import-translation-bundle.ps1 -Locale <lang>` — reads back `scripts/out/bundle-<lang>.md` (or another path), validates, patches `scripts/out/locale-<lang>.tsv`.
