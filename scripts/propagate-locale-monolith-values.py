#!/usr/bin/env python3
"""Propagate translated values from the pre-split locale monolith into the split tree.

Background: ADR-076 split the shipped English messages into
``advanced/messages/{placeholders,player,network,commands,system}.yml`` and gave
every locale a mirror of that layout. The per-locale ``messages.yml`` monolith
was left behind, and the runtime (``Configs.reloadConfigs``) only ever loads the
split mirror. The split mirrors were generated from the *English* baseline, so
keys added after the split ship English text on non-English servers even though
the monolith holds a real translation.

This script is the one-way migration: for every locale, each single-line scalar
value in ``lang/<locale>/messages.yml`` is copied over the corresponding key in
whichever split member file already declares that key. Keys are matched by name
(the monolith and the split mirror use the same translated key names), so no
``.lang.yml`` indirection is needed.

Deliberately conservative - the following are reported and skipped rather than
guessed at:
  * keys the split mirror does not declare (nothing owns them);
  * block sequences / block scalars, i.e. anything whose value is not a
    single-line scalar (``regionInfo``, ``worldInfo``, ``placeholders``).

Line-based on purpose: a YAML round-trip would discard the operator-facing
comments and key order that these files ship.

Usage (from the repository root):
    python scripts/propagate-locale-monolith-values.py [--apply]

Without ``--apply`` it is a dry run and writes nothing.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

RESOURCES = pathlib.Path("rtp-plugin/src/main/resources")
LANG_ROOT = RESOURCES / "lang"

# `key: value` at column 0. Trailing content is captured verbatim so quoting,
# inline comments and colour codes survive untouched. `\w` is Unicode-aware:
# ja / ko / ru / zh locales translate key *names* as well as values.
ENTRY = re.compile(r"^(\w[\w.-]*):[ \t]+(\S.*)$")
# `key:` with nothing after it opens a block sequence or block scalar.
BLOCK_OPENER = re.compile(r"^(\w[\w.-]*):[ \t]*$")


def read_lines(path: pathlib.Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines(keepends=True)


def scalar_entries(path: pathlib.Path) -> tuple[dict[str, str], set[str]]:
    """Return ({key: raw single-line value}, {keys opening a block})."""
    scalars: dict[str, str] = {}
    blocks: set[str] = set()
    for line in read_lines(path):
        stripped = line.rstrip("\r\n")
        match = ENTRY.match(stripped)
        if match:
            scalars[match.group(1)] = match.group(2)
            continue
        opener = BLOCK_OPENER.match(stripped)
        if opener:
            blocks.add(opener.group(1))
    return scalars, blocks


def member_files(messages_dir: pathlib.Path) -> list[pathlib.Path]:
    """Split member value files, excluding the co-located `.<file>.lang.yml` maps."""
    return sorted(
        p for p in messages_dir.glob("*.yml") if not p.name.startswith(".")
    )


def propagate_locale(locale_dir: pathlib.Path, apply: bool) -> tuple[int, list[str]]:
    monolith = locale_dir / "messages.yml"
    messages_dir = locale_dir / "advanced" / "messages"
    if not monolith.is_file() or not messages_dir.is_dir():
        return 0, []

    source, source_blocks = scalar_entries(monolith)
    updated = 0
    notes: list[str] = []
    claimed: set[str] = set()

    for member in member_files(messages_dir):
        lines = read_lines(member)
        changed = False
        for index, line in enumerate(lines):
            newline = line[len(line.rstrip("\r\n")):]
            match = ENTRY.match(line.rstrip("\r\n"))
            if not match:
                opener = BLOCK_OPENER.match(line.rstrip("\r\n"))
                if opener:
                    claimed.add(opener.group(1))
                continue
            key, current = match.group(1), match.group(2)
            claimed.add(key)
            replacement = source.get(key)
            if replacement is None or replacement == current:
                continue
            lines[index] = f"{key}: {replacement}{newline}"
            changed = True
            updated += 1
        if changed and apply:
            member.write_text("".join(lines), encoding="utf-8")

    orphans = sorted((set(source) | source_blocks) - claimed)
    if orphans:
        notes.append(f"no split member declares {len(orphans)} key(s): {orphans}")
    if source_blocks:
        notes.append(
            f"skipped {len(source_blocks)} block value(s) (not single-line scalars): "
            f"{sorted(source_blocks)}"
        )
    return updated, notes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="write the changes; omit for a dry run",
    )
    args = parser.parse_args()

    # Locale key names are reported verbatim; the Windows console defaults to
    # cp1252 and would raise UnicodeEncodeError on ja / ko / ru / zh keys.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError):
        pass

    if not LANG_ROOT.is_dir():
        print(f"error: {LANG_ROOT} not found - run from the repository root", file=sys.stderr)
        return 2

    total = 0
    for locale_dir in sorted(p for p in LANG_ROOT.iterdir() if p.is_dir()):
        updated, notes = propagate_locale(locale_dir, args.apply)
        if updated or notes:
            print(f"{locale_dir.name}: {updated} value(s) propagated")
            for note in notes:
                print(f"  - {note}")
        total += updated

    verb = "propagated" if args.apply else "would propagate"
    print(f"\n{verb} {total} value(s) across all locales")
    if not args.apply:
        print("dry run - re-run with --apply to write")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
