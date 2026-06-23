"""Temporary: verify messages/ split is a faithful copy of HEAD:messages.yml.

Reads the original via `git show` (bytes -> UTF-8) to avoid any shell re-encoding,
and forces UTF-8 stdout so icon glyphs print without a cp1252 crash.
"""
import io
import subprocess
import sys
from pathlib import Path
import yaml

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = Path(__file__).resolve().parent.parent
ORIG_REF = "HEAD:rtp-plugin/src/main/resources/messages.yml"
SPLIT_DIR = REPO / "rtp-plugin" / "src" / "main" / "resources" / "messages"
MEMBERS = ["placeholders.yml", "player.yml", "network.yml", "commands.yml", "system.yml"]

raw = subprocess.run(["git", "show", ORIG_REF], cwd=REPO, capture_output=True, check=True).stdout
orig = yaml.safe_load(raw.decode("utf-8"))

merged, dups = {}, []
for m in MEMBERS:
    d = yaml.safe_load((SPLIT_DIR / m).read_text(encoding="utf-8")) or {}
    for k, v in d.items():
        if k in merged:
            dups.append(k)
        merged[k] = v

ok, sk = set(orig), set(merged)
missing = sorted(ok - sk)
extra = sorted(sk - ok)
changed = [k for k in sorted(ok & sk) if orig[k] != merged[k]]

print(f"original keys: {len(ok)}, split keys: {len(sk)}")
print(f"duplicates: {dups}")
print(f"missing: {missing}")
print(f"extra: {extra}")
print(f"value-changed: {changed}")
for k in changed:
    print(f"  [{k}]\n    orig : {orig[k]!r}\n    split: {merged[k]!r}")
print("OK" if not (dups or missing or extra or changed) else "DIFFERENCES FOUND")
