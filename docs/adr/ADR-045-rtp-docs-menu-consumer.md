# ADR-045 — `/rtp docs` Menu Consumer (Markdown → `MenuModel`, converted at config load)

**Status:** Proposed
**Date:** 2026-05-15
**Target release:** `3.0.0-beta.3`

## Context

The plugin ships a substantial Markdown documentation tree under `docs/` (`FRONT_PAGE.md`, `FOR_SERVER_ADMINS.md`, `MAP.md`, `admin/**`, and developer-facing material under `dev/`, `adr/`, `architecture/`). Server operators today must alt-tab to a browser or the jar to read any of it. With the generalized menu primitive landed in [ADR-035](ADR-035-interactive-menus-book-first.md) (platform-neutral `MenuModel` in `rtp-api`) and the command-tree reflector specified in [ADR-044](ADR-044-command-tree-menu-reflector.md), the cost of an in-game docs reader collapses to one producer + one consumer profile + one subcommand.

Three project-wide invariants shape the design:

1. **Docs are read-only at runtime.** They are shipped resources, extracted to the data folder on enable, and refreshed only on plugin start or admin-initiated reload. They are not edited from the running server. This means the (expensive, fidelity-sensitive) Markdown → Minecraft-text conversion belongs on the **config load / reload** path, not on each `/rtp docs` invocation — matching the rest of RTP, where every other file-bound transform (YAML parse, `messages.yml` interpolation, config defaults application) happens at load and is cached.
2. **The menu primitive is plain-text-only on the API surface.** `MenuFragment(text, hover, action)` carries plain strings; Adventure component construction is a renderer responsibility ([ADR-035](ADR-035-interactive-menus-book-first.md)). Producers must already project to plain text. Markdown is therefore lowered to plain text once, at load, and the resulting `MenuModel`s are cached verbatim.
3. **No third-party Markdown parser in `rtp-core`.** `rtp-core` must compile and run free of platform and heavyweight library dependencies (Architecture Boundaries, `AGENTS.md`). Markdown lowering is performed by a small in-house converter targeted at the subset RTP authors itself.

Per user direction (2026-05-15): some fidelity loss is acceptable; we will **format authored Markdown to maximize parity** (no inline tables mixed into prose paragraphs; blank-line-separated structural blocks; bare links over reference-style links; one-line list items) rather than build a Markdown engine that recovers fidelity from arbitrary input.

## Decision

Ship `/rtp docs` as a menu consumer that walks an extracted `docs/` directory under the plugin data folder, lowers every Markdown file to a cached `MenuModel` **at config load and reload time**, and renders the cached model through the same `MenuRenderer` pipeline ([ADR-035](ADR-035-interactive-menus-book-first.md)) used by `/rtp config` ([ADR-044](ADR-044-command-tree-menu-reflector.md)). The runtime command path performs **no file I/O and no Markdown parsing** — it is a cache lookup followed by a renderer call.

### Module placement (Architecture Boundaries)

- **`rtp-api`** — no new types. `MenuModel` / `MenuPage` / `MenuLine` / `MenuFragment` / `MenuAction` ([ADR-035](ADR-035-interactive-menus-book-first.md)) are sufficient.
- **`rtp-core`** — four new classes, no platform imports, no `org.bukkit.*`:
    - `MarkdownToMenuModel` — pure function `(String markdownSource, DocsLoweringOptions opts) -> MenuModel`.
    - `DocsRegistry` — in-memory cache `Map<String /*relpath*/, MenuModel>` populated on load and replaced atomically on reload.
    - `DocsConsumerProfile implements MenuConsumerProfile` (from [ADR-044](ADR-044-command-tree-menu-reflector.md)) — `suggestPrefix() = "/rtp docs "`, `commentLookup() = YamlCommentLookup.EMPTY`.
    - `DocsCmd` — `commands-api` leaf: `/rtp docs [relpath...]`. Looks up `DocsRegistry.get(relpath)`, hands it to the active `MenuRenderer`.
- **`rtp-plugin`** — extracts the bundled `docs/` tree under `<datafolder>/docs/` (reusing the existing resource-extraction pattern used for `messages.yml` and `config.yml` defaults), invokes `DocsRegistry#rebuild()` from the load and reload hooks, registers `/rtp docs`. Adds `docs.*` knobs to `config.yml`. No business logic.
- **Renderers** — `BookMenuRenderer` (Stage 4 of `docs/dev/scratch/CHECKLIST-generalized-menu.md`) and the future `ChatMenuRenderer` (Stage 5.3) render the cached `MenuModel` unchanged. No per-platform branches.
- **Lite jar** — `docs.enabled` defaults to `false` in `rtp-plugin/src/lite/resources/config.yml` (consistent with the lite scope: trimmed surface, no on-disk docs tree).

### Lifecycle: load, not invocation

`DocsRegistry#rebuild()` runs on:

1. Plugin enable, after `Configs` is loaded and `<datafolder>/docs/` has been extracted/refreshed.
2. The existing config reload path (`/rtp reload` and any future `/rtp config save`-triggered reload; see [ADR-041](ADR-041-config-command-and-save-implementation.md)).

`rebuild()` walks the configured root, lowers each `*.md` file to a `MenuModel`, and replaces the cached map under a single atomic reference swap (`AtomicReference<Map<String, MenuModel>>`). Readers (`/rtp docs` invocations) see either the old map or the new map in full — never a torn intermediate. File I/O happens on the async scheduler (`RTP.scheduler.runTaskAsynchronously`) and completes before the reload-success message is logged. If a file fails to lower (truncated, unreadable, exceeds `docs.maxFileBytes`), the registry caches a single-page `MenuModel` whose body is the localized `messages.yml → docs.lowerError` chrome, and `RTP.log(Level.WARNING, …)` records the path and cause (REQ-RTP-S-004).

`/rtp docs <relpath>` is therefore a constant-time map lookup followed by `MenuRenderer#render`. No file is opened on the command path.

### Extraction and override semantics

`rtp-plugin` extracts the jar's `docs/` tree into `<datafolder>/docs/` on enable using the same overwrite-if-jar-newer pattern that governs `messages.yml`. Operators may edit the extracted files (e.g. to localize, redact, or extend); their edits survive plugin restart as long as the jar entry's mtime does not advance past the file on disk. The `docs.root` config knob lets an operator point at a completely operator-managed directory (e.g. `plugins/RTP/custom-docs/`); the bundled tree is then ignored.

### Markdown lowering (`MarkdownToMenuModel`)

The converter targets the **subset RTP authors itself**, not arbitrary Markdown. The contract is published in this ADR and enforced by `MarkdownToMenuModelTest` (see *Test plan*); future docs added to the tree must conform.

**Supported, with high parity:**

| Markdown | Lowered representation |
|----------|------------------------|
| ATX headings `#`…`####` | Page-break candidate. `#` and `##` start a new `MenuPage`; the heading text becomes a bold/underlined chrome line (renderer-side styling on a plain `MenuFragment` whose `text` is wrapped in renderer-recognized sentinels). `###` / `####` emit a chrome line inside the current page. |
| Paragraphs (blank-line separated prose) | Word-wrapped to the renderer's line cap. Each wrapped span is one `MenuLine` with one plain `MenuFragment`. |
| Unordered lists (`-`, `*`) | Each list item becomes one `MenuLine` prefixed with `• `. One item per line; no inline structural elements. |
| Ordered lists (`1.`, `2.`) | Each item becomes one `MenuLine` prefixed with the numeric marker, original numbering preserved. |
| Block code fences (```` ``` ````) | Each code line becomes one `MenuFragment` styled as code by the renderer. Long code lines truncated with a trailing `…` and a `hover` carrying the full original line. |
| Inline `` `code` `` | Stripped of backticks; styled as code by the renderer via inline sentinels. |
| Bare absolute links `<https://…>` and `[text](https://…)` | `MenuFragment(text, hover=url, action=OpenExternalUrl(uri))`. |
| Relative links to other `.md` in the tree, `[text](relpath.md)` and `[text](relpath.md#anchor)` | `MenuFragment(text, hover=resolved relpath, action=RunRtpCommand("docs", resolvedRelpath))`. Anchor stripped in v1. |
| Bold `**…**` / italic `_…_` | Inline emphasis sentinels honored by renderers; falls through to plain text in renderers that cannot style (e.g. minimal chat fallback). |
| Horizontal rule `---` | Single chrome line. |
| Blockquote `>` | Each line prefixed with `▎ `. |

**Lowered with fidelity loss, by deliberate convention:**

| Markdown | Behavior |
|----------|----------|
| Tables (GFM pipe tables) | **Must occupy their own page** in authored docs — i.e. preceded and followed by blank lines, not inlined into a paragraph. The converter renders tables as a sequence of `MenuLine`s, one per row, with `\t`-separated columns. A table inlined with surrounding prose (no leading blank line) is treated as paragraph text and rendered verbatim with pipe characters; this is the documented "garbage in, garbage out" case. |
| HTML blocks / inline HTML | Stripped to text content; `<br>` becomes a line break, all other tags are removed. Authored docs avoid HTML. |
| Reference-style links `[text][1]` + `[1]: url` | Resolved at lowering time. Authored docs prefer inline links; reference links are supported but generate no extra chrome. |
| Images `![alt](url)` | Lowered to `[image: alt]` text with the URL in `hover` and `OpenExternalUrl` action when the URL is absolute. No image rendering. |
| Emphasis inside list markers, footnotes, definition lists, autolinks to non-http schemes | Stripped to text content. Authored docs avoid these forms. |

**Forbidden by authoring convention (enforced by review, not by the converter):**

- Tables inlined into prose paragraphs without surrounding blank lines.
- Nested lists deeper than two levels (the converter flattens; the source should not need flattening).
- Pages that depend on horizontal alignment (ASCII art, indented columns) — the renderer's wrap is the source of truth.
- Inline footnotes or sidebars that wrap the main flow.

A new top-level note will be added to `docs/dev/RULES.md` ("Authoring docs for the in-game reader") that mirrors the supported / forbidden tables above, so the rule lives with the rest of the requirement-documentation style guide.

### Page-break model

`MarkdownToMenuModel` produces a `MenuModel` whose pages are **logical** breaks — driven by `#` / `##` headings — not renderer-cap pages. The renderer paginates each logical page against its own per-page line cap (book: ~12 lines/page; chat: configurable) using `MenuAction.ChangePage` per [ADR-035 section 4](ADR-035-interactive-menus-book-first.md). This keeps the cached `MenuModel` renderer-agnostic: the same cache serves the Adventure book renderer and any future chat / GUI renderer.

Every page after the first carries a synthetic "↩ back to index" line emitting `MenuAction.RunRtpCommand("docs")` (or `"docs", "<parent>"` when navigated into from an index page).

### Index pages

`/rtp docs` with no relpath opens the docs root index. The root index is:

1. **`docs/MAP.md` lowered** if it exists. Authors of `MAP.md` keep it as a curated table of contents; the in-game index is therefore curator-controlled.
2. Otherwise, a synthetic index generated by `DocsRegistry` listing every cached relpath, alphabetized, subdirectories first (suffixed `/`), files after. Each entry is a `MenuFragment(name, hover=first non-blank line of file, action=RunRtpCommand("docs", "<relpath>"))`.

A directory relpath (`/rtp docs admin/`) opens the synthetic index for that directory. A file relpath (`/rtp docs FOR_SERVER_ADMINS.md`) opens the cached lowered model directly.

### Permission and visibility

- `rtp.docs` (default: `op`) gates `/rtp docs` itself. Hidden — not greyed out — for users without it, consistent with [ADR-044](ADR-044-command-tree-menu-reflector.md) section *Applicability semantics*.
- Developer docs (`docs/dev/`, `docs/adr/`, `docs/architecture/`) are excluded from the cache when `docs.exposeDeveloperDocs: false` (the default). Setting it `true` re-includes them on the next `rebuild()`. There is no per-section permission in v1; the boolean is sufficient for the only real cohort split (server admin vs. developer).
- Path sandboxing: every `<relpath>` is normalized and verified to remain under the configured `docs.root`. Symlinks are not followed. Sandbox violations are recorded as `RTP.log(Level.WARNING, "docs.invalidPath", …)` and surface as `messages.yml → docs.invalid` to the caller (REQ-RTP-S-004 / S-007).

### Safety inheritance

- **S-004 (no silent failures).** Lowering errors, missing files, sandbox violations, oversized files all log via `RTP.log(Level.WARNING, …)` and surface a configurable user-facing message. No silent `return`.
- **S-005 (no main-thread chunk I/O).** No chunk I/O is performed at any phase. File I/O happens exclusively on the async scheduler during `rebuild()`; the command path is a cache lookup.
- **S-006 (require-by-contract API entry points).** `DocsRegistry#get` throws `IllegalStateException` if invoked before `rebuild()` has completed at least once.
- **S-007 / REQ-RTP-F-013 (configurable messages).** All chrome strings (`docs.title`, `docs.invalid`, `docs.notFound`, `docs.tooLarge`, `docs.lowerError`, `docs.back`, `docs.empty`, `docs.disabled`) live in `messages.yml`.

### Configuration surface (`config.yml`)

```yaml
docs:
  enabled: true                # false in lite assembly
  root: ""                     # empty → <datafolder>/docs/; absolute or relative path otherwise
  exposeDeveloperDocs: false   # include dev/, adr/, architecture/ subtrees
  maxFileBytes: 262144         # 256 KiB hard cap per file; oversized → docs.tooLarge chrome
  excludeGlobs: []             # additional glob patterns to skip during rebuild
```

All four chrome strings under `docs.*` and the `messages.yml → docs.*` block are added in the same beta.3 cycle.

### Test plan

New tests under `rtp-core`:

- `MarkdownToMenuModelTest` — table-driven over the *Supported* / *Lowered with fidelity loss* tables: heading-to-page-break, list bullets, code fences (including long-line hover truncation), inline `code`, absolute and relative links, GFM table on its own page vs. inlined, image alt text, blockquote prefix. One case per row of each table above.
- `DocsRegistryTest` — `rebuild()` is atomic (a concurrent reader sees either the pre- or post-rebuild map, never a partial); oversized files produce the `docs.tooLarge` placeholder model; sandbox-violating relpaths fail through to `docs.invalid`; `exposeDeveloperDocs=false` excludes the gated subtrees.
- `DocsCmdTest` — `/rtp docs` opens the root index; `/rtp docs FOR_SERVER_ADMINS.md` opens the cached model; `/rtp docs ../escape` is rejected; `rtp.docs`-less callers are routed to `docs.disabled` (or hidden, depending on registration form).

Traceability ([TRACEABILITY.md](../dev/TRACEABILITY.md)): add rows for the three test classes referencing REQ-RTP-S-004 (lower-error logging), REQ-RTP-S-005 (no main-thread I/O on command path; document the load-time-only constraint), REQ-RTP-S-006 (`DocsRegistry#get` pre-load contract), REQ-RTP-S-007 / REQ-RTP-F-013 (configurable chrome).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Convert Markdown on every `/rtp docs` invocation | Defeats the project's load-time-only file-handling convention; pushes parser cost into the command hot path; gains nothing because docs do not change between reloads. |
| Read Markdown from inside the jar at runtime instead of extracting | Operators cannot override or localize. Inconsistent with `messages.yml` extraction. Jar reads are also harder to bound and recover from on a malformed entry. |
| Use a third-party Markdown parser (CommonMark, flexmark) | Pulls a large dependency tree into `rtp-core`, which must stay platform-free and lean. The lowering target is plain text plus a handful of action sentinels; the project owns the input format and can author to a constrained subset. |
| Render Markdown to Adventure components directly, skipping `MenuModel` | Couples `rtp-core` to Adventure (and to renderers). The whole point of `MenuModel` ([ADR-035](ADR-035-interactive-menus-book-first.md)) is renderer-neutrality; bypassing it would mean a second presentation pipeline. |
| Generate a synthetic index only, no `MAP.md`-driven curation | Removes operator/author control of the in-game reading order. `MAP.md` already exists as the curated entry; lowering it is one extra line of code. |
| Per-file permission keys (e.g. `rtp.docs.admin.proxies`) | Over-engineered for v1. The single `docs.exposeDeveloperDocs` boolean covers the only real cohort split. Per-file permissions can be added later without an ADR superseding this one (additive surface). |
| Grey out (disable) inaccessible docs in the synthetic index | Same trade-off [ADR-044](ADR-044-command-tree-menu-reflector.md) settled: hidden, not disabled. Avoids click-then-reject UX. |
| Cache the rendered Adventure book instead of the `MenuModel` | Would force one cache per renderer and re-build on renderer swap. The `MenuModel` is small, immutable, and exactly the renderer-neutral representation we already pay to maintain. |
| Inline tables mixed with prose, supported via best-effort rendering | The fidelity cost is genuinely large for negligible authorial convenience. Documented as a forbidden authoring pattern; the converter renders them verbatim (pipes and all) as the explicit "do not do this" signal. |
| Support arbitrary Markdown (autolinks to mailto:, definition lists, footnotes, etc.) | Each addition expands the converter and the test matrix. RTP's own docs do not use these constructs; adding them is a future-ADR concern when a real authoring need lands. |
| Build the cache lazily on first invocation | Defers visible parser errors until a player opens the menu, rather than surfacing them at load when an operator can read the log. Worse operator experience for no runtime saving. |
| Ship `MarkdownToMenuModel` in `rtp-api` for addon reuse | Premature, parallels [ADR-035](ADR-035-interactive-menus-book-first.md)'s reasoning for keeping renderers `rtp-core`-internal-public. Lock the SPI after at least one external consumer asks for it. |

## Consequences

- **Positive:**
  - Operators read RTP docs in-game without leaving the server.
  - Markdown parsing cost is paid exactly twice per operator session (enable + reload), not per click.
  - The runtime command path is a `Map#get` plus a renderer call — trivially testable, trivially fast, and trivially safe against S-005.
  - The lowering target is a documented subset, so authoring drift is caught by `MarkdownToMenuModelTest` rather than by users seeing broken pages.
  - Zero new types in `rtp-api`; ADR-035's surface is reused verbatim. `rtp-core` stays free of Markdown libraries and of `org.bukkit.*`.
  - The synthetic-index fallback means a sparse `docs/` tree still produces a usable menu without authoring `MAP.md`.
  - `docs.root` lets operators ship completely custom doc trees (e.g. server-specific rules pages) using the same renderer.

- **Negative / Trade-offs:**
  - Docs edited on disk between reloads are not visible until the next reload. Acceptable: matches the rest of RTP's config model and is documented in `messages.yml → docs.staleHint`.
  - Authoring discipline is required: tables and HTML must be confined to their own blocks. Mitigated by adding the authoring rules to `docs/dev/RULES.md` and by making the violation cases produce visibly-degraded output (so a reviewer notices in PR).
  - The in-house converter must be maintained as authored docs adopt new constructs. Bounded scope: one class plus its test.
  - Anchored relative links (`foo.md#section`) lose anchors in v1. Real navigation is per-page; readers can scroll. Anchor support is a future addition if `MAP.md` or `FRONT_PAGE.md` accumulates enough internal anchors to make it worth the implementation.
  - Memory cost: every lowered `MenuModel` lives in heap until shutdown. For the current `docs/` tree this is on the order of tens of KiB; for `docs.maxFileBytes = 256 KiB` and the existing ~40 lowered files, ≪ 10 MiB worst case. Negligible against the existing config registry.
  - Lite assembly ships with `docs.enabled = false`; lite users see `messages.yml → docs.disabled` if they invoke `/rtp docs`. Documented in `docs/FRONT_PAGE_LITE.md`.

## Migration / Rollout

- Beta.3 ships `MarkdownToMenuModel`, `DocsRegistry`, `DocsConsumerProfile`, `DocsCmd`, the `docs.*` block in `config.yml` (full assembly) / `config.yml` (lite, `enabled: false`), and the `messages.yml → docs.*` block. The bundled `docs/` tree is added to `rtp-plugin/src/main/resources/docs/` so it ships in the jar; extraction reuses the existing resource-extraction code path.
- Authoring rules added to `docs/dev/RULES.md` and cross-linked from `docs/dev/INDEX.md` under a new "Authoring docs for the in-game reader" row.
- `docs/adr/README.md` *Project ADRs* table receives a row for ADR-045 in the same change.
- Order of arrival: this ADR's implementation **must follow** Stage 2 ([ADR-035](ADR-035-interactive-menus-book-first.md) token registry + redeem) and Stage 4 (`BookMenuRenderer`) of `docs/dev/scratch/CHECKLIST-generalized-menu.md`, because both are hard prerequisites. A new Stage 5.7 row is added to that checklist for tracking.
- Traceability ([TRACEABILITY.md](../dev/TRACEABILITY.md)): add `MarkdownToMenuModelTest`, `DocsRegistryTest`, `DocsCmdTest` rows referencing REQ-RTP-S-004, REQ-RTP-S-005, REQ-RTP-S-006, REQ-RTP-S-007, REQ-RTP-F-013.

## References

- [ADR-011](ADR-011-rtp-api-separate-module.md) — `rtp-api` layering. Keeps Markdown lowering out of `rtp-api`.
- [ADR-035](ADR-035-interactive-menus-book-first.md) — Interactive menus via written book. Defines `MenuModel` and the renderer pipeline this ADR consumes.
- [ADR-041](ADR-041-config-command-and-save-implementation.md) — Config command and save implementation. The reload path that triggers `DocsRegistry#rebuild()`.
- [ADR-042](ADR-042-yaml-comment-preservation-block-only.md) — Block-comment preservation. Cross-reference only; docs lowering does not touch YAML.
- [ADR-044](ADR-044-command-tree-menu-reflector.md) — Command-tree menu reflector. Defines `MenuConsumerProfile`; `DocsConsumerProfile` is the second concrete profile after `ConfigMenuConsumerProfile`.
- [REQUIREMENTS.md section 3](../dev/REQUIREMENTS.md) — Prohibitions; this ADR inherits S-004, S-005, S-006, S-007.
- [`docs/dev/scratch/CHECKLIST-generalized-menu.md`](../dev/scratch/CHECKLIST-generalized-menu.md) — Stage 5.7 entry tracks implementation.
- [TRACEABILITY.md](../dev/TRACEABILITY.md) — new test rows added as part of beta.3.
