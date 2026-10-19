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
| `test_ci_scope.py` | `ci-scope.py` | settings.gradle include/projectDir parsing, ignorable paths, leaf vs full vs none classification, reverse-dependency closure among addons/helpers, core test consumers found from path literals, per-set flags (implicit parent projects, inherited parent refs, `findProject` bundling edges, out-of-project paths flag every set), per-set task lists (build vs test-only), workflow `needs:` parsing and the job-order check against the set graph (incl. the live `gradle.yml`), fail-to-full on missing or unresolvable baseline |
| `test_locale_common.py` | `locale_common.py` | TSV cell encode/decode (incl. the documented 1->4 backslash quirk), comment-cell escaping, RFC-4180 CSV read/write/split, mojibake scan, relpath + langmap helpers |
| `test_scan_command_parameters.py` | `scan_command_parameters.py` | string/comment-aware Java argument splitting, whitespace normalization, line numbering, end-to-end scan of `new <X>Parameter(...)` and `super(...)` patterns |
| `test_bench_matrix.py` | `bench_matrix.py` | swarm stats-file tail parsing, bandwidth poller sums and NOT MEASURED (-1) on a stalled file, swaps total, live log watcher counts and first line across appends and rotation, plan order across passes, ramp idle/warm-up config parse, resume keys, and `run_matrix` against a fake driver and server dir: outcomes, run-file and log capture, single load attempt for an unsupported plugin, resume skipping done rows |
| `test_bench_matrix_report.py` | `bench_matrix_report.py` | a captured Paper ramp run (`fixtures/bench_matrix`) with synthetic bandwidth and log: parsed stages, exact idle-baseline net bandwidth, counter resets and -1 samples, online-dip flag, Folia watchdog / async-chunk / off-thread counts, plugin attribution by package, NOT MEASURED and `unknown` markers, swap notes |
| `test_folia_spark_check.py` | `folia_spark_check.py` | region-thread weight from a spark thread list (Folia vs Paper naming) and newest-profile selection by age |
| `test_bench_fuzz.py` | `bench_fuzz.py` | seeded grammar and mutator determinism (also across processes) and swarm-limit validity, reply normalisation, (previous action, state) pairs, keep-what's-new corpus, count and per-class deltas, incremental runs-CSV reads, LuckPerms grant/revert with prior values, quick checkpoint handshake, one target's episode loop against a fake server and swarm, behaviour features from timed events, ridge recovery of a planted coefficient and control-masked flags, net leak per minute, CEM convergence on a simulated oracle (variance floor, stall restart), rare-leak discovery via novelty, ddmin shrinking with k of n reproduction (ambient drift never reproduces), optimiser.jsonl and control-fit wiring, the real swarm's control channel with no server (skipped without node/mineflayer) |
| `test_locale_pipeline.py` | `locale-files-to-csv.py`, `locale-files-from-csv.py`, `locale-changeset-to-csv.py`, `reconcile-locale-csvs.py`, `locale-changeset-from-csv.py` | pure helpers of each script + an end-to-end self-consistency test that runs the real scripts over a synthetic resources tree and asserts the `to-csv -> from-csv -> to-csv` round trip is byte-stable (idempotent) |

## Not yet covered

The remaining `.py` scripts (`schematics/*.py`, `helpers/StressTestRTP/scripts/*.py`)
have no tests yet. Scripts that are still PowerShell-only are listed in the
migration discussion; each should get a test here as it is ported.
