# Checklist — `/rtp docs` Menu Consumer (ADR-045)

**Effective Issue:** Implement `/rtp docs` per ADR-045 — parse Markdown docs in `<datafolder>/docs/` into `MenuModel`s at config load/reload, render via existing `MenuRenderer` (book first). Admin/operational scope.

**Mode:** `[CODE]` — multi-session, D-005 approved 2026-05-16.

**Governing ADR:** ADR-045 (Proposed → flip to Accepted on completion). Prerequisites met: Stage 2 (token registry + redeem) and Stage 4 (`BookMenuRenderer`) of `CHECKLIST-generalized-menu.md`.

**User-locked scope deltas vs. ADR-045 as written:**
- Do **not** bundle `docs/` tree into `rtp-plugin/src/main/resources/docs/` yet. `DocsRegistry` walks whatever exists at `docs.root` (default `<datafolder>/docs/`). Which docs ship in the jar is deferred.
- Therefore: no `rtp-plugin` resource-extraction step in this change; if the data-folder docs dir is missing, `DocsRegistry#rebuild()` produces an empty cache and `/rtp docs` shows `docs.empty` chrome.
- Audience is admin/operational — `docs.exposeDeveloperDocs` stays `false` by default.

---

## Stage A — `rtp-core` data model + lowerer

- [ ] A.1 `DocsLoweringOptions` record — `(int maxLineWidth, int maxCodeLineWidth, boolean exposeDeveloperDocs, long maxFileBytes)` with sane defaults factory.
- [ ] A.2 `MarkdownToMenuModel` — pure function `lower(String relpath, String source, DocsLoweringOptions) -> MenuModel`. Subset per ADR-045 §"Markdown lowering". No platform / third-party deps.
- [ ] A.3 `MarkdownToMenuModelTest` — table-driven, one case per supported / fidelity-loss row in ADR-045.

## Stage B — `rtp-core` registry + profile + leaf command

- [ ] B.1 `DocsRegistry` — `AtomicReference<Map<String, MenuModel>>`; `rebuild(Path root, DocsLoweringOptions)` walks `*.md`, lowers each, atomic swap. `get(relpath)` throws `IllegalStateException` before first `rebuild()` (S-006). Sandbox: normalize and verify under root; reject symlinks. Errors logged via `RTP.log(WARNING, ...)`.
- [ ] B.2 `DocsRegistry` synthetic-index generation when `MAP.md` absent (alphabetized, dirs first).
- [ ] B.3 `DocsConsumerProfile implements MenuConsumerProfile` — `suggestPrefix() = "/rtp docs "`, `commentLookup() = YamlCommentLookup.EMPTY`.
- [ ] B.4 `DocsCmd` — `BaseRTPCmdImpl` subclass, `name()="docs"`. Looks up registry, dispatches to active `MenuRenderer`. Path sandbox check; missing → `docs.notFound`; disabled → `docs.disabled`.
- [ ] B.5 `DocsRegistryTest` — atomic swap; oversized → `docs.tooLarge`; sandbox-violation; `exposeDeveloperDocs=false` excludes `dev/`, `adr/`, `architecture/`.
- [ ] B.6 `DocsCmdTest` — `/rtp docs` opens root index; `/rtp docs <file>` opens cached model; `/rtp docs ../escape` rejected; permission-less caller routed to `docs.disabled`.

## Stage C — `rtp-plugin` wire-up + config + messages

- [ ] C.1 `config.yml` (main + lite) `docs:` block per ADR-045 §"Configuration surface". Lite default `enabled: false`.
- [ ] C.2 `ConfigKeys` enum gains `docs` entry.
- [ ] C.3 `messages.yml` `docs:` block: `title`, `invalid`, `notFound`, `tooLarge`, `lowerError`, `back`, `empty`, `disabled`, `staleHint`. Matching `MessagesKeys` enum entries.
- [ ] C.4 `RTPCmdBukkit` registers `DocsCmd`. Wire `DocsRegistry#rebuild()` into enable + reload paths (async scheduler).
- [ ] C.5 `TRACEABILITY.md` rows: `MarkdownToMenuModelTest`, `DocsRegistryTest`, `DocsCmdTest` → REQ-RTP-S-004 / S-005 / S-006 / S-007 / F-013.

## Stage D — Cleanup

- [ ] D.1 ADR-045: Proposed → Accepted; date 2026-05-16.
- [ ] D.2 `CHECKLIST-generalized-menu.md` Stage 5.7 row added pointing at this checklist.
- [ ] D.3 `docs/dev/RULES.md` — "Authoring docs for the in-game reader" section per ADR-045 §Decision close.
- [ ] D.4 `CHANGELOG.md` bullet under `[3.0.0-beta.3] - Unreleased ### Added`.
- [ ] D.5 Full `.\gradlew build` green.
- [ ] D.6 Delete this scratch checklist.
