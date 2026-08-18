# StressTestRTP scripts

Local CLI tooling for post-processing stress-test artifacts. No internet
required at run time, no JetBrains IDE required, no Java required.

## `spark_summary.py`

Decodes a `.sparkprofile` produced by spark
(`/spark profiler --output <path>` or `/spark profiler --upload`'s downloaded
file) into a compact JSON summary suitable for AI analysis or markdown
write-ups.

### Why

Spark profiles are the canonical source of MSPT, per-tick TPS, per-window
CPU%, and per-thread CPU breakdowns during a stress phase. The harness's
`TpsMsptHeapSampler` cannot access MSPT on Spigot (no `getAverageTickTime()`
on the `Server` interface), so the spark profile is the only path to a real
MSPT distribution on that platform. On Paper / Folia, the harness CSV and
spark agree to within sampling error, but spark additionally provides the
*per-thread* CPU split (Server thread vs chunk-system threads vs plugin
schedulers) that makes "where the time goes" headline charts trivial.

### Requirements

- **Python 3.10+** on PATH. That is *all*. No `protobuf` package, no
  `protoc`. The script ships its own minimal protobuf wire-format reader
  scoped to the message types we need.

### Usage

```powershell
python helpers\StressTestRTP\scripts\spark_summary.py <file.sparkprofile> `
    --label rtp-paper-1.21.11-phase1 `
    --json helpers\StressTestRTP\runs\<runid>-spark\rtp.json
```

For a full run (4 phases, in order matching `<runid>-phases.csv`):

```powershell
$run   = "20260502-125523"
$src   = "C:\GameServers\Minecraft\testServer\RTP-Spigot\1.20.1\plugins\spark"
$dst   = "helpers\StressTestRTP\runs\$run-spark"
New-Item -Force -ItemType Directory $dst | Out-Null

# Order must match phase order in the run's `-phases.csv`.
$phases = @(
    @{ label="rtp";       file="profile-2026-05-02_12.56.38.sparkprofile" },
    @{ label="betterrtp"; file="profile-2026-05-02_12.57.49.sparkprofile" },
    @{ label="huskhomes"; file="profile-2026-05-02_12.59.00.sparkprofile" },
    @{ label="jakesrtp";  file="profile-2026-05-02_13.00.12.sparkprofile" }
)
foreach ($p in $phases) {
    python helpers\StressTestRTP\scripts\spark_summary.py `
        "$src\$($p.file)" --label $p.label --quiet `
        --json "$dst\$($p.label).json"
}
```

### What the JSON contains

```jsonc
{
  "label": "<phase_label>",
  "platform": {                     // server identity
    "name": "Bukkit", "version": "...", "minecraft_version": "v1_20_R1",
    "brand": "CraftBukkit" | "Paper" | "Folia"
  },
  "capture": {
    "start_epoch_ms": 1777746822315, "end_epoch_ms": 1777746883420,
    "duration_s": 61.1, "ticks": 463, "interval_us": 4000
  },
  "mspt_rolling": {                 // null on Spigot (no API). Paper/Folia: populated.
    "last1m": { "mean": ..., "max": ..., "min": ..., "median": ..., "percentile95": ... },
    "last5m": { ... },
    "game_max_ideal_mspt": 50
  },
  "tps_rolling": { "last1m": ..., "last5m": ..., "last15m": ... },
  "mspt_window_aggregate": {        // aggregated over per-tick windows
    "median_of_medians": ..., "p95_of_medians": ...,
    "max_of_medians": ...,  "max_of_maxes": ...
  },
  "tps_window_aggregate":  { "median": ..., "p05": ..., "min": ... },
  "cpu_process_window_aggregate": { "median": ..., "p95": ..., "max": ... },
  "thread_cpu_top": [               // sum of `times[]` across windows per thread
    { "thread": "Server thread", "weight": 60912.0, "share_pct": 78.4 },
    ...
  ],
  "windows": [                      // raw per-window rows for plotting / debugging
    { "window": ..., "tps": ..., "mspt_median": ..., "mspt_max": ...,
      "cpu_process": ..., "cpu_system": ..., "players": ..., "entities": ...,
      "chunks": ... },
    ...
  ]
}
```

`thread_cpu_top` shows the server-internal labelling; the most useful
buckets are typically:

- `Server thread` — the main tick thread. Plugin work that blocks here
  drops MSPT.
- `Chunk I/O Worker-Main-N`, `Worker-Main-N`, `chunk-source` — chunk
  generation / load. Spikes here under teleport storms indicate the
  plugin is causing chunk loads on the hot path.
- `RTP-async-...`, `HuskHomes IOPool`, `BetterRTP-...`, plugin-named
  pools — async work scheduled by the plugin. Heavy CPU here is
  *good* (it's not blocking the tick loop) but indicates total CPU
  cost of the plugin.

### Field-name caveats / version drift

- The schema is mirrored from `lucko/spark`'s `spark.proto` and
  `spark_sampler.proto` as of 2026-05. Field numbers in spark are stable
  (proto3 conventions), so older / newer spark builds should decode without
  issue, but newly added fields will simply be ignored.
- On **Spigot**, `PlatformStatistics.mspt` is empty because the server has
  no `getAverageTickTime()` API. This is not a bug — `mspt_rolling` and
  `mspt_window_aggregate` will be null. Use `tps_window_aggregate` and
  `cpu_process_window_aggregate` instead.
- If `thread_cpu_top` only shows `Server thread`, the profile was captured
  with `--thread-dumper-mode REGEX` or a single-thread dumper. Re-capture
  with `/spark profiler --start --thread *` (default `--ticks-over` 0) for
  a full per-thread split.

### When to re-run vs. trust the CSV

- For **latency, success rate, per-attempt cost** → harness CSV is canonical.
- For **MSPT, TPS-over-time, per-thread CPU split** → spark profile is canonical.
- For **chunk-loading attribution per attempt** → harness CSV (per-attempt
  delta), but be aware of the concurrency double-count caveat documented in
  `PRE_WRITEUP.md section 5b`.

### Recipe: filling out `PRE_WRITEUP.md section 5b`

After running the per-phase script:

```powershell
# Aggregate the four JSONs into a single AI-feedable bundle.
$run = "20260502-125523"
$dst = "helpers\StressTestRTP\runs\$run-spark"
$summary = Get-ChildItem $dst -Filter *.json | ForEach-Object {
    Get-Content $_.FullName -Raw | ConvertFrom-Json
}
$summary | ConvertTo-Json -Depth 10 | Out-File "$dst\all-phases.json"
```

Feed `all-phases.json` to your LLM with a prompt like:

> Given these four spark-profile summaries, fill the row
> "MSPT mean / p95 / max / cpu_process p95 / Server-thread CPU%" for each
> phase, and call out any phase where MSPT p95 ≥ 50 ms.

That's the workflow.
