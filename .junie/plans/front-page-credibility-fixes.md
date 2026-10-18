---
sessionId: session-261017-234633-1w9q
---

# Requirements

### Overview & Goals
You were right: the `/rtp back` entry I said I removed is still in `docs/dev/POTENTIAL_BUGS.md` (lines 92-100). This plan deletes it first, then makes the front-page fixes from your answers to points 1-6.

### Scope
#### In Scope
- Delete the `/rtp back` cooldown entry from `POTENTIAL_BUGS.md`. The lite `plugin.yml` drift entry stays.
- **1:** Performance wording. The bench config changes how fast input is accepted, not the cost of each teleport.
- **2:** The Folia numbers are presented as reproducible on the current build.
- **3:** "Safe max" gets one definition, and the Folia stages are explained.
- **4:** The "Distinct" bullet uses a nearest-neighbour number (closest pair 70 blocks) instead of "no repeated block".
- **5:** One wording used everywhere: no landing within 48 blocks (the pass threshold), and the closest pair was 70 blocks.
- **6:** `REROLL` becomes the default selection mode. `ACCUMULATE` stays as the opt-in for guaranteed O(1) selection.

#### Out of Scope
- The `/rtp back` rows on the front page and `rtp.back` in `plugin.yml` stay as they are. I'm only removing the bug entry you objected to.
- No new benchmark runs.

### Functional Requirements
- `POTENTIAL_BUGS.md` has no `/rtp back` entry.
- Nothing on the front page says the bench tuning changes per-teleport cost. Nothing tells readers to discount the LeafRTP rows as "not what an untouched install does".
- The Folia columns no longer say "haven't been rerun on this build".
- The Paper and Folia safe max columns use the same pass criteria. The Folia stage ladder is stated.
- New regions, and regions with no `mode` key, default to `REROLL` on every shape that currently defaults to `ACCUMULATE`.
- The front-page text that describes `ACCUMULATE` as the default (Terms paragraph, Common setups) describes `REROLL` as the default.

# Technical Design

### Current Implementation
- **Back entry:** `docs/dev/POTENTIAL_BUGS.md` lines 92-100 still hold the `/rtp back` cooldown entry.
- **Default mode:** `Mode.ACCUMULATE` is the default through static `defaults.put(..., Mode.ACCUMULATE)` calls in four shape classes under `rtp-core/.../selection/region/selectors/memory/shapes/`:
  - `Circle.java:35`
  - `Ellipse.java:38`
  - `Rectangle.java:24`
  - `Square.java:26`
- **Shipped configs:** `rtp-plugin/src/main/resources/config.yml:86`, the commented example in `definitions/regions/default.yml:33`, and all 13 `lang/<locale>/config.yml` files set `mode: "ACCUMULATE"`.
- **`FRONT_PAGE.md`:**
  - Line 159: the "Distinct" bullet.
  - Line 182: "48 blocks" plus the `ACCUMULATE` repeat caveat.
  - Line 225: one ~250-word Performance paragraph with the tuning disclaimer and the Folia "haven't been rerun" line.
  - Line 231: the Terms paragraph. It says `ACCUMULATE` is the default and gives a Paper-only safe max definition.
  - Line 239: the Folia header `(TPS >= 19)`.
  - The FAQ: "70 blocks".

### Key Decisions
- **Default flip in code and in shipped YAML.** Existing servers that set `mode: ACCUMULATE` in their files keep it. Only new configs and configs without a `mode` key change. A CHANGELOG entry covers it.
- **Bench config framing.** The Performance text will say the shipped refill, queue and backlog settings are sized for typical usage and limit how fast input is accepted. The bench config lifts that limit to find the break point, and per-teleport cost is unchanged. The defensive "read as a ceiling" sentence goes.
- **Spacing figure.** The Distinct bullet and Common setups will both use "closest pair 70 blocks; none within 48 blocks". I'll take run IDs and counts from `helpers/StressTestRTP/RESULTS.md` and the spacing section of `docs/site/how-it-works.md`, and won't invent any numbers.

### Proposed Changes
1. Delete the `POTENTIAL_BUGS.md` entry at lines 92-101.
2. Rewrite the `FRONT_PAGE.md` sections as one edit:
   - Split line 225 into three paragraphs: rig, method, config.
   - Fix the Folia provenance wording.
   - Give one safe max definition that covers both platforms, and list the Folia stage steps (taken from the Folia results docs).
   - Change the Folia column header to match.
   - Replace the Distinct bullet and make the 48/70 wording consistent in Common setups and the FAQ.
   - Rewrite the `ACCUMULATE`/`REROLL` sentences for the new default.
   - Update the reviewer note I added earlier in the header so it matches.
3. Flip the default mode in the 4 shape classes, `config.yml`, `regions/default.yml` and the 13 locale `config.yml` files. Update the spacing section of `docs/admin/configuration/REGIONS.md` and add a CHANGELOG line.

### Risks
- Tests may assert `ACCUMULATE` as the default. I'll grep `rtp-core/src/test` for this and update only tests that check the default. Tests that set the mode explicitly stay as they are.
- Locale files have to keep their key parity. Only the value changes, so `LocaleParityTest` should still pass.

# Testing

### Validation Approach
- `python scripts/release/build_front_pages.py --check`: all targets are valid, with no Zone 1 jargon and no broken links.
- `.\gradlew.bat :rtp-core:test`: shape default tests pass with `REROLL`.
- `.\gradlew.bat :rtp-plugin:test --tests "*LocaleParityTest*"`
- `python scripts/check-mojibake.py` on the edited files.

### Key Scenarios
- A grep for `rtp back` in `POTENTIAL_BUGS.md` returns nothing.
- A grep of `FRONT_PAGE.md` for "haven't been rerun", "throughput ceiling" and "default `ACCUMULATE`" returns nothing.
- A grep of `rtp-plugin/src/main/resources` for `mode: "ACCUMULATE"` returns nothing.

# Delivery Steps

### ✓ Step 1: Remove the /rtp back entry from POTENTIAL_BUGS.md
The `/rtp back` cooldown entry is gone from `docs/dev/POTENTIAL_BUGS.md`.

- Delete the `### 2026-10-17 - /rtp back cooldown ...` block (lines 92-101).
- Leave the lite `plugin.yml` drift entry unchanged.
- Verify with `grep_search` that the file no longer contains `rtp back` or `BackCmd`.

### ✓ Step 2: Rewrite the Performance and spacing claims in FRONT_PAGE.md (points 1-5)
The front page describes the bench config, Folia provenance, safe max and spacing accurately and the same way in every section.

- Split the Performance paragraph at line 225 into three paragraphs: rig, method, config.
- Say that the shipped settings limit how fast input is accepted, and per-teleport cost is unchanged.
- Remove the ceiling disclaimer.
- Replace "haven't been rerun on this build" with a statement that the Folia numbers are reproducible on the current build.
- Write one safe max definition that covers Paper and Folia. Add the Folia stage ladder from the StressTestRTP results. Fix the column header.
- Replace the Distinct bullet with the nearest-neighbour figure. Unify the wording in Common setups and the FAQ: none within 48 blocks, closest pair 70.
- Update the reviewer note in the header.
- Run `build_front_pages.py --check` and a mojibake scan.

### ✓ Step 3: Make REROLL the default selection mode (point 6)
New regions default to `REROLL`, and the docs describe `ACCUMULATE` as the opt-in for guaranteed O(1) selection.

- Change `defaults.put(..., Mode.ACCUMULATE)` to `Mode.REROLL` in `Circle`, `Ellipse`, `Rectangle` and `Square`.
- Set `mode: "REROLL"` in `config.yml`, the example in `definitions/regions/default.yml` and all 13 `lang/<locale>/config.yml` files.
- Update the mode sentences in the front-page Terms paragraph and Common setups, and the spacing section of `docs/admin/configuration/REGIONS.md`.
- Add a CHANGELOG entry.
- Update any test that asserts the old default.
- Run `:rtp-core:test`, `LocaleParityTest` and `build_front_pages.py --check`.