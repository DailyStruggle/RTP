# Checklist — Rename `rtp-spigot` → `rtp-bukkit` (module + packages + docs)

**Effective Issue:** Amend `rtp-spigot` to `rtp-bukkit`, since Spigot is no longer a reliable long-term platform. The module has always been the "generic Bukkit-family" adapter — Paper, Folia, and Spigot all consume it through `rtp-paper-common` / `rtp-folia-common`. The name was a historical mislabel.

**Mode:** `[CODE]` — multi-session, **D-005 gated**. Multi-class, multi-module, crosses every module boundary in the Bukkit family.

**Approved scope (locked 2026-05-16 via `ask_user`):**

- **Module rename**: `rtp-spigot/` → `rtp-bukkit/`. Submodules:
  - `rtp-spigot-common` → `rtp-bukkit-common`
  - `rtp-spigot-v1_20_R1` → `rtp-bukkit-v1_20_R1`
  - `rtp-spigot-v1_21_R1` → `rtp-bukkit-v1_21_R1`
  - `rtp-spigot-v26_1_R1` → `rtp-bukkit-v26_1_R1`
- **Java packages** (chosen to avoid colliding with `rtp-plugin`'s existing `io.github.dailystruggle.rtp.bukkit.*`):
  - `io.github.dailystruggle.rtp.spigot`         → `io.github.dailystruggle.rtp.bukkitplatform`
  - `io.github.dailystruggle.rtp.spigot_v1_20_R1` → `io.github.dailystruggle.rtp.bukkitplatform.v1_20_R1`
  - `io.github.dailystruggle.rtp.spigot_v1_21_R1` → `io.github.dailystruggle.rtp.bukkitplatform.v1_21_R1`
  - `io.github.dailystruggle.rtp.spigot_v26_1_R1` → `io.github.dailystruggle.rtp.bukkitplatform.v26_1_R1`
- **Class prefix**: swap `Spigot` → `Bukkit` in every class name (e.g., `SpigotWorld` → `BukkitWorld`, `V1_20_R1SpigotVersionAdapter` → `V1_20_R1BukkitVersionAdapter`). `rtp-plugin`'s pre-existing `Bukkit*` classes (e.g., `BukkitBaseRTPCmd`) stay untouched — no collision because they're in a different package.
- **Docs**: update **all** references retroactively, including ADRs, REQUIREMENTS, TRACEABILITY, GLOSSARY, AGENTS.md, CHANGELOG, scratch checklists, MULTI_PLATFORM_PLAN. Historical text was mislabeled to begin with per user.
- **CI / scripts**: update Jenkinsfile, `check_traceability.sh`, `.github/`, any PowerShell helpers that hardcode `rtp-spigot`.
- **No behavior change.** Pure rename. Build outputs (jar names) follow the new module names — accept that as the only externally visible delta.

**Governing requirements / ADRs:**

- D-005 (this checklist *is* the proposal; user confirmed scope by `ask_user`).
- AGENTS.md *Architecture Boundaries* — `rtp-spigot` is listed as a platform adapter; entry will read `rtp-bukkit`.
- ADR-019 (claim plugin integrations) and any ADR mentioning `rtp-spigot` by name need a one-line update; track every hit during Stage 6.

---

## Stage 0 — Pre-work & inventory (DONE)

- [x] 0.1 Persist this checklist (`docs/dev/scratch/CHECKLIST-rtp-spigot-to-rtp-bukkit.md`).
- [x] 0.2 Snapshot inventory captured 2026-05-16: `rtp-spigot/` tree = **279 files**. `:rtp-spigot` Gradle module references located in 5 real source files (settings.gradle + 4 build.gradle consumers); all updated in Stage 1. Remaining `rtp-spigot` / `rtp.spigot` / `Spigot*` hits in `build/` and `.idea/` are stale artifacts and out of scope.
- [x] 0.3 Working tree state confirmed — **104 dirty files** at session start. Rename diff being kept logically separate from those pre-existing edits.

---

## Stage 1 — `settings.gradle` + module directory renames (DONE)

- [x] 1.1 `git mv rtp-spigot rtp-bukkit` plus the four submodule dirs.
- [x] 1.2 `settings.gradle`: all six `include 'rtp-spigot:rtp-spigot-*'` lines renamed to `rtp-bukkit:rtp-bukkit-*`. No `projectDir` overrides present.
- [x] 1.3 Per-submodule `build.gradle` (`rtp-bukkit-v1_20_R1`, `rtp-bukkit-v1_21_R1`, `rtp-bukkit-v26_1_R1`): `api project(':rtp-spigot:rtp-spigot-common')` → `api project(':rtp-bukkit:rtp-bukkit-common')`.
- [x] 1.4 Consumer `build.gradle` updates: `rtp-paper/rtp-paper-common/build.gradle` and `rtp-plugin/build.gradle` (three `:rtp-spigot-v*_R1` lines).
- [x] 1.5 `.\gradlew :rtp-bukkit:rtp-bukkit-common:compileJava` — **BUILD SUCCESSFUL** in 13s.
- [x] 1.6 Commit-ready checkpoint reached: directory + Gradle wiring renamed, package paths still `spigot`.

---

## Stage 2 — Java package + class renames (DONE 2026-05-16)

- [x] 2.1 Package directories `git mv`'d:
  - `rtp-bukkit-common/src/{main,test}/java/.../spigot` → `.../bukkitplatform`
  - `rtp-bukkit-v1_20_R1/src/{main,test}/java/.../spigot_v1_20_R1` → `.../bukkitplatform/v1_20_R1`
  - `rtp-bukkit-v1_21_R1/src/main/java/.../spigot_v1_21_R1` → `.../bukkitplatform/v1_21_R1`
  - `rtp-bukkit-v26_1_R1/src/main/java/.../spigot_v26_1_R1` → `.../bukkitplatform/v26_1_R1`
- [x] 2.2 Bulk text-replace across repo of the four package strings (`io.github.dailystruggle.rtp.spigot[_v*]` → `io.github.dailystruggle.rtp.bukkitplatform[.v*]`) via PowerShell `[System.IO.File]::WriteAllText` (UTF-8 no BOM, LF preserved). 71 files modified including consumers in `rtp-paper-common`, `rtp-plugin`, `rtp-fabric` glue, and `rtp-core` test fixtures. **Caveat resolved:** an initial pass corrupted files with BOM + CRLF (Set-Content default); recovered via byte-faithful re-read from git HEAD + re-apply.
- [x] 2.3 Sole `Spigot`-prefixed class `SpigotTpsSampler` renamed to `BukkitTpsSampler` (+ `SpigotTpsSamplerTest` → `BukkitTpsSamplerTest`) via `rename_element`. No other `Spigot*` classes exist; the rest of `rtp-bukkit-common` was already `Bukkit*`-prefixed.
- [x] 2.4 `AnvilPackageBoundaryArchTest` ArchUnit patterns updated `..spigot..` → `..bukkitplatform..` and `..spigot.anvil..` → `..bukkitplatform.anvil..`.
- [x] 2.5 Stale Javadoc references `{@code rtp.spigot.*}` → `{@code rtp.bukkitplatform.*}` updated in `BukkitTestCmd.java`, `TestCmd.java`, `RTPFabricMod.java`.
- [x] 2.6 Verification:
  - `.\gradlew :rtp-bukkit:rtp-bukkit-common:test` — **BUILD SUCCESSFUL**, 41 tests passed, 0 failed.
  - `.\gradlew :rtp-bukkit:rtp-bukkit-v1_20_R1:test :rtp-paper:rtp-paper-common:compileJava :rtp-plugin:compileJava` — **BUILD SUCCESSFUL**.
- [x] 2.7 Remaining `rtp.spigot` / `Spigot` hits triaged as legitimate (kept):
  - `DownloadInfo.SPIGOT` enum + `SPIGOT_BBB_*` constants — SpigotMC marketplace, not the module.
  - `helpers/StressTestRTP/.../TpsMsptHeapSampler.java` — fields about the vanilla Spigot TPS API.
  - Test method names `spigotScheduler_runTask_*`, `anvilPackageIsSpigotOnly` — semantic content about the Spigot platform itself; class file names and packages are renamed.
  - This checklist file (deleted at Stage 5.4).

**Stage 2 commit-ready checkpoint reached.**

---

## Stage 3 — Plugin metadata, resources, CI, scripts (NEXT SESSION)

- [ ] 3.1 `rtp-plugin/src/main/resources/plugin.yml` and `paper-plugin.yml` (if present) — author / softdepend strings referencing `rtp-spigot` jar name (if any).
- [ ] 3.2 Jenkinsfile — update artifact stages that reference `rtp-spigot-*.jar` paths to `rtp-bukkit-*.jar`.
- [ ] 3.3 `.github/` — issue templates, workflow files, any `rtp-spigot` mentions.
- [ ] 3.4 `check_traceability.sh`, `_insert_changelog.ps1`, and other repo-root scripts — grep for `rtp-spigot` / `spigot/` literals.
- [ ] 3.5 `rtp-plugin/build.gradle` — shadowJar relocations / includes that name `:rtp-spigot-*` dependencies.

---

## Stage 4 — Docs sweep

User directive: **update all docs including retroactive design choices as they were mislabeled to begin with.** So historical ADRs *do* get rewritten in place (no superseding ADR per term), but each ADR touched gets a one-line "2026-05-16 — module renamed `rtp-spigot` → `rtp-bukkit` (no behavioural change)" footer for traceability.

- [ ] 4.1 `AGENTS.md` — *Architecture Boundaries* (item 4), *Self-Updating Protocol* "Safe-to-modify modules" line, any other `rtp-spigot` mention.
- [ ] 4.2 `docs/dev/REQUIREMENTS.md`, `docs/dev/DESIGN.md`, `docs/dev/GLOSSARY.md`, `docs/dev/MULTI_PLATFORM_PLAN.md`, `docs/dev/ARCHITECTURE.md`.
- [ ] 4.3 `docs/dev/TRACEABILITY.md` — every row whose class path includes `rtp-spigot/` or package `rtp.spigot.*`.
- [ ] 4.4 ADRs: `docs/adr/` sweep. Each touched ADR gets the dated footer noted above.
- [ ] 4.5 `CHANGELOG.md`: bullet under `[3.0.0-beta.3] - Unreleased ### Changed`.
- [ ] 4.6 README.md / SUPPORT.md / CONTRIBUTING.md — any operator-facing mentions.
- [ ] 4.7 Other scratch checklists under `docs/dev/scratch/` — sweep for stale references.
- [ ] 4.8 Subproject docs: `rtp-fabric/docs/`, `effects-api/docs/`, `commands-api/docs/`, `rtp-proxy/docs/`.

---

## Stage 5 — Final verification

- [ ] 5.1 `.\gradlew build` — full multi-module. Must be green. Cite headline in submit summary.
- [ ] 5.2 Zero-hit sweeps:
  - `search_project "rtp-spigot"` → 0 hits.
  - `search_project "rtp.spigot"` → 0 hits in production code.
  - Legitimate `"Spigot"` string survivors are reviewed and explicitly kept.
- [ ] 5.3 Markdown encoding hygiene sweep on every doc edited in Stage 4.
- [ ] 5.4 Delete this scratch checklist.

---

## Notes / open questions

- Pre-existing 104 dirty files are unrelated to this rename and must remain logically separate.
- Build-output jar names will change from `rtp-spigot-*.jar` to `rtp-bukkit-*.jar` — externally visible side effect, to be mentioned in CHANGELOG under *Breaking (compile-only) Changes*.
- Class prefix `Bukkit` already established in the codebase via `rtp-plugin`'s `BukkitBaseRTPCmd` etc. — no new naming pattern introduced.
- "Spigot" as a *user-facing platform name* in `messages.yml`, command help text, and operator-facing docs may legitimately stay where it refers to the Spigot server software itself.
- **Stage 2 lesson learned (record before Stage 3):** PowerShell `Set-Content -Encoding UTF8` and `[System.IO.File]::WriteAllText` with default encoding both write a BOM, and `Set-Content` also converts LF→CRLF. The `rename_element` tool also re-writes files with BOM. Always strip BOMs from `rtp-bukkit/**` after a rename pass with `Get-ChildItem ... | %% { strip-bom }` before compiling. (Candidate for `LESSONS_LEARNED.md` if observed again.)
