# ADR-042 — YAML Comment Preservation: Block-Only, Surgical Text-Edit Substrate

**Status:** Proposed
**Date:** 2026-05-14
**Target release:** post-`3.0.0-beta.3` (after ADR-037 / ADR-041 land; closes `CONFIG_COMMAND_SPEC.md` Appendix A12)
**Supersedes / amends:** scopes ADR-025 (SnakeYAML wrapper) by deciding *how* the wrapper exposes comments on write.

---

## Context

`CONFIG_COMMAND_SPEC.md` §2.4 (`view` sub-sub-command) and Appendix A12 flag that the hardened `/rtp config` UX wants to show, on hover, the YAML comment associated with each configuration key, and to round-trip that comment unchanged through the seven-stage save lifecycle (`SPEC §3`). The current YAML substrate (`org.simpleyaml` `YamlFile.loadWithComments()` + `YamlFile.save()`, inherited from v2 and described in `docs/dev/scratch/CONFIG_COMMENT_PRESERVATION_COMPARISON.md`) **cannot guarantee** this round-trip:

- `loadWithComments()` populates an in-memory comment side-table keyed on node identity. Any `set(...)` that re-emits a scalar may detach the comment.
- `setSection(...)` recursion used for factory values (`shape`, `vert`) replaces whole subtrees; interior comments are lost wholesale.
- Lists with interspersed comments lose ordering or drop comments on re-emit.
- Inline (same-line trailing `# …`) comments are the worst case and are routinely dropped on rewritten lines.

The v1 line-rewrite approach (Bukkit `YamlConfiguration` + textual line-by-line patch) preserved block comments and inline tails on flat top-level scalars but fell apart on nested keys, lists, and any non-default layout — which is why v2 moved to `simpleyaml` in the first place.

Both prior attempts fail at the same boundary: **the moment the writer materializes the file from a data model, comment ownership becomes a best-effort heuristic.** No off-the-shelf JVM YAML library currently round-trips inline comments through `set + save` on rewritten scalars reliably (snakeyaml-engine's comment events are read-side primitives; SnakeYAML 2.x classic strips on emit; simpleyaml is documented above).

### Scope decision (user, 2026-05-14)

The A12 problem is narrowed:

- **In scope:** **block** (above-line, whole-line) comments attached to a following key. These are the comments the shipped `config.yml` / `regions/*.yml` / `worlds/*.yml` defaults use to explain each option. Their preservation through `/rtp config set` is the user-visible contract `view`'s hover-text needs.
- **Out of scope:** **inline** (same-line `key: value  # tail`) comments. These will be declared **unsupported** — any inline tail on a key that `/rtp config set` mutates will be dropped on write. Existing inline tails on **untouched** keys may survive incidentally but the contract does not promise it.

This narrowing matters because it removes the lexically-hardest case (re-emitting the same line with a different value while keeping the trailing fragment intact) and lets the design focus on the easier, structurally-stable case: whole-line comment blocks immediately preceding their owning key.

---

## Decision

Replace the `simpleyaml`-based write path with an **in-house surgical text-edit substrate** built on top of the SnakeYAML wrapper introduced by ADR-025. Treat the on-disk file as the source of truth; the in-memory `YamlFile`-equivalent is a *read* view only. Comment ownership is computed once at load time by an AST walk over SnakeYAML Engine's `Parser` event stream and stored in a side-table keyed by **canonical key path** (e.g. `regions.default.shape.radius`), not by node identity.

### 1. Comment-ownership model (block-only)

Each YAML file is decomposed at load into:

- A **node tree** (standard parsed YAML) for read access.
- A **line index**: array of `(lineNumber, kind, content)` where `kind ∈ {KEY, LIST_ITEM, COMMENT, BLANK, OTHER}` reproduced verbatim from the file bytes.
- A **block-comment side-table** `Map<KeyPath, List<String>>`:
  - For each `KEY` line at line `L`, walk backwards consuming contiguous `COMMENT` and `BLANK` lines at the **same indentation level or shallower**. The captured run, trimmed of trailing blanks, is the key's *leading comment block*.
  - Stop at the first non-comment, non-blank line, or at a `KEY` / `LIST_ITEM` at a deeper indent (those belong to the previous key).
  - Top-of-file comments (before any key) are stored under the synthetic path `""` (file header).
- **Inline comments are not stored.** Anything after a `#` on a `KEY` or `LIST_ITEM` line is discarded from the side-table. (It still exists in the original file bytes until a write touches that line.)

This model is **stable under `set`**: the key path is a deterministic identifier independent of value re-emission, and the leading-block heuristic is purely positional, not lexical.

### 2. Write path (surgical text edit)

`AtomicConfigWriter` (named in ADR-041 §"New classes") is implemented as a text editor, not a serializer:

1. Load the current file bytes (always; the cached read-view is never the write source).
2. For each `set(path, value)` in the `ConfigTransaction`:
   a. Locate the existing key's **line range** in the file via the line index (the key's own line, plus any continuation lines for multi-line scalars / block lists).
   b. Emit the **new value** for that key using the SnakeYAML wrapper's scalar/sequence/mapping emitter, then strip the emitter's leading `key:` prefix (we already have one in place) and reuse the existing key's indentation.
   c. Splice the new value lines into the file in place of the old line range. **Leave the preceding block comment lines untouched.**
   d. If the key did not previously exist, locate the parent section's line range, find the last child of that section (or the section header line if empty), and insert the new `key: value` lines after it — preceded by a single blank line if the convention in this file places blanks between top-level keys. The new key has no leading block comment unless the caller explicitly supplied one (out of scope for `/rtp config set` in beta.3).
3. After all mutations, write the patched byte sequence to a sibling `.tmp` file (`AtomicConfigWriter` already owns this), `fsync`, and atomically rename per ADR-041 §"Concurrency model".

This path **never re-emits unmodified keys, blank lines, or comment blocks**. Bytes outside the affected line ranges are byte-identical to the input. That is the contract.

### 3. Section / factory-value replacement

For nested mutations that today go through `setSection(...)` (e.g. setting `shape: {radius: 100, …}` wholesale):

- The transaction must decompose the section write into **leaf-level** `set` calls (one per scalar in the section).
- The writer never deletes an entire subtree's line range; it only edits leaf lines and inserts/removes individual leaf keys.
- A `remove(path)` call on a non-leaf is rejected with `reasonCode = SECTION_REMOVAL_UNSUPPORTED` (added to `ConfigReasonCode`). Removing a whole section is an `admin/restoreDefault` operation, not a `/rtp config set` operation.

This eliminates the v2/v3 "section-replacement wipes interior comments" failure mode by construction.

### 4. List mutations

`add:` and `remove:` (already in SPEC §2.2) operate on individual list items:

- `add:` appends a new `- item` line at the list's current end, matching the list's indentation. No comment is associated with the new item.
- `remove:` deletes the matching `- item` line. If the line immediately above the removed item is a `COMMENT` line at the same indent and the line immediately below the removed item is also a `LIST_ITEM`, the comment is treated as belonging to the **next** item and is preserved. If the comment is the only thing between the removed item and the next non-list line, it is treated as belonging to the removed item and is also removed.

Wholesale list replacement is not exposed at the command surface and is rejected with `reasonCode = SECTION_REMOVAL_UNSUPPORTED` (sharing the code with whole-section replacement).

### 5. Read path / `view` hover-text

The `view` sub-sub-command (SPEC §2.4) consults the block-comment side-table:

- `view <key>` returns `(declaredType, bounds, currentValue, leadingBlockComment | null)`.
- The hover-text formatter joins the leading-block lines with `\n`, stripping the leading `# ` from each.
- If `leadingBlockComment` is `null` (no comment present, or the key was added at runtime without a comment), the hover degrades to declared-type + bounds, as already promised in SPEC §2.4.

A **shipped-default comment dictionary** baked into the jar (one comment block per known config key, harvested at build time from the jar-shipped default YAML files) is a fallback when the user's file has no comment for a key (e.g. because they hand-deleted it). This dictionary is read-only and lives at `rtp-core/src/main/resources/config-comments.properties` (one entry per dotted key path, value is the block-comment text with embedded `\n`). Build-time generation is a Gradle task `:rtp-core:generateConfigCommentDictionary` that walks `rtp-core/src/main/resources/*.yml` and emits the properties file; the runtime loader reads it once at startup into a `Map<KeyPath, String>`.

The view UX is therefore robust against on-disk comment loss: the user can wipe their `config.yml`'s comments entirely and `view` still works.

### 6. What this ADR does **not** do

- It does not promise inline-comment preservation. Inline tails on `set`-touched keys are dropped. Inline tails on untouched keys survive only because their lines are not in any mutation's line range — that is incidental, not contractual.
- It does not change ADR-025's choice of SnakeYAML as the parser. It scopes ADR-025 by deciding the **emit** strategy: do not use a serializing emitter for whole files; use the parser's event stream to build the line index, and write via text splice.
- It does not address backup rotation (`.old1..N`) — that is ADR-041's concern (`AtomicConfigWriter` + Appendix A11 cleanup).
- It does not address version-bump rebase (v2's `renameFiles` + jar re-extract behavior). A 3-way merge on version bump is a follow-up concern; the current behavior (rotate the user's file aside, extract the new jar default) is preserved unchanged. Block comments survive a version bump because the new file is the jar default and the jar default has comments; the user's edits are in the rotated `.old1`.

---

## Alternatives Considered

1. **Stay on `simpleyaml` and accept the failure modes.** Rejected: SPEC §2.4 hover-text becomes a permanent best-effort, every `setSection` wipes interior comments, and the user has explicitly said block-comment loss is the main problem to solve. No path forward without a substrate change.

2. **Adopt a third-party comment-preserving YAML library wholesale** (e.g. `snakeyaml-engine` + a community comment-preserving emitter, or YamlBeans, or a Java port of `ruamel.yaml`). Rejected for now:
   - `snakeyaml-engine`'s comment events are exposed on the **read** side; its emitter does not faithfully round-trip them across all node kinds.
   - YamlBeans does not preserve comments on emit.
   - No JVM library currently delivers what we need without an in-house adaptation layer. If we are going to write that layer either way, it is simpler to write it directly against SnakeYAML's parser (ADR-025's choice) than to maintain a forked third-party comment emitter.
   - Re-evaluate if a comment-preserving YAML emitter library matures.

3. **Generalize v1's line-rewriting approach to nested keys without a real AST** (regex-based). Rejected: nesting + lists + multi-line scalars + quoted-vs-unquoted strings + the `>`/`|` block-scalar indicators make this brittle in exactly the same way v1 was brittle on lists. We need a real parser to compute line ranges; once we have one, we use it.

4. **Bake all comments into the jar dictionary only; stop trying to preserve user-edited comments at all.** Rejected: users who hand-edit `config.yml` to add `# my deployment note` expect that note to survive `/rtp config set`. The dictionary covers the `view` UX (#5 above) but is not the primary source for already-present block comments.

5. **Support inline comments anyway via a "trailing token" pass.** Rejected per user scope decision. The lexical complexity of preserving a `# tail` across a value rewrite (especially when the new value's emit changes the line's column count) is the v1/v2 nemesis and the user has explicitly chosen to drop the feature rather than carry the maintenance cost.

6. **Use a `git`-style 3-way merge of (jar default, last-saved user file, current user file) on every save.** Rejected: solves a different problem (version-bump comment drift), is much more code, and does not address the per-write `setSection`-wipes-interior problem. May be revisited as a follow-up ADR for version-bump UX.

---

## Consequences

### Positive

- Block-comment round-trip becomes a **byte-level guarantee**, not a heuristic: lines outside mutation ranges are unchanged.
- `setSection`-style wholesale subtree replacement is eliminated; interior comments of `shape` / `vert` / similar factory-value sections survive any `set`.
- `view` hover-text always has a comment to show (user's file or jar dictionary fallback).
- The write path no longer depends on `simpleyaml`'s comment side-table; the ADR-025 SnakeYAML wrapper becomes the only YAML dependency.
- Atomic write + comment preservation compose cleanly: the writer produces a byte sequence; `AtomicConfigWriter` writes it to `.tmp` and renames.

### Negative / risks

- Implementation cost is non-trivial: line index, block-comment side-table, surgical splice writer, jar-dictionary build task. Estimated ~6–8 new classes in `rtp-core/.../configuration/text/` plus a Gradle task and a test suite.
- Inline-comment loss must be documented in `messages.yml` and admin docs so users are not surprised. Mitigation: SPEC §2.4 already softens the hover-text language; this ADR makes the inline-drop explicit.
- The line index makes assumptions about file encoding (UTF-8, LF or CRLF) and indentation (spaces, not tabs — YAML 1.2 forbids tabs anyway). Tab-indented files are rejected at load time with `reasonCode = MALFORMED_YAML`.
- A pathological user file (e.g. flow-style mapping `{a: 1, b: 2}` at top level) has no per-key line range. Such files are loaded read-only and any `/rtp config set` against them returns `reasonCode = UNSUPPORTED_FILE_LAYOUT`. The shipped default files are all block-style; this only affects user files re-formatted to flow style.

### Migration

- Phase 1 (next, post-beta.3): Implement the line-index loader + block-comment side-table + jar dictionary. Make `view` consume them. No write-path change yet. Tests assert the side-table is correct on every shipped default file.
- Phase 2: Implement the surgical write splicer for scalar `set` on existing keys. Run alongside the existing `simpleyaml` writer; gate by config flag for soak testing.
- Phase 3: Implement add-key, add/remove list item, and the `SECTION_REMOVAL_UNSUPPORTED` rejection.
- Phase 4: Remove `simpleyaml` from `rtp-core`'s dependency graph; close ADR-025 as Accepted (wrapper choice) + this ADR as Accepted (write strategy).

Each phase ships with its own test class under `rtp-core/src/test/java/.../configuration/text/`. The phase split is intentional so that the SPEC §2.4 hover-text improvement (Phase 1) ships independently of the write-path change (Phase 2+).

---

## References

- `docs/dev/CONFIG_COMMAND_SPEC.md` §2.4 (`view` UX), Appendix A12 (this ADR closes A12).
- `docs/adr/ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md` (parser substrate; this ADR scopes its emit strategy).
- `docs/adr/ADR-037-harden-rtp-config-commands.md` (decision to harden), `docs/adr/ADR-041-config-command-and-save-implementation.md` (`AtomicConfigWriter` / `ConfigTransaction` ownership).
- `docs/dev/scratch/CONFIG_COMMENT_PRESERVATION_COMPARISON.md` (v1/v2/v3 evidence; can be deleted once this ADR is Accepted).
- Prior failure modes: v1 `Config.update()` (`origin/V1`), v2 `ConfigParser.save()` / `YamlFileDatabase.connect()` (`origin/V2`), v3 same files in `rtp-core`.
