# Translation Contributor Guide

Operational rules for adding or updating a locale under `rtp-plugin/src/main/resources/lang/<locale>/`. Keep this guide short — it is enforced by `LocaleResourceParityTest` (`rtp-plugin/src/test/.../configuration/LocaleResourceParityTest.java`); read that test if you need to know exactly what CI rejects.

> Locales currently shipped: `de`, `es`, `fr`, `nl`. The canonical baseline is the un-suffixed English files at `rtp-plugin/src/main/resources/*.yml`.

**Planned locales (contributions welcome):**

| Code | Language | Notes |
|------|----------|-------|
| `zh` | Chinese (Simplified) | Largest Minecraft player base outside the West |
| `pt` | Portuguese (Brazilian) | 4th most spoken language globally; large Brazilian server community |
| `ru` | Russian | Large Eastern European server community |
| `ja` | Japanese | Active Minecraft community; tests multi-byte character encoding |
| `pl` | Polish | Very active Minecraft modding/server community in Europe |
| `ko` | Korean | Growing Minecraft community; tests multi-byte character encoding |
| `it` | Italian | Completes the major Romance languages alongside `es` and `fr` |

---

## What gets translated

Each translatable config file ships **two** locale artifacts:

1. `lang/<locale>/<file>.lang.yml` — a flat **key-rename map**: `internalEnumName: localizedKeyName`. This rewrites the YAML key names an operator sees on disk.
2. `lang/<locale>/<file>.yml` — the **localized value file** whose top-level keys are the right-hand sides of the `.lang.yml` map and whose values are the translated strings.

A locale **does not** need a copy of files that hold only proper nouns / plugin identifiers / numeric tunables (`integrations.yml`, `version` sentinels). See the "When *not* to translate" section.

---

## Hard rules (CI-enforced)

1. **No `.bak` files under `lang/`.** Editor and agent backups are not shipped. `processResources` excludes `**/*.bak` and `LocaleResourceParityTest` fails the build if any leak in.
2. **Key parity.** Every non-identity right-hand value in `<file>.lang.yml` must exist as a top-level key in the sibling `<file>.yml`. Mismatches are exactly the bug class that produced the original blank-`rtp info` Spanish output (the `messages.lang.yml` map pointed at Spanish keys that did not exist in the on-disk YAML).
3. **Placeholder fidelity.** Translated `messages.yml` files must not introduce `[token]` placeholders that aren't in the English baseline. Translating `[player]` → `[jugador]` will never substitute at runtime; the parity test rejects this.
4. **UTF‑8, no BOM.** Save every `*.yml` as UTF‑8 without BOM. (PowerShell display mojibake is not a real encoding bug — open the file in IntelliJ to confirm.)

---

## Soft rules (reviewer judgement)

5. **Keep color codes identical** to the English baseline (`&e` warnings, `&c` errors, `&a` success, `#hex` accents). Don't "improve" colors during translation.
6. **Mirror file coverage across locales.** If `de/` ships `regions.yml`, then `es/` and `fr/` should too — or none should.
7. **Don't translate `version:` keys** or any sentinel used by config-migration logic.
8. **Identity mappings carry no signal.** A line like `infoTickets: infoTickets` in `<file>.lang.yml` is a no-op for the locale-switch detector (`ConfigParser.detectAndPreserveLocaleMismatch`). It is allowed (the parity test tolerates it) but represents an untranslated key. Prefer translating it; if the term has no good native equivalent, leave it identity-mapped and accept the cosmetic mix.
9. **Translate header comments** for operator-facing files (`messages.yml`, `config.yml`) but keep them short. Comments are not remapped by `ConfigParser`.

---

## When *not* to translate a file

A file does **not** need a `lang/<locale>/<file>.yml` + `.lang.yml` pair when **any** of the following are true:

- All keys are proper nouns / third-party plugin identifiers (`Factions`, `WorldGuard`, `HuskTowns`, …).
- All values are booleans / numbers / version strings with no operator narrative.
- Translating the keys would break the operator's mental model (e.g. plugin-bridge toggles).

`integrations.yml` is the canonical example. The locale-switch migration short-circuits when `language_mapping` is entirely identity, so shipping an identity-only `integrations.lang.yml` per locale is a no-op — and shipping a renamed one would force operators to learn `rerolarFacciones` instead of `rerollFactions`, losing the plugin-name affordance.

---

## Adding a new locale

1. Create `rtp-plugin/src/main/resources/lang/<locale>/`.
2. For each file you intend to translate, copy the English baseline value file as `<locale>/<file>.yml` and translate strings (preserving placeholders, color codes, and structure).
3. Author `<locale>/<file>.lang.yml` mapping each enum/canonical name to your translated key name. Identity entries (`foo: foo`) are permitted for keys with no good translation but contribute no locale signal.
4. Run the parity test:
   ```powershell
   .\gradlew :rtp-plugin:test --tests "*LocaleResourceParityTest*"
   ```
5. Run the locale-switch regression suite to confirm `ConfigParser` migration still detects your locale:
   ```powershell
   .\gradlew :rtp-core:test --tests "*ConfigParserLocaleSwitchTest*"
   ```
6. Smoke-test on a server: set `language: <locale>` in `language.yml`, restart, run `/rtp info`, and confirm every line is non-blank and translated.

---

## Updating an existing locale

- Translate identity mappings opportunistically (`<file>.lang.yml` lines where left == right). Each one you translate also strengthens the locale-switch detector.
- When the English baseline gains a new key, mirror the addition in every locale's `<file>.lang.yml` and `<file>.yml`. The parity test will fail closed if you forget.
- Never remove a key from a translated `<file>.yml` without removing its entry from the corresponding `<file>.lang.yml`.

---

## Related

- `ConfigParser.detectAndPreserveLocaleMismatch` — the migration that re-extracts the JAR's localized YAML and preserves user values when `language.yml` flips. Reading the tests in `ConfigParserLocaleSwitchTest` is the fastest way to understand its contract.
- ADR-020 — locale bootstrap rationale.
- REQ-RTP-F-013 — all user-facing messages are configurable via `messages.yml`.
- `LESSONS_LEARNED.md` — locale-switch cache eviction and identity-mapping pitfalls.
