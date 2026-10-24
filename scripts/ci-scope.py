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

`--working-tree` (head must be HEAD) also counts staged, unstaged and
untracked (non-ignored) files, so uncommitted local or agent edits scope the
same way they will once pushed. scripts/agent-gate.py builds on it.

Writes `scope=`, `tasks=`, one `<set>=true|false` per set, `full=true|false`
(every set affected) and one `<set>_tasks=` per set (`:<p>:build` for each
affected project of the set, `:<p>:test` for test-only consumers; every
project when unscoped) to $GITHUB_OUTPUT when set; always prints them.

    python scripts/ci-scope.py --check-workflow .github/workflows/gradle.yml

fails when a set job does not (transitively) `needs:` the job of a set its
projects reference, so the job chain cannot drift from the Gradle graph.
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

# Mirrors the push paths-ignore list of .github/workflows/gradle.yml. GitHub's `*`
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
    "core": ("rtp-api", "rtp-core", "server-api", "commands-api", "yaml-api", "units-api", "effects-api",
             "maps-api", "metrics-api", "bstats-api", "anvil-api", "bit-api", "tags-api",
             "predict-api", "config-bit-api", "menu-api"),
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


def affected_projects(
    changed: list[str],
    tree: dict[str, str],
    refs: dict[str, set[str]],
    consumers: dict[str, set[str]],
) -> tuple[set[str], set[str]] | None:
    """(projects to build, projects whose tests alone must run), or None when
    the change cannot be scoped (path outside every project, or a project in
    no set) and everything must build."""
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
            return None
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

    if any(set_of(p) is None for p in affected | test_only):
        return None
    return affected, test_only - affected


def affected_sets(
    changed: list[str],
    tree: dict[str, str],
    refs: dict[str, set[str]],
    consumers: dict[str, set[str]],
) -> dict[str, bool]:
    """Set name -> whether any of its projects is affected by `changed`."""
    result = affected_projects(changed, tree, refs, consumers)
    if result is None:
        return {name: True for name in SETS}
    affected, test_only = result
    flags = {name: False for name in SETS}
    for project in affected | test_only:
        flags[set_of(project)] = True
    return flags


def set_tasks(
    tree: dict[str, str],
    result: tuple[set[str], set[str]] | None,
) -> dict[str, list[str]]:
    """Set name -> Gradle tasks its CI job runs: `:<p>:build` per affected
    project, `:<p>:test` per test-only project. None (unscoped) builds every
    project in the tree."""
    affected, test_only = result if result is not None else (set(tree), set())
    tasks: dict[str, list[str]] = {name: [] for name in SETS}
    for project in sorted(affected):
        name = set_of(project)
        if name is not None:
            tasks[name].append(f":{project}:build")
    for project in sorted(test_only):
        tasks[set_of(project)].append(f":{project}:test")
    return tasks


def set_graph(tree: dict[str, str], refs: dict[str, set[str]]) -> dict[str, set[str]]:
    """Set name -> other sets its projects reference (build-order edges)."""
    graph: dict[str, set[str]] = {name: set() for name in SETS}
    for project, deps in refs.items():
        source = set_of(project)
        if source is None:
            continue
        for dep in deps:
            target = set_of(dep) if dep in tree else None
            if target is not None and target != source:
                graph[source].add(target)
    return graph


_JOB_RE = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")
_NEEDS_INLINE_RE = re.compile(r"^    needs:\s*(\S.*)$")
_NEEDS_BLOCK_RE = re.compile(r"^    needs:\s*$")
_LIST_ITEM_RE = re.compile(r"^      -\s*([A-Za-z0-9_-]+)\s*$")


def workflow_needs(text: str) -> dict[str, set[str]]:
    """Job id -> direct `needs:` of a GitHub workflow (inline, list or block form)."""
    needs: dict[str, set[str]] = {}
    in_jobs = False
    job: str | None = None
    block = False
    for line in text.splitlines():
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if not in_jobs:
            continue
        if line and not line.startswith(" ") and not line.startswith("#"):
            break
        match = _JOB_RE.match(line)
        if match:
            job = match.group(1)
            needs[job] = set()
            block = False
            continue
        if job is None:
            continue
        inline = _NEEDS_INLINE_RE.match(line)
        if inline:
            value = inline.group(1).split("#", 1)[0].strip().strip("[]")
            needs[job] |= {v.strip() for v in value.split(",") if v.strip()}
            block = False
            continue
        if _NEEDS_BLOCK_RE.match(line):
            block = True
            continue
        item = _LIST_ITEM_RE.match(line) if block else None
        if item:
            needs[job].add(item.group(1))
        elif block and line.strip():
            block = False
    return needs


def check_workflow(graph: dict[str, set[str]], needs: dict[str, set[str]]) -> list[str]:
    """Violations of: every set has a job of the same id, and for each set edge
    A -> B one job transitively needs the other (B before A, or A before B
    when the edge closes a cycle such as rtp-core <-> rtp-proxy-common)."""
    def closure(job: str) -> set[str]:
        seen: set[str] = set()
        stack = list(needs.get(job, set()))
        while stack:
            current = stack.pop()
            if current not in seen:
                seen.add(current)
                stack.extend(needs.get(current, set()))
        return seen

    errors = [f"no job '{name}' for project set '{name}'" for name in SETS if name not in needs]
    for source, targets in graph.items():
        for target in sorted(targets):
            if source not in needs or target not in needs or target in closure(source):
                continue
            if source in graph.get(target, set()) and source in closure(target):
                continue
            errors.append(f"job '{source}' builds projects referencing '{target}' "
                          f"but does not need job '{target}'")
    return errors


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


def _git_lines(args: list[str]) -> list[str] | None:
    out = subprocess.run(
        ["git", *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=False,
    )
    if out.returncode != 0:
        print(f"warning: git {' '.join(args)} failed:\n{out.stderr}", file=sys.stderr)
        return None
    return [line.strip() for line in out.stdout.splitlines() if line.strip()]


def changed_files(base: str, head: str, working_tree: bool = False) -> list[str] | None:
    """Paths changed in base...head; with working_tree, plus uncommitted and untracked.

    None when any git call fails, so the caller falls back to a full build.
    """
    commands = [["diff", "--name-only", "--no-renames", f"{base}...{head}"]]
    if working_tree:
        # Staged + unstaged vs HEAD, then untracked files .gitignore does not hide.
        commands.append(["diff", "--name-only", "--no-renames", "HEAD"])
        commands.append(["ls-files", "--others", "--exclude-standard"])
    paths: set[str] = set()
    for command in commands:
        lines = _git_lines(command)
        if lines is None:
            return None
        paths.update(lines)
    return sorted(paths)


def emit(
    scope: str,
    tasks: list[str],
    flags: dict[str, bool],
    per_set: dict[str, list[str]],
) -> None:
    lines = [f"scope={scope}", f"tasks={' '.join(tasks)}"]
    lines += [f"{name}={'true' if flags.get(name) else 'false'}" for name in SETS]
    lines.append(f"full={'true' if all(flags.get(name) for name in SETS) else 'false'}")
    lines += [f"{name}_tasks={' '.join(per_set.get(name, []))}" for name in SETS]
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
    parser.add_argument("--working-tree", action="store_true",
                        help="also count staged, unstaged and untracked files (requires --head HEAD)")
    parser.add_argument("--check-workflow", metavar="PATH",
                        help="only verify that the workflow's set jobs `needs:` follow the "
                             "project dependency graph; exit 1 on a missing edge")
    args = parser.parse_args(argv[1:])
    if args.working_tree and args.head != "HEAD":
        parser.error("--working-tree compares against the checkout; --head must be HEAD")

    root = Path.cwd()
    settings = (root / "settings.gradle").read_text(encoding="utf-8")
    dirs = project_dirs(settings)
    tree = project_dirs(settings, parents=True)
    refs = all_refs(root, tree)

    if args.check_workflow:
        needs = workflow_needs(Path(args.check_workflow).read_text(encoding="utf-8"))
        errors = check_workflow(set_graph(tree, refs), needs)
        for error in errors:
            print(f"error: {args.check_workflow}: {error}", file=sys.stderr)
        if not errors:
            print(f"{args.check_workflow}: set job order matches the project graph.")
        return 1 if errors else 0

    every = {name: True for name in SETS}
    if args.force_full or not args.base or args.base == ZERO_SHA:
        print("Full build: forced, or no usable baseline.")
        emit("full", [], every, set_tasks(tree, None))
        return 0

    changed = changed_files(args.base, args.head, args.working_tree)
    if changed is None:
        print("Full build: change set could not be determined.")
        emit("full", [], every, set_tasks(tree, None))
        return 0

    consumers = test_source_consumers(root, dirs)
    scope, tasks = classify(changed, dirs, refs, consumers)
    result = affected_projects(changed, tree, refs, consumers)
    flags = affected_sets(changed, tree, refs, consumers)
    print(f"{len(changed)} changed file(s) vs {args.base}")
    emit(scope, tasks, flags, set_tasks(tree, result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
