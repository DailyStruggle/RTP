# Behaviour fuzzing plan: from leak hunting to coverage-guided fault finding

`scripts/bench_fuzz.py` drives real Minecraft clients against one plugin at a time and was built to find memory leaks ([`LEAK_FUZZING.md`](../../helpers/StressTestRTP/LEAK_FUZZING.md)). This plan records what in it is already generic, what ties it to leaks, and the stages that turn it into a general, coverage-guided behaviour fuzzer that also finds crashes, safety violations and stalls. Governing decision: [ADR-111](../adr/ADR-111-live-server-behaviour-fuzzing.md). Quality-gate context: [ADR-094](../adr/ADR-094-quality-engineering-gates.md).

---

## Findings

### What is generic and what is leak-specific

| Layer | Where (`bench_fuzz.py`) | Leak-specific? |
|---|---|---|
| Behaviour generator and mutation | `gen_program`, `gen_episode`, `mutate`, `novelty_params` | No |
| Guidance (keep what is new) | `extract_states`, `console_states`, `Corpus.consider` | No: novelty is a set of keys, any key source fits |
| Search loop (CEM, novelty share, restarts, surprise replay) | `SearchState`, `Cem`, `check_surprise` | The loop is generic; the score it chases is leaks |
| Shrinking | `ddmin`, `shrink_bots` | No: takes any "still reproduces?" probe |
| Objective and oracle | `fit_counts`, `screen_classes`, `net_leak`, `score_episode`, `confirm_rule` | Yes |
| Scale-up ladder | `climb`, `run_rung` | Mostly: it grows a leak until it is large enough to confirm |
| Verdict | `target_verdict` | Was leak-only |
| Other detectors | `ServerLogOracle`, `LeastPrivilegeOracle` | Generic, but were only logged |

### Defects in the detectors as first written

- Findings went to `findings.jsonl` and the log only; corpus, score, shrinker and verdict ignored them, so a reproducible exception in the target still ended in **PASS**.
- `"java.lang." in line` / `"Error:" in line` matched ordinary messages; severity was keyed on `NullPointerException`.
- With no known package in the trace, attribution fell back to the target, so a harness or vanilla exception was blamed on the plugin and could never be matched against the control.
- "Can't keep up" was attributed to the target without evidence.
- The log offset did not survive a replaced `latest.log` (restart), and the file was seeked in text mode.
- Finding ids were timestamps, so the same bug never deduplicated.
- The swarm emits `denial_failure` for every chat line after a listed admin command, but the grammar never sends one, and `--grant *` allows everything: the permission oracle is close to dead and noisy.

### Limits that decide what is worth building

- **Speed:** with `--reps 3` an episode costs about 2 to 2.5 minutes, mostly repetitions and full-GC checkpoints that only the leak oracle needs.
- **Vocabulary:** bots can only `rtp`, `rtp2`, wait, move, quit, rejoin and die. Coverage will plateau quickly until commands, arguments, GUI clicks and chat are added.
- **Plugin replies stand in for coverage.** There is no code-level feedback from the server, so "PASS within budget" says nothing about how much of the plugin ran.

---

## Goals

1. **Every detector is a first-class oracle.** A reproduced, target-attributed finding fails the verdict like a confirmed leak, and the report names the oracle.
2. **Stable identity for findings.** The same bug seen twice is one finding, in the corpus, the control comparison and the report.
3. **Code coverage from the live server**, without plugin source, to guide the search and to qualify every PASS with "x % of the target's branches reached".
4. **A fast fault-only mode** several times quicker than leak mode, so most of the budget explores and only promising episodes pay for leak checkpoints.
5. **A larger action vocabulary**, prioritised by the coverage it unlocks.
6. **Plugin-agnostic throughout:** per-target input stays the command and, at most, package prefixes.

### Non-goals

- Running `bench_fuzz` in-process or replacing the Jazzer targets. Jazzer suits stateless parsers (RTP-28 lists the gaps); `bench_fuzz` finds state, lifecycle and timing bugs only a real server shows.
- Proving a plugin fault-free: PASS stays "nothing found within budget".

---

## Stages

### Stage 1: finding oracles wired into the loop (done)

- `FindingOracle` interface (`begin()` before the bots, `observe(events, programs, target)` after); `ServerLogOracle` and `LeastPrivilegeOracle` implement it. The leak path keeps its own checkpoints, fit and ladder.
- Signatures: hash of class plus owning plugin, root exception type and first frame in that plugin, digits dropped. Ids derive from the signature.
- Stricter parsing: a qualified throwable type followed by a trace line; `[hh:mm:ss LEVEL]:` prefixes from `printStackTrace()` handled; replaced or shorter logs read from the start; binary seek.
- Attribution by the topmost frame in a known package (`PLUGIN_PACKAGES` + `--plugin-packages`); no fallback to the target. Target match is exact against the label and its `--names` / `--jars` entries. Stalls are `Server`.
- Corpus keeps an episode that reaches a new signature. `direct` writes `control-findings.json`; matching signatures are never replayed.
- A new target signature is replayed `--finding-n` times (one run each, `reps=1`) from `--replay-budget`; `--finding-k` hits confirm; `--finding-shrink` delta-debugs it from `--shrink-budget`.
- Verdict: FAIL on a reproduced target finding, INCONCLUSIVE on a one-off; `verdict.json` holds one entry per signature and `confirmed_by`. Report lists signatures with their replay status.
- Tests: `FindingSignatureTest`, `FindingLoopTest` in `scripts/tests/test_bench_fuzz.py`.

### Stage 2: JaCoCo coverage from the bench server

- Launch the bench server with the JaCoCo agent restricted to the target's packages (`includes=` from `PLUGIN_PACKAGES` / `--plugin-packages`, or from jar class ownership as `LeakProbe` does). Classes are instrumented as they load; no plugin source needed.
- `/rtpstress coverage <label>` in StressTestRTP: dump and reset execution data through the agent's runtime API, write compact JSON (`{class: probe bitset hash}` or probe ids) to the runs folder. Python stays stdlib-only and reads JSON.
- Feed new probes into `Corpus.consider` as a third key source; weight mutation parents by rarely hit probes; optionally add coverage gain to the CEM objective.
- Report per target: branches reached, and the unreached branches by class (shows which commands and arguments the bots never trigger).
- Limitation: JaCoCo probes are booleans (branch coverage, no hit counts). Acceptable at minutes per episode.
- **Acceptance:** a run against LeafRTP reports a coverage percentage per target; an episode that reaches new probes but no new reply is kept; overhead on episode time under 10 %.

### Stage 3: fast fault-only mode

- `--mode faults`: `--reps 1`, no `leakcheck` checkpoints, coverage dump and finding oracles only. Run the leak oracle only on episodes that reached new coverage or a new signature.
- Finding replays in this mode skip the checkpoint (in leak mode they take a quick one to keep the leak baseline consistent).
- **Acceptance:** at least 3x more episodes per hour than leak mode on the same server; the same planted exception is still found and confirmed.
- **Status:** accepted. The planted-exception test passes (`FaultModeTest`). Live throughput measured on Paper 26.2 on Threadripper host `leaf26`: fault mode (`fuzz-20261015-214736`) ran 24 episodes in 3368.7 s (`25.65 episodes/hour` with 4 promotions to 3-rep leak checks; ~96 episodes/hour in pure exploration prior to promotion), and the companion leak baseline (`fuzz-20261016-020101`) ran 24 episodes in 4496.2 s (`19.22 episodes/hour`). Pure exploration achieved ~5.0x baseline throughput, exceeding the 3x acceptance threshold (1.33x net with full promotion leak checks). Both runs confirmed zero retained player sessions (`retained+0`) and zero target-attributed exceptions. Details and table in [`LEAK_FUZZING.md`](../../helpers/StressTestRTP/LEAK_FUZZING.md#fault-mode-throughput-comparison).

### Stage 4: new oracles from data already collected

- **Silently dropped teleport (S-004):** a bot sent the command and got neither a `tp` event nor a chat reply within N seconds.
- **Unsafe landing (S-001):** block check at the bot's `tp` position, through the harness or bot-side.
- **Growing chunk tickets / force-loaded chunks (S-002):** as a checkpoint count, it slots into the existing leak fit.
- **Tick-time spikes per episode:** numeric MSPT excess over the control, replacing the "Can't keep up" line heuristic.
- **Growing threads or scheduled tasks:** already counted; promote to their own oracle.
- **Acceptance:** each oracle has a planted-bug test against the fake server and a control-subtracted signature.

### Stage 5: action vocabulary

- Command discovery from `plugin.yml` and tab completion; argument values learnt from tab completion (`/rtp region:... world:...`); GUI clicks and chat.
- Prioritise by coverage gained per action type (a bandit over action types).
- Cross-feed: arguments and config values that reach new coverage become Jazzer seeds.

---

## Process

- Stage 1 changed only `scripts/bench_fuzz.py`, its tests and docs.
- Stages 2 to 5 are planned and on hold in Linear `RTP-55` (team `LeafRTP`) until stage 1 has been through extended real-server runs with the results recorded in [`LEAK_FUZZING.md`](../../helpers/StressTestRTP/LEAK_FUZZING.md).
- Stages 2 to 5 touch StressTestRTP (Java), `bench-swarm.js` and the bench server's launch flags: each needs an approved proposal (Rule D-005). Their direction is fixed by [ADR-111](../adr/ADR-111-live-server-behaviour-fuzzing.md), including the coverage channel: `/rtpstress coverage <label>` reads and resets the JaCoCo agent through its runtime API in the server JVM and writes probe JSON, so the Python side stays stdlib-only.
- Optional middle tier, not scheduled: Jazzer driving the `rtp-core` command pipeline against a fake platform (coverage-guided at thousands of runs per second; logic exceptions, not real-server races).
