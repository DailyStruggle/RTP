# YAML Feature Audit — Shipped Configs (2026-05-15)

**Purpose:** scope the subset of YAML that an in-house parser/serializer must
support, in preparation for reversing ADR-025 toward Option D
(hand-rolled comment-preserving YAML library) per the A12 design discussion.

**Method:** ran `docs/dev/scratch/yaml_feature_audit.ps1` over all shipped
`.yml` resources in `rtp-plugin/src/main/resources/`,
`rtp-plugin/src/lite/resources/`, `addons/RTP_ExampleAddon/src/main/resources/`,
and `rtp-core/src/test/resources/`. Total files scanned: **187**.

## Results — features the parser **must** handle

| Feature | Required? |
|---------|-----------|
| Block-style nested mappings (arbitrary depth) | Yes |
| Block-style sequences of scalars (`- item`) | Yes |
| Scalar types: integer, double, boolean, `null`/empty | Yes |
| Bare (unquoted) string scalars | Yes |
| Double-quoted string scalars (822 occurrences) | Yes |
| Single-quoted string scalars (7 occurrences) | Yes — small surface, low cost |
| Standard escape sequences inside double-quoted scalars (`\"`, `\\`, `\n`, `\t`, `\uXXXX`) | Yes |
| Above-line (block) comments at arbitrary indentation | **Yes — load-bearing for A12** |

## Results — features the parser is **free to reject**

| Feature | Hits | Decision |
|---------|------|----------|
| Anchors `&name` / aliases `*name` | 0 real (64 false positives from Bukkit color codes `&a`/`&c` inside quoted strings) | **Reject.** Parser fails fast with `UNSUPPORTED_YAML_FEATURE` on first occurrence of an anchor/alias *outside* a quoted scalar. |
| Merge keys `<<:` | 0 | **Reject.** |
| Flow-style mappings `{…}` or sequences `[…]` at value position | 0 real (234 false positives — every match is a `[placeholder]` substring inside a comment or quoted string) | **Reject.** Parser fails fast on `{` or `[` at value start *outside* a quoted scalar. |
| Explicit tags `!Tag` / `!!type` | 0 | **Reject.** |
| Multi-document separators `---` / `...` | 0 | **Reject** (single-doc files only). |
| Block-scalar indicators `|` / `>` | 4 — all in `plugin.yml` only (Bukkit plugin descriptor's `usage: |` for two commands) | **Out of scope.** `plugin.yml` is loaded by the Bukkit platform, never by RTP's config layer, and never mutated by `/rtp config`. The in-house parser is **never asked to read `plugin.yml`**, so block scalars are not a feature it has to implement. |

## Inline (same-line) trailing comments — separate decision

The audit found **228 inline trailing comments** in shipped configs (`config.yml`,
`economy.yml`, `logging.yml`, `performance.yml`, `safety.yml`, etc.). Examples:

```yaml
teleportDelay: 2 # Wait time (seconds) before teleport
type: "sqlite" # yaml, sqlite, mysql, or postgresql
```

Per the 2026-05-14 A12 scope decision (block comments in, inline comments out),
these are out of scope for the comment-preservation contract — but the *user has
explicitly stated* (2026-05-15 issue update) that the original YAML idiom is
*comments above entries, not on the same line*, and that this is more efficient
to read. The combined implication:

1. The parser must **tolerate** inline trailing comments on input (a user who
   hand-edits their config that way must not get a parse error).
2. The parser is **not** required to preserve them across a `/rtp config set`
   write-back. A12 makes no promise about inline-comment fidelity.
3. Shipped default `.yml` files must be **rewritten** to move every inline
   comment onto the line above its key, before the in-house parser ships. This
   aligns the shipped defaults with the A12 scope and with the user's stated
   preference, and gives `view` hover-text full coverage of every default
   parameter without relying on inline-comment recovery.

## Block-comment density (top-10 files by `^#` line count)

| File | Block-comment lines |
|------|---------------------|
| `rtp-plugin/src/main/resources/effects/default.yml` | 138 |
| `rtp-plugin/src/main/resources/lang/es/safety.yml` | 102 |
| `rtp-plugin/src/main/resources/lang/cat/messages.yml` | 100 |
| `rtp-plugin/src/main/resources/messages.yml` | 99 |
| `rtp-plugin/src/main/resources/lang/ko/safety.yml` | 89 |
| `rtp-plugin/src/main/resources/lang/ja/safety.yml` | 87 |
| `rtp-plugin/src/main/resources/lang/zh/safety.yml` | 82 |
| `rtp-plugin/src/main/resources/lang/es/performance.yml` | 52 |
| `rtp-plugin/src/main/resources/lang/ja/performance.yml` | 51 |
| `rtp-plugin/src/main/resources/lang/ko/performance.yml` | 51 |

Block comments are the dominant comment form already; the proposed rewrite of
the 228 inline comments to above-line form increases the count further but
introduces no new comment-attachment patterns.

## Audit conclusion

The subset RTP actually uses is small enough that a hand-rolled parser/serializer
is a clearly bounded effort: **block mappings + block sequences of scalars +
quoted/bare strings + above-line comments**. Every feature this audit examined
that is genuinely complex to implement (anchors, merge keys, flow style, tags,
multi-document, multi-line block scalars) is **zero occurrences in shipped
configs**. The only edge case is single-quoted scalars (7 occurrences) — a low-
cost addition.

Recommendation: **Option D (reverse ADR-025 toward an in-house comment-preserving
YAML library)** is viable on the evidence. The parser is roughly 300–500 LoC for
the reader plus the same again for the surgical text-edit save layer that A12
needs regardless of substrate choice.

## Followup work this audit unblocks

1. **Reverse ADR-025** — propose an in-house parser as the supersede decision.
2. **Shipped-defaults rewrite** — convert all 228 inline trailing comments to
   above-line block comments. One-time mechanical edit, no behavior change.
3. **Patch `CONFIG_COMMAND_SPEC.md`** §2.4 and Appendix A12 to reflect block-only
   comment scope and the in-house substrate.
4. **`plugin.yml` carve-out** — document that `plugin.yml` is parsed by Bukkit,
   not RTP, so its `usage: |` block scalars are not in scope.

## Delete-after

This file is a one-shot research note for the ADR-025 reversal. Delete once the
supersede ADR has been opened and references the audit results inline.
