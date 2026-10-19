#!/usr/bin/env python3
"""Classify a change set so CI builds only what it touches.

Scopes:
- none: every changed path is ignorable (docs, root markdown, licences).
- leaf: every non-ignorable path lives inside an addon or helper project
  (addons/<Name>/**, helpers/<Name>/**). No core module has a Gradle edge to a
  leaf (rtp-plugin only bundles their jars), so the leaf scope builds:
    1. every Gradle project of each touched addon/helper,
    2. every leaf that references one of those via project(':...'),
    3. `:<module>:test` for any non-leaf module whose test sources read a
       touched leaf's files by path (e.g. rtp-core tests loading the shipped
       LeafRTPActionAddon action definitions).
- full: anything else (core, APIs, platforms, rtp-plugin, Gradle/CI config),
  or the change set could not be determined. Unknown always means full.

Independently of the scope, one true/false flag per project set (SETS below)
reports whether that set must rebuild: a set is affected when a changed file
lies in one of its projects, or one of its projects references an affected
project through project(':...') / findProject(':...'), transitively. A path
outside every project (root Gradle files, gradle/, .github/, scripts/, ...),
a project outside every set, or full mode sets every flag. A non-leaf module
whose tests read a changed leaf by path also flags its own set.

Usage (CI passes the event's base and head):

    python scripts/ci-scope.py --base <sha> [--head HEAD]
    python scripts/ci-scope.py --force-full

Writes `scope=`, `tasks=`, one `<set>=true|false` per set and `full=true|false`
(every set affected) to $GITHUB_OUTPUT when set; always prints them.
Stdlib only (Python 3.12+).
"""

from __future__ import annotations

import argparse
import fnmatch
import os
import re
import subprocess
import sys
from pathlib import Path

LEAF_ROOTS: tuple[str, ...] = ("addons", "helpers")

# Mirrors the paths-ignore list of .github/workflows/gradle.yml. GitHub's `*`
# does not cross `/`, so `*.md` and `LICENSE*` match repo-root files only.
IGNORED_PATTERNS: tuple[str, ...] = (
    "docs/**",
    "site/**",
    "*.md",
    "mkdocs.yml",
    ".github/ISSUE_TEMPLATE/**",
    "LICENSE*",
)

ZERO_SHA = "0" * 40

# Project set -> top-level Gradle path segments it owns. Order is output order.
SETS: dict[str, tuple[str, ...]] = {
    "core": ("rtp-api", "rtp-core", "commands-api", "yaml-api", "effects-api",
             "maps-api", "metrics-api", "bstats-api", "anvil-api", "tags-api"),
    "bukkit": ("rtp-bukkit", "rtp-paper", "rtp-folia"),
    "fabric": ("rtp-fabric",),
    "neoforge": ("rtp-neoforge",),
    "proxy": ("rtp-proxy",),
    "plugin": ("rtp-plugin",),
    "addons": ("addons",),
    "helpers": ("helpers",),
}

_INCLUDE_RE = re.compile(r"""^\s*include\s+['"]([^'"]+)['"]""", re.MULTILINE)
_PROJECT_DIR_RE = re.compile(
    r"""project\(\s*['"](:[^'"]+)['"]\s*\)\.projectDir\s*=\s*file\(\s*['"]([^'"]+)['"]\s*\)""")
# findProject covers rtp-plugin bundling addon jars without a dependency edge.
_PROJECT_REF_RE = re.compile(r"""(?:\bproject|findProject)\(\s*['"]:([^'"]+)['"]""")
# A string literal that starts with a leaf path, optionally behind `../`
# segments: "addons/LeafRTPActionAddon/src/...". Prose mentions inside a
# sentence ("belongs to helpers/StressTestRTP.") do not start the literal.
_LEAF_LITERAL_RE = re.compile(r"""["'](?:\.\./)*(addons|helpers)/([A-Za-z0-9_.-]+)/""")


def is_ignored(path: str) -> bool:
    for pat in IGNORED_PATTERNS:
        if pat.endswith("/**"):
            if path.startswith(pat[:-2]):
                return True
        elif "/" not in path and fnmatch.fnmatchcase(path, pat):
            return True
    return False


def is_leaf(project: str) -> bool:
    return project.split(":", 1)[0] in LEAF_ROOTS


def project_dirs(settings_text: str, parents: bool = False) -> dict[str, str]:
    """Gradle path (no leading colon) -> repo-relative POSIX dir, for every include.

    Includes inside `if (...)` gates are listed too: CI provisions JDK 21 + 25
    and NeoForge, so every gated carrier is part of the graph there.
    `parents` adds the implicit parent projects Gradle creates for nested
    includes (e.g. `rtp-neoforge`), except the bare `addons` / `helpers`
    containers, whose loose files are classified separately.
    """
    dirs: dict[str, str] = {}
    for path in _INCLUDE_RE.findall(settings_text):
        path = path.lstrip(":")
        if parents:
            segments = path.split(":")
            for depth in range(1, len(segments)):
                parent = ":".join(segments[:depth])
                if parent not in LEAF_ROOTS:
                    dirs.setdefault(parent, parent.replace(":", "/"))
        dirs.setdefault(path, path.replace(":", "/"))
    for path, directory in _PROJECT_DIR_RE.findall(settings_text):
        path = path.lstrip(":")
        if path in dirs:
            dirs[path] = directory.replace("\\", "/").rstrip("/")
    return dirs


def owning_group(path: str) -> str | None:
    """`addons:<Name>` or `helpers:<Name>` for a path inside a leaf project dir."""
    parts = path.split("/")
    if len(parts) >= 3 and parts[0] in LEAF_ROOTS:
        return f"{parts[0]}:{parts[1]}"
    return None


def project_refs(root: Path, directory: str) -> set[str]:
    try:
        text = (root / directory / "build.gradle").read_text(encoding="utf-8")
    except OSError:
        return set()
    return {ref.lstrip(":") for ref in _PROJECT_REF_RE.findall(text)}


def all_refs(root: Path, tree: dict[str, str]) -> dict[str, set[str]]:
    """Project -> referenced projects, including refs declared by its ancestors
    (a parent build.gradle such as platforms/rtp-neoforge configures children)."""
    own = {p: project_refs(root, d) for p, d in tree.items()}
    refs: dict[str, set[str]] = {}
    for project in tree:
        segments = project.split(":")
        merged: set[str] = set()
        for depth in range(1, len(segments) + 1):
            merged |= own.get(":".join(segments[:depth]), set())
        merged.discard(project)
        refs[project] = merged
    return refs


def set_of(project: str) -> str | None:
    top = project.split(":", 1)[0]
    for name, tops in SETS.items():
        if top in tops:
            return name
    return None


def owner(path: str, tree: dict[str, str]) -> str | None:
    """Deepest project whose directory contains `path`."""
    best: str | None = None
    best_len = -1
    for project, directory in tree.items():
        if path.startswith(directory + "/") and len(directory) > best_len:
            best, best_len = project, len(directory)
    return best


def affected_sets(
    changed: list[str],
    tree: dict[str, str],
    refs: dict[str, set[str]],
    consumers: dict[str, set[str]],
) -> dict[str, bool]:
    """Set name -> whether any of its projects is affected by `changed`."""
    every = {name: True for name in SETS}
    affected: set[str] = set()
    test_only: set[str] = set()
    for path in changed:
        path = path.replace("\\", "/")
        if is_ignored(path):
            continue
        project = owner(path, tree)
        if project is None:
            if path.split("/", 1)[0] in LEAF_ROOTS and path.endswith(".md"):
                continue
            return every
        affected.add(project)
        group = owning_group(path)
        if group is not None:
            test_only |= consumers.get(group, set())

    # A parent build.gradle configures its children (subprojects {}, shared tasks).
    affected |= {p for p in tree if any(p.startswith(a + ":") for a in affected)}

    grew = True
    while grew:
        grew = False
        for project, deps in refs.items():
            if project not in affected and deps & affected:
                affected.add(project)
                grew = True

    flags = {name: False for name in SETS}
    for project in affected | test_only:
        name = set_of(project)
        if name is None:
            return every
        flags[name] = True
    return flags


def test_source_consumers(root: Path, dirs: dict[str, str]) -> dict[str, set[str]]:
    """Leaf group -> non-leaf projects whose test sources read that leaf by path."""
    consumers: dict[str, set[str]] = {}
    for project, directory in dirs.items():
        if is_leaf(project):
            continue
        test_root = root / directory / "src" / "test"
        if not test_root.is_dir():
            continue
        for source in test_root.rglob("*"):
            if source.suffix not in (".java", ".kt") or not source.is_file():
                continue
            text = source.read_text(encoding="utf-8", errors="replace")
            for top, name in _LEAF_LITERAL_RE.findall(text):
                consumers.setdefault(f"{top}:{name}", set()).add(project)
    return consumers


def classify(
    changed: list[str],
    dirs: dict[str, str],
    refs: dict[str, set[str]],
    consumers: dict[str, set[str]],
) -> tuple[str, list[str]]:
    """Return (scope, Gradle tasks to run for the leaf scope)."""
    leaves = [p for p in dirs if is_leaf(p)]
    groups: set[str] = set()
    selected: set[str] = set()
    for path in changed:
        path = path.replace("\\", "/")
        if is_ignored(path):
            continue
        group = owning_group(path)
        if group is None:
            # Loose markdown directly under addons/ or helpers/ carries no code.
            if path.split("/", 1)[0] in LEAF_ROOTS and path.endswith(".md"):
                continue
            return "full", []
        members = [p for p in leaves if p == group or p.startswith(group + ":")]
        if not members:
            # A directory Gradle does not know about: cannot scope it safely.
            return "full", []
        groups.add(group)
        selected.update(members)

    if not selected:
        return "none", []

    grew = True
    while grew:
        grew = False
        for project in leaves:
            if project not in selected and refs.get(project, set()) & selected:
                selected.add(project)
                grew = True

    tasks = [f":{p}:build" for p in sorted(selected)]
    core_tests = sorted({c for g in groups for c in consumers.get(g, set())})
    tasks += [f":{p}:test" for p in core_tests]
    return "leaf", tasks


def changed_files(base: str, head: str) -> list[str] | None:
    out = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", f"{base}...{head}"],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    )
    if out.returncode != 0:
        print(f"warning: git diff {base}...{head} failed:\n{out.stderr}", file=sys.stderr)
        return None
    return [line.strip() for line in out.stdout.splitlines() if line.strip()]


def emit(scope: str, tasks: list[str], flags: dict[str, bool]) -> None:
    lines = [f"scope={scope}", f"tasks={' '.join(tasks)}"]
    lines += [f"{name}={'true' if flags.get(name) else 'false'}" for name in SETS]
    lines.append(f"full={'true' if all(flags.get(name) for name in SETS) else 'false'}")
    for line in lines:
        print(line)
    target = os.environ.get("GITHUB_OUTPUT")
    if target:
        with open(target, "a", encoding="utf-8") as fh:
            for line in lines:
                fh.write(line + "\n")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Classify a change set into a CI build scope.")
    parser.add_argument("--base", default="", help="git ref/sha the change set starts from")
    parser.add_argument("--head", default="HEAD", help="git ref/sha the change set ends at")
    parser.add_argument("--force-full", action="store_true", help="skip detection and build everything")
    args = parser.parse_args(argv[1:])

    every = {name: True for name in SETS}
    if args.force_full or not args.base or args.base == ZERO_SHA:
        print("Full build: forced, or no usable baseline.")
        emit("full", [], every)
        return 0

    changed = changed_files(args.base, args.head)
    if changed is None:
        print("Full build: change set could not be determined.")
        emit("full", [], every)
        return 0

    root = Path.cwd()
    settings = (root / "settings.gradle").read_text(encoding="utf-8")
    dirs = project_dirs(settings)
    tree = project_dirs(settings, parents=True)
    refs = all_refs(root, tree)
    consumers = test_source_consumers(root, dirs)
    scope, tasks = classify(changed, dirs, refs, consumers)
    flags = affected_sets(changed, tree, refs, consumers)
    print(f"{len(changed)} changed file(s) vs {args.base}")
    emit(scope, tasks, flags)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
