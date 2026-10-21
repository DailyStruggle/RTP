#!/usr/bin/env python3
"""Run the pull-request CI gates locally, scoped to the uncommitted change set.

Agents and contributors usually leave edits uncommitted, which a plain
`ci-scope.py --base ...` (commit diff only) cannot see. This wrapper scopes
with `ci-scope.py --working-tree` against the merge base with the target
branch and then runs what .github/workflows/gradle.yml would run for a pull
request with the same changes:

1. `checks` job: traceability, UTF-8 mojibake, front-page drift, Spotless.
2. Set jobs: every affected set's `<set>_tasks` with `-Pcoverage
   -PstaticAnalysis` (tests, PMD, JaCoCo floors, SpotBugs where configured),
   in one Gradle invocation. The plugin set and `checkBinaryCompatibility`
   run only in `full` scope, as in CI.
3. `full` scope only: the reduced JDK 21 graph (`assemble` and
   `verifyJava17Bytecode` with `-PexcludeJdk25 -PexcludeNeoforge`) and the 80%
   changed-line coverage gate (scripts/diff-coverage.py) against the merge base.

Not covered: SonarCloud, artifact packaging/upload, devstack acceptance.

Usage:

    python3 scripts/agent-gate.py                 # vs merge-base with origin/V3 (else V3)
    python3 scripts/agent-gate.py --base origin/V2
    python3 scripts/agent-gate.py --dry-run       # print the plan only
    python3 scripts/agent-gate.py -- --offline    # extra args for every Gradle call

Stops at the first failing step and exits with its code; 0 when every step
passed or nothing buildable changed. Stdlib only (Python 3.12+).
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

DEFAULT_BASES: tuple[str, ...] = ("origin/V3", "V3")
GATE_FLAGS: tuple[str, ...] = ("-Pcoverage", "-PstaticAnalysis")
REDUCED_FLAGS: tuple[str, ...] = ("-PexcludeJdk25", "-PexcludeNeoforge")


def git(root: Path, *args: str) -> str | None:
    out = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True,
                         encoding="utf-8", errors="replace", check=False)
    return out.stdout.strip() if out.returncode == 0 else None


def resolve_base(root: Path, requested: str | None) -> str | None:
    """Merge-base sha of HEAD and the requested (or first existing default) ref."""
    candidates = (requested,) if requested else DEFAULT_BASES
    for ref in candidates:
        if git(root, "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}") is None:
            continue
        sha = git(root, "merge-base", "HEAD", ref)
        if sha:
            print(f"Base: merge-base of HEAD and {ref} = {sha[:12]}")
            return sha
    print(f"warning: no usable base among {', '.join(candidates)}; scoping as full.", file=sys.stderr)
    return None


def parse_scope(stdout: str) -> dict[str, str]:
    """key=value lines of ci-scope.py output, in print order."""
    result: dict[str, str] = {}
    for line in stdout.splitlines():
        key, sep, value = line.partition("=")
        if sep and key and " " not in key:
            result[key] = value.strip()
    return result


def gradle_tasks(scope: dict[str, str]) -> list[str]:
    """Union of affected sets' tasks, mirroring the set-job conditions in gradle.yml."""
    full = scope.get("scope") == "full"
    tasks: list[str] = []
    for key, value in scope.items():
        if not key.endswith("_tasks"):
            continue
        name = key[: -len("_tasks")]
        if scope.get(name) != "true" or (name == "plugin" and not full):
            continue
        tasks += [t for t in value.split() if t not in tasks]
    if full and scope.get("core") == "true":
        tasks.append("checkBinaryCompatibility")
    return tasks


def has_jacoco_reports(root: Path) -> bool:
    return any(root.glob("**/build/reports/jacoco/test/jacocoTestReport.xml"))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Run the PR CI gates locally for the uncommitted change set.")
    parser.add_argument("--base", help=f"target branch/ref (default: first of {', '.join(DEFAULT_BASES)})")
    parser.add_argument("--dry-run", action="store_true", help="print the steps without running them")
    parser.add_argument("gradle_args", nargs="*", help="extra Gradle arguments (after `--`)")
    args = parser.parse_args(argv[1:])

    top = git(Path.cwd(), "rev-parse", "--show-toplevel")
    if top is None:
        print("error: not inside a git work tree", file=sys.stderr)
        return 2
    root = Path(top)
    gradlew = str(root / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    py = sys.executable or "python3"
    extra = list(args.gradle_args)

    base = resolve_base(root, args.base)
    scope_cmd = [py, str(root / "scripts" / "ci-scope.py")]
    scope_cmd += ["--base", base, "--working-tree"] if base else ["--force-full"]
    env = {k: v for k, v in os.environ.items() if k != "GITHUB_OUTPUT"}
    out = subprocess.run(scope_cmd, cwd=root, capture_output=True, text=True,
                         encoding="utf-8", errors="replace", check=False, env=env)
    sys.stderr.write(out.stderr)
    if out.returncode != 0:
        print(out.stdout)
        print(f"error: ci-scope.py exited {out.returncode}", file=sys.stderr)
        return out.returncode
    scope = parse_scope(out.stdout)
    kind = scope.get("scope", "full")
    affected = [k for k, v in scope.items() if v == "true" and f"{k}_tasks" in scope]
    print(f"Scope: {kind}; affected sets: {', '.join(affected) or 'none'}")
    if kind == "none":
        print("Nothing buildable changed; CI skips the build jobs too.")
        return 0

    steps: list[tuple[str, list[str]]] = []
    if shutil.which("bash"):
        steps.append(("traceability", ["bash", str(root / "scripts" / "check_traceability.sh")]))
    else:
        print("warning: bash not found; skipping scripts/check_traceability.sh", file=sys.stderr)
    steps.append(("mojibake", [py, str(root / "scripts" / "check-mojibake.py")]))
    steps.append(("front pages", [py, str(root / "scripts" / "release" / "build_front_pages.py"), "--check"]))
    steps.append(("spotless", [gradlew, "spotlessCheck", *extra]))
    tasks = gradle_tasks(scope)
    if tasks:
        steps.append(("set builds", [gradlew, *tasks, *GATE_FLAGS, *extra]))
    if kind == "full":
        steps.append(("reduced JDK 21 graph",
                      [gradlew, "assemble", "verifyJava17Bytecode", *REDUCED_FLAGS, *extra]))

    for label, cmd in steps:
        print(f"\n==> {label}: {' '.join(cmd)}", flush=True)
        if args.dry_run:
            continue
        code = subprocess.run(cmd, cwd=root, check=False).returncode
        if code != 0:
            print(f"\nFAILED: {label} (exit {code})", file=sys.stderr)
            return code

    if kind == "full" and base:
        cmd = [py, str(root / "scripts" / "diff-coverage.py"), "--baseline", base, "--min", "0.80"]
        print(f"\n==> changed-line coverage: {' '.join(cmd)}", flush=True)
        if not args.dry_run:
            if not has_jacoco_reports(root):
                print("No JaCoCo reports; nothing to gate (as in CI).")
            else:
                code = subprocess.run(cmd, cwd=root, check=False).returncode
                if code != 0:
                    print(f"\nFAILED: changed-line coverage (exit {code})", file=sys.stderr)
                    return code

    print("\nDry run: nothing executed." if args.dry_run else "\nAll CI gates passed for this change set.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
