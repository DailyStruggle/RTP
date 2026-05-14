# Config Comment-Preservation: v1 vs v2 vs v3

**Status:** scratch / research note, 2026-05-14
**Motivation:** `CONFIG_COMMAND_SPEC.md` Appendix A12 — the spec's `view` UX (hover-text shows the YAML comment for each key) depends on round-trip comment preservation through save. Today the round-trip is not guaranteed. Before designing a fix, look at what v1 and v2 attempted, what worked, and what broke.
**Scope:** comment fate through `<load → mutate → save>` for the user's installed config (not the jar-shipped default). Backup rotation and atomic-write are noted in passing where they intersect with comment handling.

**Scope decision (2026-05-14, user):** **Block (above-line) comments are the in-scope problem to solve.** Inline (same-line `key: value  # comment`) comments are **explicitly out of scope** — the A12 ADR will declare them unsupported. This narrows the design problem substantially: comment ownership only needs to be stable for *whole-line comment blocks attached to a following key*, not for trailing tails of rewritten value lines. Everything below should be read with that constraint in mind; sections discussing inline-comment fragility remain as historical evidence for *why* we are dropping inline support, not as problems still to be solved.

---

## TL;DR

| Version | YAML substrate | Comment-preservation strategy | What it actually preserved | Main failure modes |
|---|---|---|---|---|
| **v1** | Bukkit `YamlConfiguration` (strips comments on load) | Re-read the **user's file** as plain text lines; for each line, if it `startsWith("<node>:")`, replace the value tail and keep the rest of the line; pass other lines (incl. comment lines) through untouched. | Block comments and inline comments on **top-level scalar** keys, plus all standalone comment lines. | Nested keys not addressed (the `startsWith` match is anchored at column 0). Lists are rebuilt from scratch — any inline/per-item comment inside a list is lost. Manual quoting heuristics for `Material` vs free strings. No atomic write. Version line hardcoded into the rewrite. |
| **v2** | `org.simpleyaml.configuration.file.YamlFile` (`simpleyaml`), invoked as `loadWithComments()` + `save()` | Library does the round-trip; v2 caches one `YamlFile` per file in `YamlFileDatabase` and saves the cached instance. On version bump, `renameFiles()` rotates the user's file to `.old1..N` and re-extracts the jar default (**user comments lost on version bump**, by design). | Above-line block comments and (mostly) inline comments on simple scalar keys, on **both top-level and nested keys**, as long as the file is *never* round-tripped through a path that goes back to the jar default. | (a) Inline (`# trailing`) comments on lines whose value gets rewritten by `set(...)` are unreliable — simpleyaml's comment-binding heuristic can detach the trailing comment from the line when the scalar is re-emitted. (b) Comments inside list bodies (per-item or between items) get reordered or dropped. (c) Any `ConfigurationSection` path that goes through `setSection(...)` recursion (used for `shape`/`vert` factory values) replaces the whole subtree and drops the section's interior comments. (d) Version-bump path re-extracts from jar (intentional, but a real user-comment loss event). (e) No atomic temp+rename — `YamlFile.save()` writes in place. |
| **v3 (current)** | Same simpleyaml `YamlFile`, same `loadWithComments` + `save` (inherited from v2). | Same as v2, with the same caching layer (`YamlFileDatabase.cachedLookup`). Update path uses an intermediate `baseline.loadWithComments()` to merge jar defaults with the on-disk file before re-saving. | Same surface as v2. The merge-from-baseline path was an improvement intent (avoid wholesale jar re-extraction on version bump) but its comment fidelity is bounded by the same simpleyaml inline/list limitations. | Same a–d as v2. Plus: `SubConfigCmd` / `ListCmd` mutate via `YamlFile.set(...)` without re-loading-with-comments first, so any path that has lost the cached `YamlFile`'s comment side-table (e.g. a write that went through `getMapValues(true)` then a `set`, or a clone path) saves a comment-free file. |

**One-sentence lesson for the spec:**
Library-level "preserve comments" support (v2/v3 via simpleyaml) is necessary but **not sufficient** — inline comments on rewritten scalars, comments inside lists, and any path that replaces a whole `ConfigurationSection` will still lose comments regardless of the library's nominal capability. The robust fix is the same one v1 stumbled onto in primitive form: never re-emit the file from a data model; instead, mutate the **on-disk text** with surgical line/range edits and only fall back to a full re-emit when the user file diverges from a parseable shape.

---

## v1 — surgical line-level rewriting on top of a comment-stripping library

### How

- Library: Bukkit `YamlConfiguration`, which discards comments on load.
- Save path (`Config.update()` in `leafcraft/rtp/tools/configuration/Config.java`):
  1. Call `FileStuff.renameFiles(plugin, "config")` to rotate older `.old*` files.
  2. Snapshot the in-memory values into `oldValues = config.getValues(false)`.
  3. Re-open the **user's `config.yml`** with a `Scanner` and load every line into `ArrayList<String> linesInDefaultConfig` (note: misnamed — these are the user's lines, not the shipped default).
  4. For each line:
     - If `startsWith("version:")`, replace with the new version literal.
     - Else if `startsWith("  -")` (a list item), drop it — list items are rebuilt from the in-memory list when the parent key line is rewritten.
     - Else, for each known top-level key, if `line.startsWith(node + ":")` replace the value tail by stringifying `oldValues.get(node)`:
       - For lists: rebuild as `<node>: \n  - item1\n  - item2 …`, with per-item quoting heuristic (`Material.getMaterial(item) == null ? "\"…\"" : item`) to keep enums unquoted and string values quoted.
       - For strings: emit `node: "value"`.
       - For everything else: emit `node: value`.
  5. Write the resulting lines back to `config.yml` with a `FileWriter` (no temp file, no rename, no fsync).

### What it preserved

- **Standalone comment lines** anywhere in the file: passed through untouched.
- **Inline trailing comments on a scalar top-level key** — because the rewrite only replaces from `<node>:` to the end-of-value; the trailing `# comment` is part of the same line **only if** the rewrite logic doesn't blindly overwrite from `<node>:` to end-of-line. Looking at the actual code (`newline = new StringBuilder(node + ": " + ...)`) it **does** overwrite the entire line — so v1's inline-comment story is worse than the file-format would in principle allow.
- **Block comments above a line**: yes, preserved.

### Where it broke

- **Nested keys**: a key inside a section was matched by `startsWith("section:")` at column 0 and rebuilt as a top-level entry. The result was either a no-op (no match) or a corrupted file. v1's config schema was almost entirely flat, which is why this didn't blow up in production.
- **Lists with inline comments**: lost. Lists were dropped and rebuilt; only the values survived, and only as bare items with the auto-quote heuristic.
- **Quoting heuristic**: `Material.getMaterial(str) == null ? quote : noquote` is fragile across MC versions (a previously-known Material becoming unknown re-quotes its serialization).
- **Atomicity**: none. A crash mid-`FileWriter` truncated the user's config in place.
- **Backup**: `FileStuff.renameFiles` rotated `config.yml` → `config.old1` → `config.old2 …` before writing, so a partial save could be recovered manually. Not the same thing as atomic rename, but it did mean the previous good copy was always one filename away.

### Lessons for v3

1. **Standalone comment lines are easy** — pass-through works as long as you don't blow away the whole file. v1 got this right despite using a comment-stripping library.
2. **Per-key surgical edits** beat "deserialize-then-re-emit" if you want inline comments. v1 did this in the crudest possible way (`startsWith` line match) and still preserved more than a naive `YamlConfiguration.save()` would.
3. **Lists are the hard part.** v1 chose to drop and rebuild; v2/v3 inherited the same compromise via simpleyaml.

---

## v2 — outsource to simpleyaml; accept its limits

### How

- Library: `org.simpleyaml:simpleyaml`, which has a documented "load/save with comments" mode (`YamlFile.loadWithComments()`).
- Save path (`ConfigParser.save()` in `src/main/java/io/github/dailystruggle/rtp/common/configuration/ConfigParser.java`):
  1. `YamlFileDatabase.connect()` walks `pluginDirectory`, opens each `.yml` as a `YamlFile`, calls `loadWithComments()` on it, and caches the instance in `cachedLookup` (an `AtomicReference<Map<String, YamlFile>>`).
  2. `set(E key, Object value)` mutates the **cached YamlFile instance** via `yamlFile.set(key.name(), value)`. For `ConfigurationSection` values (e.g. `shape`, `vert`) it recurses via a private `setSection(...)` that re-walks the subtree and `set`s each leaf.
  3. `save()` calls `cachedLookup.get().get(name).save()` — the library serializes the in-memory `YamlFile`, comments and all, back to disk in place.
- Version-bump path (`check(...)` → `update()` → `renameFiles()`):
  - On a version mismatch, the user's file is rotated to `.old1..N` (rotating the existing `.old*` chain upward, top-down to avoid overwrites) and the shipped jar default is re-extracted as the new `name`. The user's comments are lost intentionally — the assumption is that the **jar default's** comments are more useful than a stale custom file.

### What it preserved

- **Above-line block comments** on scalar keys at any nesting depth: well preserved by simpleyaml across `set → save` round-trips.
- **Most inline trailing comments on simple top-level scalars** that are not touched by a `set`: preserved.
- **Inline trailing comments on scalars that ARE touched by a set**: **fragile** — simpleyaml's comment-binding code attaches the trailing `# …` to a "comment side-table" keyed by node identity; when the scalar value is re-emitted with a different lexical form (e.g. `123` → `"123"` due to a type-narrowing `set`), the rebinding heuristic sometimes drops the trailing comment.

### Where it broke (or remained brittle)

a. **Inline comments on rewritten scalars** — as above. The user-visible symptom is "I edited one value via `/rtp config` and the `# explanation` next to a different, neighboring value disappeared," because simpleyaml re-emits the section, not just the one line.
b. **Comments inside list bodies** — simpleyaml's list-comment support is partial; comments interspersed between list items (e.g. `# vanilla biomes ↓`) can move, fuse with the next item, or vanish when the list is re-emitted.
c. **`setSection(...)` recursion for factory values** (`shape`, `vert`) replaces a whole subtree by reconstructing it from a `Map<String, Object>` — *interior* comments of that subtree are not in the side-table the same way (the subtree's `ConfigurationSection` is replaced wholesale) and are lost.
d. **Version-bump rotation** intentionally discards the user's file in favour of the jar default. Comments the user added between releases are lost on every minor version bump.
e. **No atomic write**: `YamlFile.save()` truncates and writes in place. A crash mid-save corrupts the file; `.old1` may be a useful previous snapshot if `renameFiles()` ran in this cycle (only on version bumps — not on routine `/rtp config set`).

### Lessons for v3

1. `loadWithComments` is a **necessary** primitive but not a complete solution. Treat its output as best-effort: covers most above-line block comments, partial inline, near-zero coverage on intra-list and replaced-section interiors.
2. The `setSection` factory-value path is the single biggest in-process comment loser. If we want to keep comments on the interior of `shape`/`vert` sections, we must avoid wholesale subtree replacement and instead set individual leaf scalars under the section.
3. The version-bump rebase-to-jar-default policy is a deliberate user-comment loss event. If users care about their inline notes surviving a release, this policy must change (3-way merge, or explicit "keep user, discard jar").
4. Backup rotation `.old1..N` is good provenance even without atomic-rename, but **only fires on version bump**, not on routine writes — so a crash mid-`save()` during a `/rtp config set` has no recovery path.

---

## v3 (current) — same simpleyaml core, more elaborate update path, same gaps

### How (delta from v2)

- Same `simpleyaml YamlFile` + `loadWithComments()` + `save()` substrate (see `rtp-core/.../configuration/ConfigParser.java`).
- Same `YamlFileDatabase.cachedLookup` caching pattern.
- New: an intermediate "baseline" path inside `extractLocalizedResource` / the update logic that copies the jar resource to a temp `YamlFile`, `loadWithComments()`s it, and merges keys back, instead of v2's wholesale rotate-and-re-extract. This was intended to **reduce** the v2-era version-bump comment loss.
- `LanguageBootstrap` pre-parser bootstrap loads `language.yml` before the rest of the parser graph (ADR-020) — a special path that historically didn't go through the cached `YamlFile`.

### What it preserves

- Same surface as v2: above-line block on scalars, most untouched inline comments, partial-to-nothing on rewritten scalars, lists, and replaced sections.

### Where it still breaks

- All of a–e from the v2 list, plus:
- **Mutations that bypass the cached `YamlFile`'s comment side-table**: any code path that loads values via `getMapValues(true)`, mutates a primitive map, and then writes back, loses comments — because the side-table is keyed on the original `YamlFile` instance. Suspect paths: `SubConfigCmd` / `ListCmd` mutate the cached `YamlFile` directly today, but the **`clone()` path** (`ConfigParser.clone()` calls `check(...)` again on the clone, which can reopen the file fresh) re-binds the comment side-table to a new instance, leaving the original (still-cached, still-mutated) instance's comment data stale.
- **No atomic write** still: same in-place truncate-and-rewrite as v2. This intersects directly with the planned `ConfigTransaction` / `AtomicConfigWriter` work in ADR-041 — the temp+fsync+rename has to happen **inside** the simpleyaml save call (i.e. `save(File tempFile); atomicMove(tempFile, finalFile)`) rather than letting simpleyaml write the final path directly.
- **The user's prior partial-fix attempt for above-line comments** (noted by the user, not visible in current source as a finished feature) suggests there is or was an experimental path that tried to capture the block-comment above a key independently and reattach it on save; it was abandoned because the same heuristic doesn't generalize to inline (same-line) comments, where the comment is lexically inside the same line being rewritten.

---

## What we should take into ADR-A12-followup

(For when we open the dedicated YAML-substrate ADR that closes `CONFIG_COMMAND_SPEC.md` Appendix A12.)

1. **Don't expect any off-the-shelf YAML library to perfectly round-trip inline comments through `set + save` on rewritten scalars.** simpleyaml is the best of a weak field; snakeyaml-engine has experimental comment preservation but the same inline-on-rewrite caveat; SnakeYAML 2.x classic does not preserve at all.
2. **The robust strategy is text-surgery, not data-model-round-trip.** Treat the on-disk file as the source of truth; the in-memory model is a *query* view. For a `set`:
   - Locate the key's line range in the existing file text (line and column).
   - Rewrite only the value portion, leaving the `# trailing` tail intact unless the user explicitly clears it.
   - For lists, rewrite item-by-item with a stable diff, preserving comment-only lines between items.
   - For section replacement (factory values), surgical-delete the old block and emit the new block plus the section's preceding block-comment header (if any).
   This is essentially v1's idea, generalized to nested keys via a real YAML AST. The right tool is a comment-aware YAML AST library (e.g. a custom layer on top of snakeyaml-engine's Parser+Emitter exposing the `CommentEvent` stream) — **not** simpleyaml's serialized data-model.
3. **Specific things v1 and v2 each got right and we should keep:**
   - From v1: pass-through of standalone comment lines that aren't part of any key's rewrite range; backup-rotation `.old1..N` chain (independent of atomic-rename, useful for forensics).
   - From v2: a single library-managed comment side-table is fine as a *read* model (for the `view` hover-text); just don't trust it as the *write* model.
4. **Specific things to **stop** doing:**
   - `setSection(...)` wholesale subtree replacement for factory values. Either set leaves individually, or accept that the section's interior comments are gone (and document the choice in `messages.yml`'s explanatory text for that section).
   - Re-extracting the jar default as the new user file on version bump. Replace with a 3-way merge (jar default ⊕ user changes ⊕ previous user file).
   - In-place `YamlFile.save()`. Always temp+fsync+atomic-rename, owned by `AtomicConfigWriter`.

---

## Open questions to ask before opening the A12 ADR

- Are we willing to take a hard dependency on snakeyaml-engine (or a similar low-level parser) and write the comment-aware surgical-edit layer in-house, **or** do we accept "inline-comment loss on rewritten scalars" as a permanent limitation and document it in `messages.yml`?
- Do we want comment preservation to be a **hard requirement** for `view` hover-text (i.e. `view` falls back to declared-type+bounds whenever the comment is missing), or do we want to ship a **shipped-default comment dictionary** keyed by config key, baked into the jar, so `view` always has *something* to show even when the user's edits have wiped the on-disk comment?
- (Probably yes to the dictionary regardless of the parser decision — it's the right primary source for `view`, and the on-disk comment is a fallback override.)

---

## File references

- v1: `origin/V1:src/main/java/leafcraft/rtp/tools/configuration/Config.java` (lines ~160–230, `update()` method).
- v2: `origin/V2:src/main/java/io/github/dailystruggle/rtp/common/configuration/ConfigParser.java` (lines ~120–340), `…/database/options/YamlFileDatabase.java` (lines ~76–125).
- v3 (current): `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/ConfigParser.java`, `…/MultiConfigParser.java`, `…/database/options/YamlFileDatabase.java`, `…/commands/config/SubConfigCmd.java`, `…/commands/config/list/ListCmd.java`.
- Spec gap this addresses: `docs/dev/CONFIG_COMMAND_SPEC.md` Appendix A12.

**Delete this file** once the A12 ADR has been opened and references its content where needed.
