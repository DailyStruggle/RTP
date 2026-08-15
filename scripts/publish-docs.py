#!/usr/bin/env python3
"""Publish the LeafRTP documentation site to the gh-pages branch.

Manual, on-demand publisher for while the site is still unofficial and driven
by hand. Builds the MkDocs Material site and pushes the rendered output to the
gh-pages branch via `mkdocs gh-deploy` (the same command the CI workflow runs).

Usage:
    python3 scripts/publish-docs.py [-- <extra mkdocs gh-deploy args>]

Everything after a literal `--` is forwarded verbatim to `mkdocs gh-deploy`
(e.g. `-- --message "docs: tweak"` or `-- --remote-branch gh-pages`).

Requires the pinned docs toolchain:
    pip install -r docs/requirements.txt

On the Windows dev box invoke via the absolute Store interpreter path from the
project guidelines rather than the bare `python` alias.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    extra = sys.argv[1:]
    # Drop a leading `--` separator if the caller used one.
    if extra and extra[0] == "--":
        extra = extra[1:]

    cmd = [
        sys.executable,
        "-m",
        "mkdocs",
        "gh-deploy",
        "--force",
        "--clean",
        *extra,
    ]

    print("Publishing docs site with:", " ".join(cmd), flush=True)
    result = subprocess.run(cmd, cwd=REPO_ROOT)
    if result.returncode == 0:
        print(
            "\nPublished to the gh-pages branch. If GitHub Pages is not yet "
            "enabled, turn it on once under Settings -> Pages -> Deploy from "
            "branch -> gh-pages.",
            flush=True,
        )
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
