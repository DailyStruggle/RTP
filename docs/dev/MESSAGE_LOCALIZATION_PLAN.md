# Message Localization — Execution Plan

**Status:** Draft
**Last updated:** 2026-04-21
**Owning ADR:** [ADR-020 — Message Localization via Lazy Folder-Per-Locale Overlay](../adr/ADR-020-message-localization-lazy-overlay.md)
**Related requirement:** REQ-RTP-F-013 ([`REQUIREMENTS.md`](REQUIREMENTS.md)); related prohibition: S-007.

> **Purpose.** This document tracks the *implementation work* to realize ADR-020.
> ADR-020 decides **what** and **why**; this plan decides **how**, **when**, and **in what order**.
> Treat this file as the living checklist through to delivery; do not duplicate ADR rationale here.

---

## 1. Scope

**In scope**
- Loader changes in `rtp-core` to support a lazy locale overlay on `MessagesKeys`.
- A `language:` key in `config.yml` (default `en`, reserved `auto`).
- Jar resource layout: `rtp-plugin/src/main/resources/lang/<locale>/messages.yml` plus an *optional* sibling `lang/<locale>/messages.lang.yml` that re-uses the existing baseline `.lang.yml` format to remap `MessagesKeys` code constants → YAML key names for that locale (e.g., `asdf: value` lets `messages.yml` use `value: true` instead of `asdf: true`).
- Hot reload via `/rtp reload`.
- Seed translations for an initial locale set (see §6).
- REQ-traceable tests and traceability updates.

**Out of scope**
- Per-player locale resolution (`player.getLocale()`). `language: auto` is reserved but behaves as `en`.
- Localization of files other than `messages.yml` (e.g., `help.yml`). Layout supports it; work is deferred.
- Localization of `RTP.log(...)` output. Logs remain English (ADR-020 §Decision.7).
- Locale negotiation, region fallback chains beyond `xx_YY → xx → en`.

---

## 2. Success Criteria (Definition of Done)

1. `language: en` (or unset): **byte-identical on-disk behavior** to today — no new files extracted, no new parses. Verified by a test that asserts no `lang/<locale>/` directory is created when `language: en`.
2. `language: <known_locale>`: the corresponding jar resource is extracted to `plugins/RTP/lang/<locale>/messages.yml`, parsed, and overlays the English baseline. Every `MessagesKeys` lookup returns the locale value when present, else the English baseline.
3. `language: <unknown_locale>`: a single `WARNING` is logged; plugin continues to serve English. Startup does not fail.
4. `/rtp reload` after editing `language:` in `config.yml` switches locales with no server restart.
5. All shipped locale files pass a dev-only completeness test reporting any missing `MessagesKeys` entries.
6. Placeholder tokens (`[arg]`, `[P0]`, `%player%`, `%world%`, etc.) and color codes (`&0`–`&f`, `&k`–`&r`) are preserved verbatim in every shipped locale.
7. `TRACEABILITY.md` row `REQ-RTP-F-013` is updated to reference new class and test.
8. `CHANGELOG.md` and [`docs/admin/CONFIGURATION.md`](../admin/CONFIGURATION.md) document the `language:` key and the `lang/<locale>/` layout.

---

## 3. Architecture Touch Points

Per ADR-020, the change is narrow:

| Module | Change |
|---|---|
| `rtp-core` | New `LocaleOverlay` helper; modify the `MessagesKeys` parser construction site in `Configs.java` (~line 211); `getConfigValue` resolution for `MessagesKeys` consults overlay first. Overlay resolves the effective key-name map per locale: locale's `messages.lang.yml` if present, else the baseline `lang/messages.lang.yml`. |
| `rtp-api` | No changes. `MessagesKeys` enum remains the authoritative key set. |
| `rtp-plugin` | Add `config.yml: language:` default `en`; add jar resources `lang/<locale>/messages.yml` per shipped locale, plus an optional `lang/<locale>/messages.lang.yml` when a locale needs renamed YAML keys; update default `messages.yml` only if new keys are introduced (none planned). |
| Platform adapters (`rtp-spigot`, `rtp-paper`, `rtp-folia`) | No changes. |
| `addons/` | No changes. |

Module boundary compliance: follows `AGENTS.md §Architecture Boundaries` — core logic in `rtp-core`, resources in `rtp-plugin`, no platform imports introduced.

---

## 4. Phased Work Breakdown

Each phase is independently mergeable. Check items off as they land.

### Phase 0 — Proposal & Approval

- [ ] ADR-020 reviewed and marked **Accepted**.
- [ ] This plan reviewed; §6 locale list confirmed with maintainer.
- [ ] Confirm no conflict with in-flight `BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md` or `SAFETY_TAGS_AND_STATES_PLAN.md` work (neither touches `MessagesKeys` or `Configs.java` message parser).

### Phase 1 — Config Key

- [ ] Add `language:` to the enum backing `config.yml` (default `en`).
- [ ] Document the key in [`docs/admin/CONFIGURATION.md`](../admin/CONFIGURATION.md) under the `config.yml` section.
- [ ] Unit test: reading `language:` returns `en` when absent; returns normalized lowercase value when present; reserved tokens (`en`, `auto`, empty, blank) all resolve to the "no overlay" branch.
- [ ] `.bak` backups created for any modified file (per `AGENTS.md` backup policy).

### Phase 2 — Loader (overlay, no locales shipped yet)

- [ ] Introduce `LocaleOverlay` in `rtp-core/.../common/configuration/` with:
  - `attach(ConfigParser<MessagesKeys> baseline, File pluginDirectory, String locale)`
  - Extract-if-missing logic mirroring `ConfigParser`'s existing pattern, extracting both `lang/<locale>/messages.yml` and the optional `lang/<locale>/messages.lang.yml`.
  - Effective key-name map resolution: prefer locale `messages.lang.yml`, else fall back to the baseline `lang/messages.lang.yml` (identity by default). A missing per-locale `.lang.yml` is **not** a warning.
  - Warn-and-fall-back path for unknown locales (missing locale directory entirely).
- [ ] Modify `Configs.java` so that after baseline `MessagesKeys` construction it invokes `LocaleOverlay.attach(...)` iff `language` requires an overlay.
- [ ] Ensure overlay is consulted on every `MessagesKeys` `getConfigValue(...)` path (pick the cleanest seam — either wrap the `ConfigParser` or register an override map on it).
- [ ] `/rtp reload` rebuilds the overlay.
- [ ] Tests (see §5).

### Phase 3 — Tooling: Completeness + Placeholder Lint

- [ ] Dev-only test `ReqRtpF013LocaleCompletenessTest`:
  - Enumerates every shipped `lang/<locale>/messages.yml` resource.
  - Asserts every `MessagesKeys` entry is either present or known-fallback-acceptable.
  - Reports missing keys as a diagnostic list (not hard failure for partial translations in Phase 4+, hard failure for official locales marked "complete" in a manifest).
- [ ] Dev-only test `ReqRtpF013PlaceholderPreservationTest`:
  - For every shipped locale file, verifies each localized value contains the same set of placeholder tokens (`[P0]`, `[arg]`, `%player%`, etc.) as the English baseline.
  - Verifies `&`-color codes are syntactically valid (`&` followed by `0-9a-fk-or`).
- [ ] Dev-only test `ReqRtpF013LocaleLangMapConsistencyTest`:
  - For every shipped `lang/<locale>/messages.lang.yml` (where present), asserts left-hand keys are a subset of `MessagesKeys` constants.
  - Asserts every right-hand YAML key named by the map actually exists as a key in the sibling `lang/<locale>/messages.yml` (catching drift like `asdf: value` when the file still contains `asdf: true`).

### Phase 4 — Seed Translations

For each locale in §6:

- [ ] Generate first-pass draft (AI-assisted) from the English `messages.yml`.
- [ ] Add to `rtp-plugin/src/main/resources/lang/<locale>/messages.yml`.
- [ ] Run Phase 3 lints; fix placeholder and color-code drift.
- [ ] Request native-speaker review (mark PR as "awaiting native review" if unavailable).
- [ ] Mark as "complete" in the locale manifest only after native review.

### Phase 5 — Documentation & Release

- [ ] `CHANGELOG.md` entry under the next version.
- [ ] [`docs/admin/CONFIGURATION.md`](../admin/CONFIGURATION.md) — describe `language:`, list shipped locales, document fallback rules, explain that community contributions are welcome as `lang/<locale>/messages.yml`.
- [ ] `TRACEABILITY.md` — update REQ-RTP-F-013 row to add `LocaleOverlay` and the new test classes.
- [ ] `AGENTS.md` — no change expected (REQ-RTP-F-013 already referenced).
- [ ] Cross-link: update the Required Reading table row for "Database / command / shutdown work" style? **No** — localization isn't safety-critical and doesn't warrant a new row.

---

## 5. Test Strategy

All new tests reference REQ-RTP-F-013 in class name or `@DisplayName`, per `AGENTS.md §Code & Testing Conventions`.

| Test | Module | Purpose |
|---|---|---|
| `ReqRtpF013DefaultLocaleNoOverlayTest` | `rtp-core` | With `language: en`, assert no `lang/<locale>/` directory created; overlay not attached; lookup returns English. |
| `ReqRtpF013OverlayLookupTest` | `rtp-core` | With `language: es` and a synthetic overlay, assert translated keys win, untranslated keys fall back to English. |
| `ReqRtpF013LocaleLangMapRemapTest` | `rtp-core` | With a synthetic locale whose `messages.lang.yml` contains `teleportMessage: tp_msg` and whose `messages.yml` uses `tp_msg: "..."`, assert `MessagesKeys.teleportMessage` resolves to the locale value. Covers the `value → asdf` style remap end-to-end. |
| `ReqRtpF013LocaleLangMapOmittedFallsBackToBaselineTest` | `rtp-core` | With a synthetic locale whose `messages.lang.yml` is absent, assert the baseline `lang/messages.lang.yml` is used to read the locale's `messages.yml` and no warning is logged. |
| `ReqRtpF013UnknownLocaleFallbackTest` | `rtp-core` | With `language: zz`, assert single WARNING log and English lookups continue. Must not throw. |
| `ReqRtpF013ReloadSwitchesLocaleTest` | `rtp-core` | Start `en`, reload with `es`, assert overlay attached and active; reload back to `en`, assert overlay detached. |
| `ReqRtpF013LocaleCompletenessTest` | `rtp-plugin` (dev-only) | Enumerate shipped locales, report missing keys. |
| `ReqRtpF013PlaceholderPreservationTest` | `rtp-plugin` (dev-only) | Assert placeholder and color-code fidelity across shipped locales. |
| `ReqRtpF013LogsRemainEnglishTest` | `rtp-core` | Under any `language:` value, `RTP.log(...)` output matches English constants (guards ADR-020 §Decision.7). |

Test execution: prefer `run_test` tool over Gradle CLI (per `AGENTS.md §Environment & Execution`).

---

## 6. Initial Shipped Locales

Proposed first batch. Final list pending Phase 0 approval.

| Locale | Code | Rationale | Native reviewer |
|---|---|---|---|
| Spanish | `es` | Large Minecraft-server userbase. | TBD |
| German | `de` | Large Minecraft-server userbase. | TBD |
| French | `fr` | Large Minecraft-server userbase. | TBD |
| Brazilian Portuguese | `pt_BR` | Large Minecraft-server userbase. | TBD |
| Simplified Chinese | `zh_CN` | Large Minecraft-server userbase. | TBD |

Additional locales may be accepted as community PRs post-launch without further ADR work.

---

## 7. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| AI-drafted translation corrupts placeholders (`[arg]` → `[argumento]`) | Medium | Breaks variable substitution | Phase 3 `PlaceholderPreservationTest` blocks merge. |
| Color codes (`&c`) localized in RTL/CJK translations | Low | Visual glitch | Same test enforces code fidelity. |
| `ConfigParser` API resists clean overlay attachment | Low-Medium | Forces invasive refactor | Prototype during Phase 2 spike; if painful, wrap `ConfigParser` instead of modifying it. Re-scope if needed. |
| Admin edits lost when updating plugin version | Medium | User trust issue | Mirror existing extract-if-missing behavior; never overwrite on-disk locale files. Document in `CONFIGURATION.md`. |
| Partial translations confuse users ("half Spanish, half English") | Medium | Cosmetic | Default fallback behavior is intended; document clearly; encourage completing translations before marking "official". |
| Scope creep into per-player locale | Medium | Delays delivery | `language: auto` reserved but explicitly deferred; any PR proposing per-player resolution must reference a new ADR superseding the relevant parts of ADR-020. |

---

## 8. Non-Goals (Explicit)

- This plan does **not** localize log output, command-tab-completion suggestions, or addon messages.
- This plan does **not** introduce a translation build pipeline, CI integration with external translation platforms (Crowdin, Weblate), or string-freeze processes.
- This plan does **not** modify `rtp-api`, `MessagesKeys`, or any platform adapter.
- This plan does **not** change the format or semantics of the baseline `lang/messages.lang.yml`; per-locale `messages.lang.yml` files follow the same format verbatim.
- This plan does **not** add locale fallback chains beyond the simple `xx_YY → xx → en` implied by normalization (only `xx_YY → en` is required for Phase 2; richer chaining may be added later without an ADR change).

---

## 9. Open Questions

- [ ] Should the completeness test hard-fail for locales marked "official" in the manifest, or only report? (Proposal: hard-fail once a locale has passed native review.)
- [ ] Should `language:` accept mixed-case (`en_US` vs `en_us`)? (Proposal: normalize to `lowercase_UPPER` — `en_US` — following Java `Locale` convention; match filenames case-insensitively.)
- [ ] Do we want a `/rtp lang list` admin subcommand? (Proposal: defer; operators can `ls` the `lang/` folder.)

---

## 10. Rollback Plan

If a critical defect emerges post-release:

1. Set `language: en` (documented default). Overlay code path becomes inert; behavior reverts to pre-change.
2. Optionally delete `plugins/RTP/lang/` on disk — safe, will be re-extracted on next locale switch.
3. If the defect is in the loader itself (not a specific translation), revert the `rtp-core` loader commit; jar resources under `lang/<locale>/` become dormant but harmless.

No database migrations, no persistent state changes, no player-visible data loss in any rollback scenario.
