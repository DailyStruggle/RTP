#!/usr/bin/env python3
"""Serialize concurrent POSIX ./gradlew runs by transitive project-dependency closure.

Mirrors gradle/rtp-gradle-lock.ps1 on POSIX hosts with python3 + fcntl:
- Module-scoped runs (every non-option argument is `:module:...:task`) compute the
  transitive in-tree `project(':...')` / `findProject(':...')` closure from
  `settings.gradle` and `build.gradle` files, then acquire `fcntl.flock` locks for
  every affected top-level module in deterministic lexicographical order while
  holding the global build lock (`rtp_gradle_build.flock`), and release the global
  lock before launching Gradle.
- Global or unscoped runs hold `rtp_gradle_build.flock` and wait for any active
  `rtp_gradle_mod_*.flock` locks to drain before launching Gradle.
- All lock file descriptors stay `FD_CLOEXEC` (`close_fds=True`) in this wrapper
  process so a newly spawned Gradle daemon never inherits a held lock descriptor.
"""

from __future__ import annotations

from collections import deque
from contextlib import contextmanager
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
from typing import Iterator, Sequence

try:
    import fcntl
except ImportError:  # pragma: no cover - Windows unit-test import fallback
    fcntl = None  # type: ignore[assignment]

DEFAULT_TIMEOUT_SECONDS = 600.0
POLL_INTERVAL_SECONDS = 5.0

# CLI flags whose value is the next argument token (neither a task nor a target).
VALUE_OPTIONS: frozenset[str] = frozenset({
    "--tests",
    "-x",
    "--exclude-task",
    "--console",
    "--warning-mode",
    "-p",
    "--project-dir",
    "-I",
    "--init-script",
    "-g",
    "--gradle-user-home",
    "-b",
    "--build-file",
    "-c",
    "--settings-file",
    "--include-build",
    "--priority",
    "-F",
    "--dependency-verification",
    "-M",
    "--write-verification-metadata",
    "--max-workers",
    "-D",
    "--system-prop",
    "-P",
    "--project-prop",
})

_TASK_TOKEN_RE = re.compile(r"^:[A-Za-z0-9_.\-:]+$")
_COMMENT_RE = re.compile(r"(?m)^\s*//[^\r\n]*|(?<!:)//[^\r\n]*|/\*[\s\S]*?\*/")
_INCLUDE_STMT_RE = re.compile(r"(?m)^\s*include(?:\s+|\s*\()([^\r\n]+)")
_QUOTED_PROJ_RE = re.compile(r"""['"]:?([A-Za-z0-9_.\-:]+)['"]""")
_PROJECT_DIR_RE = re.compile(
    r"""project\(\s*['"](:[^'"]+)['"]\s*\)\.projectDir\s*=\s*file\(\s*['"]([^'"]+)['"]\s*\)"""
)
_PROJECT_REF_RE = re.compile(
    r"""(?:\b(?:find)?[Pp]roject|\bevaluationDependsOn)\(\s*(?:path\s*[:=]\s*)?['"](:[^'"]+)['"]"""
)
_TASK_DEP_REF_RE = re.compile(
    r"""\bdependsOn\s*\(?\s*['"](:[A-Za-z0-9_.\-:]+):[A-Za-z0-9_.\-]+['"]"""
)


def strip_comments(text: str) -> str:
    """Remove Groovy/Kotlin line and block comments before regex extraction."""
    return _COMMENT_RE.sub("", text)


def parse_gradle_args(argv: Sequence[str]) -> tuple[list[str], bool]:
    """Extract unique target project paths and whether any unscoped/root task is present."""
    project_paths: list[str] = []
    seen: set[str] = set()
    has_other_tasks = False
    i = 0
    n = len(argv)
    while i < n:
        token = argv[i]
        if token.startswith("-"):
            if token in VALUE_OPTIONS:
                i += 2
                continue
            i += 1
            continue
        if _TASK_TOKEN_RE.match(token):
            parts = [p for p in token.lstrip(":").split(":") if p]
            if len(parts) > 1 and "::" not in token.lstrip(":"):
                proj = ":" + ":".join(parts[:-1])
                if proj not in seen:
                    seen.add(proj)
                    project_paths.append(proj)
            else:
                has_other_tasks = True
        else:
            has_other_tasks = True
        i += 1
    return project_paths, has_other_tasks


def parse_settings_projects(settings_text: str) -> tuple[dict[str, str], set[str], list[str]]:
    """Parse settings.gradle into (project_dirs, included_projects, top_level_modules)."""
    cleaned = strip_comments(settings_text)
    dirs: dict[str, str] = {}
    included: set[str] = set()
    top_modules: set[str] = set()

    for match in _INCLUDE_STMT_RE.finditer(cleaned):
        for proj_match in _QUOTED_PROJ_RE.finditer(match.group(1)):
            raw = proj_match.group(1).strip(":")
            if not raw:
                continue
            segments = raw.split(":")
            top_modules.add(segments[0])
            for depth in range(1, len(segments) + 1):
                anc = ":" + ":".join(segments[:depth])
                included.add(anc)
                dirs.setdefault(anc, "/".join(segments[:depth]))

    for proj_match in _PROJECT_DIR_RE.finditer(cleaned):
        proj = ":" + proj_match.group(1).lstrip(":")
        rel_dir = proj_match.group(2).replace("\\", "/").strip("/")
        dirs[proj] = rel_dir
        included.add(proj)
        top = proj.lstrip(":").split(":", 1)[0]
        if top:
            top_modules.add(top)

    return dirs, included, sorted(top_modules)


def extract_build_project_refs(build_text: str) -> set[str]:
    """Extract in-tree project paths referenced in a build.gradle / build.gradle.kts file."""
    cleaned = strip_comments(build_text)
    refs: set[str] = set()
    for match in _PROJECT_REF_RE.finditer(cleaned):
        dep = ":" + match.group(1).lstrip(":")
        if dep != ":":
            refs.add(dep)
    for match in _TASK_DEP_REF_RE.finditer(cleaned):
        dep = ":" + match.group(1).lstrip(":")
        if dep != ":":
            refs.add(dep)
    return refs


def compute_lock_closure(root_dir: Path, project_paths: Sequence[str]) -> list[str]:
    """Compute sorted unique top-level module names in the transitive dependency closure."""
    settings_file = root_dir / "settings.gradle"
    settings_text = ""
    if settings_file.is_file():
        try:
            settings_text = settings_file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            settings_text = ""

    dirs, included, _ = parse_settings_projects(settings_text)
    seen: set[str] = set()
    queue: deque[str] = deque()

    def enqueue(proj_path: str) -> None:
        norm = ":" + proj_path.lstrip(":")
        if norm == ":" or norm in seen:
            return
        seen.add(norm)
        queue.append(norm)
        # Include ancestor projects (parent build.gradle may declare shared dependencies)
        segments = norm.lstrip(":").split(":")
        for depth in range(1, len(segments)):
            anc = ":" + ":".join(segments[:depth])
            if anc not in seen:
                seen.add(anc)
                queue.append(anc)
        # Include descendant subprojects when a parent/aggregator project is targeted
        prefix = norm + ":"
        for inc in included:
            if inc.startswith(prefix) and inc not in seen:
                seen.add(inc)
                queue.append(inc)

    for start in project_paths:
        enqueue(start)

    while queue:
        current = queue.popleft()
        rel = dirs.get(current, current.lstrip(":").replace(":", "/"))
        proj_dir = root_dir / rel
        for build_name in ("build.gradle", "build.gradle.kts"):
            build_file = proj_dir / build_name
            if not build_file.is_file():
                continue
            try:
                text = build_file.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for dep in sorted(extract_build_project_refs(text)):
                enqueue(dep)

    return sorted({p.lstrip(":").split(":", 1)[0] for p in seen if p.lstrip(":")})


def _open_and_try_flock(path: Path, wait_seconds: float) -> object | None:
    """Open `path` and attempt to acquire an exclusive flock within `wait_seconds`."""
    if fcntl is None:
        raise RuntimeError("fcntl is required for POSIX flock serialization")
    fh = open(path, "a+", encoding="utf-8")
    os.set_inheritable(fh.fileno(), False)
    deadline = time.monotonic() + max(0.0, wait_seconds)
    while True:
        try:
            fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return fh
        except BlockingIOError:
            now = time.monotonic()
            if now >= deadline:
                fh.close()
                return None
            time.sleep(min(0.05, deadline - now))
        except OSError:
            fh.close()
            return None


def _release_and_close(fh: object) -> None:
    """Unlock and close an open lock file handle."""
    try:
        if fcntl is not None:
            fcntl.flock(fh.fileno(), fcntl.LOCK_UN)  # type: ignore[attr-defined]
    except OSError:
        pass
    try:
        fh.close()  # type: ignore[attr-defined]
    except OSError:
        pass


@contextmanager
def acquire_gradle_locks(
    root_dir: Path,
    gradle_args: Sequence[str],
    lock_dir: Path | None = None,
    timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
    poll_interval: float = POLL_INTERVAL_SECONDS,
) -> Iterator[tuple[str, list[str]]]:
    """Acquire either the sorted module-closure locks or the global build lock.

    Yields `(mode, lock_names)` where `mode` is `"module"` or `"global"`.
    """
    resolved_lock_dir = lock_dir or Path(os.environ.get("TMPDIR") or "/tmp")
    resolved_lock_dir.mkdir(parents=True, exist_ok=True)
    global_lock_path = resolved_lock_dir / "rtp_gradle_build.flock"

    project_paths, has_other_tasks = parse_gradle_args(gradle_args)
    held: list[object] = []
    waited = 0.0

    try:
        if project_paths and not has_other_tasks:
            lock_names = compute_lock_closure(root_dir, project_paths)
            label = ":" + ", :".join(lock_names)
            start_mono = time.monotonic()

            while not held:
                iter_start = time.monotonic()
                global_fh = _open_and_try_flock(global_lock_path, poll_interval)
                have_global = global_fh is not None
                if global_fh is not None:
                    got: list[object] = []
                    try:
                        for name in lock_names:
                            mod_path = resolved_lock_dir / f"rtp_gradle_mod_{name}.flock"
                            mod_fh = _open_and_try_flock(mod_path, 0.0)
                            if mod_fh is not None:
                                got.append(mod_fh)
                            else:
                                break
                        if len(got) == len(lock_names):
                            held = got
                        else:
                            for fh in got:
                                _release_and_close(fh)
                    finally:
                        _release_and_close(global_fh)

                if held:
                    break

                elapsed_iter = time.monotonic() - iter_start
                sleep_rem = max(0.0, poll_interval - elapsed_iter)
                if sleep_rem > 0.0:
                    time.sleep(sleep_rem)
                waited = time.monotonic() - start_mono
                what = f"module locks {label}" if have_global else f"global build lock (for {label})"
                print(f"[gradlew] Waiting for {what}... ({int(round(waited))}s)", file=sys.stderr, flush=True)
                if waited >= timeout_seconds:
                    print(f"[gradlew] Timed out waiting for module locks {label}", file=sys.stderr, flush=True)
                    raise TimeoutError(f"Timed out waiting for module locks {label}")

            yield ("module", lock_names)
        else:
            while True:
                global_fh = _open_and_try_flock(global_lock_path, poll_interval)
                if global_fh is not None:
                    held.append(global_fh)
                    break
                waited += poll_interval
                print(f"[gradlew] Waiting for global build lock... ({int(waited)}s)", file=sys.stderr, flush=True)
                if waited >= timeout_seconds:
                    print("[gradlew] Timed out waiting for the global Gradle build lock.", file=sys.stderr, flush=True)
                    raise TimeoutError("Timed out waiting for the global Gradle build lock.")

            # New module runs require rtp_gradle_build.flock to start, so once each
            # existing module lock drains it remains free for the rest of this build.
            for mod_path in sorted(resolved_lock_dir.glob("rtp_gradle_mod_*.flock")):
                while True:
                    mod_fh = _open_and_try_flock(mod_path, poll_interval)
                    if mod_fh is not None:
                        _release_and_close(mod_fh)
                        break
                    waited += poll_interval
                    print(
                        f"[gradlew] Waiting for module build ({mod_path.name})... ({int(waited)}s)",
                        file=sys.stderr,
                        flush=True,
                    )
                    if waited >= timeout_seconds:
                        print(
                            f"[gradlew] Timed out waiting for module build ({mod_path}).",
                            file=sys.stderr,
                            flush=True,
                        )
                        raise TimeoutError(f"Timed out waiting for module build ({mod_path}).")

            yield ("global", ["build"])
    finally:
        for fh in held:
            _release_and_close(fh)


def main(argv: Sequence[str]) -> int:
    if len(argv) >= 2 and argv[1] == "--print-closure":
        root_dir = Path(__file__).resolve().parent.parent
        gradle_args = argv[2:]
        project_paths, has_other_tasks = parse_gradle_args(gradle_args)
        if project_paths and not has_other_tasks:
            print(" ".join(compute_lock_closure(root_dir, project_paths)))
        else:
            print("build")
        return 0

    if len(argv) < 2:
        print("Usage: rtp-gradle-lock.py <gradlew-path> [gradle-args...]", file=sys.stderr)
        return 2

    gradlew_script = Path(argv[1]).resolve()
    root_dir = gradlew_script.parent
    gradle_args = list(argv[2:])

    env = os.environ.copy()
    env["RTP_GRADLE_LOCKED"] = "1"
    if os.access(gradlew_script, os.X_OK):
        cmd = [str(gradlew_script), *gradle_args]
    else:
        cmd = ["sh", str(gradlew_script), *gradle_args]

    timeout = float(os.environ.get("RTP_GRADLE_LOCK_TIMEOUT", str(DEFAULT_TIMEOUT_SECONDS)))
    try:
        with acquire_gradle_locks(root_dir, gradle_args, timeout_seconds=timeout):
            proc = subprocess.Popen(cmd, env=env, close_fds=True)

            def _forward_signal(signum: int, _frame: object) -> None:
                try:
                    proc.send_signal(signum)
                except OSError:
                    pass

            prev_handlers: dict[int, object] = {}
            for sig in (signal.SIGINT, signal.SIGTERM, getattr(signal, "SIGHUP", signal.SIGTERM)):
                try:
                    prev_handlers[sig] = signal.signal(sig, _forward_signal)
                except (OSError, ValueError):
                    pass
            try:
                return proc.wait()
            finally:
                for sig, prev in prev_handlers.items():
                    try:
                        signal.signal(sig, prev)  # type: ignore[arg-type]
                    except (OSError, ValueError):
                        pass
    except TimeoutError:
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
