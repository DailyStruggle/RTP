# scripts/tests

Cross-platform (`unittest`, stdlib only) tests for the Python scripts under
`scripts/`. They run identically on Windows and Linux, which is the point of the
PowerShell-to-Python migration: every script that gets ported should land here
with coverage before its `.ps1` counterpart is retired.

## Run

From the repo root:

```bash
python -m unittest discover -s scripts/tests -p "test_*.py"
```

(Use `-v` for per-test output.)

## What is covered

| Test file | Script under test | Focus |
|-----------|-------------------|-------|
| `test_locale_common.py` | `locale_common.py` | TSV cell encode/decode (incl. the documented 1->4 backslash quirk), comment-cell escaping, RFC-4180 CSV read/write/split, mojibake scan, relpath + langmap helpers |
| `test_scan_command_parameters.py` | `scan_command_parameters.py` | string/comment-aware Java argument splitting, whitespace normalization, line numbering, end-to-end scan of `new <X>Parameter(...)` and `super(...)` patterns |
| `test_locale_pipeline.py` | `locale-files-to-csv.py`, `locale-files-from-csv.py`, `locale-changeset-to-csv.py`, `reconcile-locale-csvs.py`, `locale-changeset-from-csv.py` | pure helpers of each script + an end-to-end self-consistency test that runs the real scripts over a synthetic resources tree and asserts the `to-csv -> from-csv -> to-csv` round trip is byte-stable (idempotent) |

## Not yet covered

The remaining `.py` scripts (`schematics/*.py`, `helpers/StressTestRTP/scripts/*.py`)
have no tests yet. Scripts that are still PowerShell-only are listed in the
migration discussion; each should get a test here as it is ported.
