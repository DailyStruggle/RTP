#!/usr/bin/env python3
"""
Compatibility re-export of `corpus-atlas/corpus_atlas.py` so `corpus-atlas/`
remains the single source of truth.
"""
from __future__ import annotations

import importlib.util as _ilu
import pathlib
import sys

_ATLAS_DIR = pathlib.Path(__file__).resolve().parent.parent / "corpus-atlas"
if str(_ATLAS_DIR) not in sys.path:
    sys.path.insert(0, str(_ATLAS_DIR))

_spec = _ilu.spec_from_file_location("_atlas_corpus_atlas", _ATLAS_DIR / "corpus_atlas.py")
assert _spec is not None and _spec.loader is not None
_mod = sys.modules.get(_spec.name)
if _mod is None:
    _mod = _ilu.module_from_spec(_spec)
    sys.modules[_spec.name] = _mod
    _spec.loader.exec_module(_mod)
globals().update({k: v for k, v in vars(_mod).items() if not k.startswith("__")})

if __name__ == "__main__":
    raise SystemExit(_mod.main())
