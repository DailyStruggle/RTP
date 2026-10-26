#!/usr/bin/env python3
"""
Compatibility shim re-exporting the project-neutral `corpus-atlas/corpus_atlas.py` engine.
"""
from __future__ import annotations

import pathlib
import sys

_SHIM_DIR = pathlib.Path(__file__).resolve().parent
_ATLAS_DIR = _SHIM_DIR.parent / "corpus-atlas"
if str(_ATLAS_DIR) not in sys.path:
    sys.path.insert(0, str(_ATLAS_DIR))

_TARGET_FILE = _ATLAS_DIR / "corpus_atlas.py"
with _TARGET_FILE.open("rb") as _fh:
    exec(compile(_fh.read(), str(_TARGET_FILE), "exec"), globals(), globals())
