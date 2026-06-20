#!/usr/bin/env python3
"""scan-locale-untranslated.py

Cross-platform (Python 3.12) replacement for scan-locale-untranslated.ps1.

First-pass triage scanner over the TSV pipeline produced by
locale-files-to-csv.py. Identifies rows in each scripts/out/locale-<lang>.tsv
that likely need translation attention, in four categories:

  1. DEPRECATED   - locale row whose (relpath, base_key) is absent from
                    baseline.tsv (stale; next reconcile pass will drop it).
  2. SAME_KEY     - translated `key` column identical to baseline `base_key`.
  3. SAME_VALUE   - `value` identical to baseline English AND the value is
                    genuine translatable prose (seeded-but-untranslated). Values
                    that are language-agnostic by nature (numbers, booleans,
                    hosts/IPs, version strings, config enum/identifier tokens,
                    map/list parent sentinels, and color-code / placeholder /
                    symbol-only strings with no prose) are filtered out, so a
                    SAME_VALUE hit is something that *should* be translated.
  4. SAME_COMMENT - `preceding_comment` byte-identical to baseline. Doc-tag-only
                    blocks (@type/@range/...) are skipped.
  5. ENGLISH_LEFTOVER - `value` differs from baseline English (so it was edited)
                    but still carries >=2 distinct English marker words, i.e. an
                    English fragment left behind during a partial text update.
                    Only flagged for genuine prose values.

Read-only: modifies no TSV. Use --csv for machine-readable output.

  python scripts/scan-locale-untranslated.py
  python scripts/scan-locale-untranslated.py --locales de fr --sample-count 5
  python scripts/scan-locale-untranslated.py --csv > scripts/out/untranslated.tsv

See AGENTS.md "Locale Config TSV Pipeline" for the surrounding workflow.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import locale_common as lc  # noqa: E402

# Locale keys/values carry non-ASCII text (CJK, Cyrillic, accents); force UTF-8
# stdout so the scanner does not crash on a cp1252 console.
try:
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
except (AttributeError, ValueError):
    pass


def is_doc_tag_only_comment(comment: str) -> bool:
    if not comment or not comment.strip():
        return True
    for ln in comment.split("\n"):
        s = ln.lstrip()
        if not s.strip():
            continue
        if re.match(r"^#\s*@(type|range|unit|default|options|source):", s):
            continue
        if re.match(r"^#\s*#+\s*$", s):
            continue
        if re.match(r"^#\s*$", s):
            continue
        return False
    return True


# English connector / function words that are unlikely to survive verbatim in a
# properly translated non-English value. Used for ENGLISH_LEFTOVER detection.
# Deliberately excludes words that collide with target languages or are commonly
# kept as loanwords (e.g. "no" in Spanish/Italian, "server"/"chunks"/"world"
# borrowed verbatim, "player" kept untranslated) to keep false positives down.
EN_MARKERS = [
    " the ", " and ", " for ", " with ", " when ", " this ", " that ", " your ",
    " you ", " will ", " must ", " should ", " does ", " from ", " into ",
    " before ", " after ", " between ", " available ", " enabled ", " disabled ",
    " empty ", " list ", " command ", " commands ", " loaded ", " already ",
    " reject ", " cause ", " teleporting ", " success ",
]

# Locales whose register is an English-based dialect by design (the Internet Cat
# dialect, ISO-misnamed "cat"); English connector words there are intentional, so
# they are excluded from ENGLISH_LEFTOVER detection.
ENGLISH_DIALECT_LOCALES = {"cat"}


def mask_value(value: str) -> str:
    """Strip color codes, hex, section signs and [placeholder] tokens, leaving
    only the residual human-readable text."""
    s = re.sub(r"&[0-9A-Za-z]", " ", value)
    s = re.sub(r"#[0-9A-Fa-f]{6}\b", " ", s)
    s = re.sub(r"#[0-9A-Fa-f]{3}\b", " ", s)
    s = re.sub(r"\u00a7.", " ", s)  # legacy section sign color code
    s = re.sub(r"\[[^\]]*\]", " ", s)  # [placeholder] substitution tokens
    return s


def is_language_agnostic_value(value: str) -> bool:
    """True when the value is not genuine translatable prose: empty, numeric,
    boolean, an IP/host, a version string, a map/list parent sentinel, a single
    config enum/identifier token, or a string consisting only of color codes,
    placeholders and symbols with no residual prose word."""
    if not value or not value.strip():
        return True
    v = value.strip()
    if re.match(r"^-?\d+(\.\d+)?$", v):
        return True
    if re.match(r"^(true|false|null|yes|no)$", v, re.IGNORECASE):
        return True
    if re.match(r"^\d{1,3}(\.\d{1,3}){3}$", v):  # IPv4 host
        return True
    if re.match(r"^__.+__$", v):  # __MAP_OR_LIST_PARENT__ and similar sentinels
        return True
    if re.match(r"^&?#?[0-9A-Fa-f]{3,8}$", v):  # bare color code
        return True
    # Single config enum / identifier token (sqlite, INFO, root, rtp, book,
    # NORMAL, world-border, 3.0, ...). No internal whitespace -> not prose.
    if " " not in v and re.match(r"^[A-Za-z][A-Za-z0-9_.\-]*$", v):
        return True
    # Namespaced resource identifier (bungeecord:main, #minecraft:logs,
    # minecraft:overworld). No internal whitespace -> not prose.
    if " " not in v and re.match(r"^#?[A-Za-z][A-Za-z0-9_./\-]*:[A-Za-z0-9_./\-:]+$", v):
        return True
    masked = mask_value(v)
    # Kebab-case identifier labels only (survival-default, low-performance,
    # folia-tuned, multi-world): every letter-bearing token is kebab-case, so
    # the value is a config/prefab selector rendered verbatim, not prose.
    tokens = [t for t in re.split(r"\s+", masked) if re.search(r"[A-Za-z]", t)]
    if tokens and all(re.fullmatch(r"[^A-Za-z]*[a-z]+(?:-[a-z]+)+[^A-Za-z]*", t) for t in tokens):
        return True
    # Residual prose check: anything left after masking codes/placeholders.
    residual = re.sub(r"[^A-Za-z ]", " ", masked)
    words = [w for w in residual.split() if len(w) >= 3]
    return len(words) == 0


def english_leftover_markers(value: str) -> list[str]:
    """Return the distinct English marker words present in a value's residual
    prose (after masking), used to flag partial-translation leftovers."""
    if is_language_agnostic_value(value):
        return []
    hay = " " + mask_value(value).lower() + " "
    return sorted({m.strip() for m in EN_MARKERS if m in hay})


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--locales", nargs="*", default=None)
    ap.add_argument("--sample-count", type=int, default=3)
    ap.add_argument("--csv", action="store_true")
    ap.add_argument(
        "--changeset-keys",
        action="store_true",
        help=("emit only a deduped, sorted list of '<relpath>:<base_key>' selectors "
              "for the genuinely-translatable categories (SAME_VALUE, ENGLISH_LEFTOVER), "
              "suitable for: locale-changeset-to-csv.py --keys $(...)"),
    )
    ap.add_argument("--out-dir", dest="out_dir")
    args = ap.parse_args()

    out_dir = Path(args.out_dir) if args.out_dir else lc.out_dir()
    baseline_path = out_dir / "baseline.tsv"
    if not baseline_path.exists():
        raise SystemExit(f"baseline.tsv not found at {baseline_path} - run locale-files-to-csv.py first.")

    locales = args.locales if args.locales else lc.list_locales()

    # Key baseline by (normalized relpath, base_key).
    baseline_by_key: dict[str, dict] = {}
    for r in lc.read_tsv(baseline_path):
        norm = re.sub(r"^lang/", "", r["relpath"])
        baseline_by_key[f"{norm}\t{r['base_key']}"] = {
            "relpath": r["relpath"],
            "base_key": r["base_key"],
            "key": r["key"],
            "value": r["value"],
            "comment": r["preceding_comment"],
        }

    csv_rows: list[dict] = []
    # (relpath, base_key) selectors for keys that genuinely need translation,
    # deduped across locales. Drives the changeset workflow.
    changeset_keys: set[tuple[str, str]] = set()

    for loc in locales:
        localepath = out_dir / f"locale-{loc}.tsv"
        if not localepath.exists():
            if not args.csv:
                print(f"{loc:<4}  (no locale-{loc}.tsv)")
            continue

        rows = lc.read_tsv(localepath)
        total = 0
        deprecated: list[dict] = []
        same_key: list[dict] = []
        same_value: list[dict] = []
        same_comment: list[dict] = []
        english_leftover: list[dict] = []

        for r in rows:
            total += 1
            relpath = r["relpath"]
            key = r["key"]
            value = r["value"]
            comment = r["preceding_comment"]
            base_key = r["base_key"]

            base_relpath = re.sub(rf"^lang/{re.escape(loc)}/", "", relpath)
            rid = f"{base_relpath}\t{base_key}"

            def mkrow(category: str) -> dict:
                return {"locale": loc, "relpath": relpath, "base_key": base_key,
                        "key": key, "category": category}

            def add_changeset_key() -> None:
                changeset_keys.add((base_relpath, base_key))

            if rid not in baseline_by_key:
                row = mkrow("DEPRECATED")
                deprecated.append(row)
                csv_rows.append(row)
                continue

            b = baseline_by_key[rid]
            if key == b["base_key"]:
                row = mkrow("SAME_KEY")
                same_key.append(row)
                csv_rows.append(row)
            if value == b["value"] and not is_language_agnostic_value(value):
                row = mkrow("SAME_VALUE")
                same_value.append(row)
                csv_rows.append(row)
                add_changeset_key()
            elif value != b["value"] and loc not in ENGLISH_DIALECT_LOCALES:
                markers = english_leftover_markers(value)
                if len(markers) >= 2:
                    row = mkrow("ENGLISH_LEFTOVER")
                    row["markers"] = ",".join(markers)
                    english_leftover.append(row)
                    csv_rows.append(row)
                    add_changeset_key()
            if comment == b["comment"] and not is_doc_tag_only_comment(comment):
                row = mkrow("SAME_COMMENT")
                same_comment.append(row)
                csv_rows.append(row)

        if not args.csv and not args.changeset_keys:
            print(
                f"{loc:<4}  total={total:4}  deprecated={len(deprecated):3}  "
                f"same_key={len(same_key):4}  same_value={len(same_value):4}  "
                f"same_comment={len(same_comment):4}  english_leftover={len(english_leftover):3}"
            )
            if args.sample_count > 0:
                for label, lst in (("DEPRECATED", deprecated), ("SAME_KEY", same_key),
                                   ("SAME_VALUE", same_value), ("SAME_COMMENT", same_comment),
                                   ("ENGLISH_LEFTOVER", english_leftover)):
                    for e in lst[:args.sample_count]:
                        extra = f" <{e['markers']}>" if e.get("markers") else ""
                        print(f"       [{label}] {e['relpath']} :: {e['base_key']} (key={e['key']}){extra}")

    if args.changeset_keys:
        for relpath, base_key in sorted(changeset_keys):
            print(f"{relpath}:{base_key}")
    elif args.csv:
        print("locale\trelpath\tbase_key\tkey\tcategory\tmarkers")
        for r in csv_rows:
            print(f"{r['locale']}\t{r['relpath']}\t{r['base_key']}\t{r['key']}\t{r['category']}\t{r.get('markers', '')}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
