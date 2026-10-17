# ADR-111 — Live-Server Behaviour Fuzzing: Plugin-Agnostic Oracles, Reproduced Findings and Coverage Feedback

**Status:** Accepted (2026-10-13). Sections 2.1-2.5 and 2.8 record the behaviour that ships. Sections 2.6 and 2.7 (coverage feedback, fast fault mode, new oracles, action vocabulary) are accepted direction: each shall have its own approved proposal (Rule D-005) before implementation.
**Date:** 2026-10-13
**Relevant Requirements:** REQ-RTP-S-001, REQ-RTP-S-002, REQ-RTP-S-004
**Related ADRs:** [ADR-102](ADR-102-coverage-guided-fuzz-testing-and-parser-input-hardening.md), [ADR-103](ADR-103-multi-version-platform-adapter-deduplication.md), [ADR-094](ADR-094-quality-engineering-gates.md), [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md), [ADR-091](ADR-091-headless-player-protocol-driver-for-devstack.md), [ADR-008](ADR-008-memory-tracker-active-gc.md), [ADR-012](ADR-012-chunk-reservation-abstraction.md)

---

## 1. Context

[ADR-102](ADR-102-coverage-guided-fuzz-testing-and-parser-input-hardening.md) fuzzes stateless parsers in-process with Jazzer (`AnvilReader`, `RespProtocol`). Many RTP bugs are not parser bugs. They depend on state, lifecycle and timing on a real server: a player quitting while a teleport is pending, `/rtp reload` in the middle of a burst, chunk tickets that are never released, a listener that throws only after a particular sequence of joins and deaths. An in-process harness cannot reproduce the Paper scheduler, the network timing or the plugin lifecycle these bugs need.

`scripts/bench_fuzz.py` drives real Minecraft clients (`devstack/clients/bench-swarm.js`, Mineflayer as in [ADR-091](ADR-091-headless-player-protocol-driver-for-devstack.md)) against one plugin per server restart, with the StressTestRTP harness taking post-GC checkpoints. It started as a memory-leak hunter. Its behaviour generator, keep-what's-new corpus, search loop and delta-debugging shrinker are not leak-specific. Its log and permission detectors were, at first, only logged: the corpus, the shrinker and the verdict ignored them, so a reproducible exception in the target still ended in PASS.

Three limits shape what is worth building:
- **Speed:** with `--reps 3` an episode costs about 2 to 2.5 minutes, mostly repetitions and full-GC checkpoints that only the leak oracle needs.
- **Vocabulary:** bots can only send the target command, wait, move, quit, rejoin and die.
- **No code-level feedback:** plugin chat replies stand in for coverage, so a PASS says nothing about how much of the target ran.

---

## 2. Decision

### 2.1 Two fuzzing tiers

- **Tier P (in-process, ADR-102):** byte and wire parsers and other stateless decoders, fuzzed by Jazzer.
- **Tier L (live server, this ADR):** player behaviour against one plugin per server restart, run by `bench_fuzz.py`.
- A new target shall go in the cheapest tier that can show the bug. Tier L shall not be used for a defect that a Tier P target can reach.
- A middle tier (Jazzer driving the `rtp-core` command pipeline against a fake platform) is noted and not adopted.

### 2.2 Tool invariants

- **Plugin-agnostic:** the only per-target inputs shall be the command each bot sends (`--cmd`) and, optionally, package-to-plugin prefixes (`--plugin-packages`) and the target's plugin and jar names (`--names`, `--jars`). No oracle, generator rule or score term shall be specific to one plugin.
- **Stdlib Python:** the Python side shall use the standard library only, and shall read harness output only as JSON or text files.
- **Seeded:** every episode shall be reproducible from its seed and trace (`--replay`, `--ep`).
- **Isolation:** each target shall run on a server restarted with only its own plugin jar (plus the harness).
- **Same-run control:** the plugin-free `direct` control shall run first, on the same seeds, in the same run. Its outputs (`control.json`, `control-classes.json`, `control-heap.json`, `control-findings.json`) shall never be reused across runs.

### 2.3 Oracle model

- **Finding oracles** implement `FindingOracle`: `begin()` before the episode's bots start and `observe(events, programs, target)` after they finish, returning findings. `ServerLogOracle` (uncaught exceptions with a stack trace, `Could not pass event`, tick stalls) and `LeastPrivilegeOracle` (permission denials that did not happen) are the shipped finding oracles.
- **Signature:** every finding shall carry a stable signature: a hash of its class and a key made of the owning plugin, the root exception type and the first frame in that plugin's packages, with digits dropped. The finding id shall derive from the signature, so the same bug seen twice is one finding.
- **Parsing:** an exception shall count only when a qualified throwable type is followed by at least one stack-trace line. A replaced or shorter log shall be read from its start.
- **Attribution:** a finding shall be attributed by the packages of its stack frames (`PLUGIN_PACKAGES` plus `--plugin-packages`), with no fallback to the target. A finding counts against the target only on an exact match with the target label or its `--names` / `--jars` entry. Tick stalls shall be attributed to `Server`.
- **Leak oracle:** the leak path keeps its own confirmation: checkpoints, the per-count fit against the control, and the bots x reps ladder (`--rungs`, `confirm_rule`).

### 2.4 Procedure

Each target runs the same procedure:

1. **Search:** generated and mutated episodes, steered by the cross-entropy search.
2. **Keep:** an episode joins the corpus when it reaches a new key. The key sources are `(previous action, normalised reply)` pairs, count extremes, finding signatures and, once coverage exists, newly reached coverage probes.
3. **Control subtraction:** a signature that the `direct` control also produced shall never be replayed or reported against the target.
4. **Replay:** a new target-attributed signature shall be replayed `--finding-n` times, one run each, drawing on `--replay-budget`. `--finding-k` reproductions confirm it.
5. **Shrink:** up to `--finding-shrink` confirmed findings per seed shall be delta-debugged (`ddmin`, `shrink_bots`) to the fewest actions that still reproduce the signature, drawing on `--shrink-budget`.
6. **Verdict** (2.5).

A non-leak finding shall not need amplification: it shall not go through the ladder.

### 2.5 Verdict semantics

- **FAIL:** a leak confirmed at the top rung, or a reproduced target-attributed finding.
- **INCONCLUSIVE:** a signal that did not confirm, such as a leak candidate lost at scale or a target finding that did not reproduce.
- **PASS:** nothing found within budget. A PASS shall never be read as proof that the target is fault-free. Once coverage feedback exists (2.6), every PASS shall state how much of the target's code the run reached.
- `verdict.json` shall list one entry per signature with its replay result, and `confirmed_by` shall name the oracles (leak and/or finding oracles) behind a FAIL. Per-target `findings.jsonl` and `finding-replays.jsonl` keep the raw evidence.

### 2.6 Coverage feedback (accepted direction)

- The bench server shall run with the JaCoCo agent, staged by the `extractJacocoAgent` task and attached as in [ADR-103](ADR-103-multi-version-platform-adapter-deduplication.md) and `devstack/docker-compose.coverage.yml`. `includes=` shall be limited to the target's packages (from `PLUGIN_PACKAGES` / `--plugin-packages`, or from jar class ownership). Classes are instrumented as they load, so no plugin source is needed.
- A StressTestRTP command, `/rtpstress coverage <label>`, shall read and reset the execution data through the agent's runtime API in the same JVM, and write per-class probe data as JSON into the run folder. The Python side shall read only that JSON (2.2).
- Newly reached probes shall feed the corpus as a key source (2.4) and shall weight the choice of mutation parents towards rarely reached probes.
- Coverage shall be reported as probes reached per target. Exact line and branch figures stay with the offline `jacocoServerReport` task.
- JaCoCo probes are booleans: the feedback has no hit counts.

### 2.7 Fast fault mode, new oracles and action vocabulary (accepted direction)

- **Fast fault mode:** a mode with one repetition per episode and no leak checkpoints, running only the finding oracles and the coverage dump. The leak oracle shall run only on episodes that reached new coverage or a new signature.
- **New oracles**, each with a control-subtracted signature:
  - silently dropped teleport (S-004): the command was sent and the bot got neither a teleport nor a reply within a bound;
  - unsafe landing (S-001): the block at the bot's arrival position fails the safety check;
  - chunk-ticket or force-loaded chunk growth (S-002, [ADR-008](ADR-008-memory-tracker-active-gc.md), [ADR-012](ADR-012-chunk-reservation-abstraction.md)) as a checkpoint count in the leak fit;
  - tick-time excess over the control, replacing log-line stall heuristics as the scored signal;
  - thread and scheduled-task growth.
- **Action vocabulary:** commands discovered from `plugin.yml` and tab completion, argument values learnt from tab completion, GUI clicks and chat, prioritised by the coverage each action type gains.

### 2.8 Gates and outputs

- Tier L runs shall be opt-in and local. They shall never be part of `gradlew test` or CI, consistent with the opt-in gates of [ADR-094](ADR-094-quality-engineering-gates.md).
- Every oracle shall have a planted-bug test against the fake server in `scripts/tests/test_bench_fuzz.py` before it can affect the verdict.
- A confirmed finding in LeafRTP shall be recorded in `docs/dev/POTENTIAL_BUGS.md`, or filed in the Linear tracker with the `source:bench` label.
- A published claim about another plugin's findings shall follow the provenance and publication rules of [ADR-080](ADR-080-opt-in-simulation-benchmark-tier.md).

---

## 3. Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Extend Jazzer (Tier P) only | Cannot reach real-server races, lifecycle and scheduler-dependent bugs |
| Run `bench_fuzz` logic in-process against a fake server | Loses the real Paper scheduler and client timing the target bugs depend on |
| Python reads the JaCoCo agent's `tcpserver` output | Needs a hand-written parser for the binary `.exec` format in stdlib Python |
| `dumponexit` once per server restart | One coverage sample per target, so it cannot guide the search episode by episode |
| Custom bytecode instrumentation for hit counts | Large cost and risk for little gain at minutes per episode |
| Plugin-specific oracles | Break the plugin-agnostic invariant and the fairness of cross-plugin comparison |

---

## 4. Consequences

### Positive
- A reproducible crash in the target fails the run instead of ending in PASS.
- Findings deduplicate by signature across episodes, the control comparison and the report.
- One procedure (keep, control subtraction, replay, shrink, verdict) covers leaks and runtime faults.
- Once coverage exists, a PASS states what was exercised.

### Negative / Trade-offs
- StressTestRTP gains a JaCoCo core (execution data) dependency once coverage feedback lands, and the bench server's launch flags change.
- Episodes stay slow until the fast fault mode exists.
- The permission oracle stays close to inactive under the default `--grant '*'`, since nothing is denied.

---

## 5. References

- `scripts/bench_fuzz.py`, `scripts/tests/test_bench_fuzz.py`, `devstack/clients/bench-swarm.js`
- [`helpers/StressTestRTP/LEAK_FUZZING.md`](../../helpers/StressTestRTP/LEAK_FUZZING.md)
- [`docs/dev/BEHAVIOUR_FUZZING_PLAN.md`](../dev/BEHAVIOUR_FUZZING_PLAN.md) (findings, goals and staged delivery)
- `devstack/docker-compose.coverage.yml`; `build.gradle` tasks `extractJacocoAgent` and `jacocoServerReport`
- Linear tracker RTP-28 (Tier P target gaps)
