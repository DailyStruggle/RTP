---
sessionId: session-261013-105738-1f09
---

# Requirements

### Overview & Goals
Write down, as an ADR, the decisions behind the behaviour fuzzer (`scripts/bench_fuzz.py` + StressTestRTP + `devstack/clients/bench-swarm.js`) and the way it is run, so steps 2-5 build on an agreed direction. Today the only fuzzing record is ADR-102, which covers Jazzer in-process parser fuzzing. The live-server fuzzer, its oracle model and its verdict meaning are not recorded anywhere architectural.

### Scope
**In scope**
- New `docs/adr/ADR-111-live-server-behaviour-fuzzing.md`, status **Accepted**. It accepts what ships now (step 1: tool, oracles, signatures, attribution, replay/shrink, verdict) and records steps 2-5 as accepted direction, each still behind its own D-005 proposal.
- Cross-links in `docs/adr/README.md` (index row), ADR-102 (*Related ADRs*), `docs/dev/BEHAVIOUR_FUZZING_PLAN.md` (process section), `helpers/StressTestRTP/LEAK_FUZZING.md` and `docs/dev/INDEX.md`.

**Out of scope**
- Any code change (Python, Java, JS, compose files).
- Rewriting ADR-102's decision. It gets a link only, no change to what it decided.
- Implementing coverage (step 2) or later steps.

### User Stories
- As the maintainer, I want one Accepted record of what the fuzzer is for and what it shall not do, so each later step's proposal can be checked against it.
- As a contributor, I want to know which fuzzing tier a new target belongs to (Jazzer vs live server) and what a PASS/FAIL means.

### Functional Requirements
- The ADR follows `docs/adr/ADR-TEMPLATE.md`: Context, Decision, Alternatives Considered, Consequences, References. It also has a **Related ADRs** line (ADR-102, ADR-103, ADR-094, ADR-080, ADR-091, ADR-008/012 for S-002 counts).
- The decision text uses `shall` / `shall not` and has no process shorthand (no "Stage 2" wording in the normative part; the roadmap is stated by capability).
- Every claim about shipped behaviour matches `scripts/bench_fuzz.py` as it stands now (e.g. `--finding-n/-k/-shrink`, `control-findings.json`, `verdict.json` `confirmed_by`).
- UTF-8, no BOM, LF, no mojibake.

# ADR Content

### Proposed ADR-111 outline: Live-Server Behaviour Fuzzing: Plugin-Agnostic Oracles, Reproduced Findings and Coverage Feedback

**Status:** Accepted (date of writing). The coverage feedback, fast fault mode, new oracles and action vocabulary are accepted direction, each needing a D-005 proposal before implementation.

#### Context
- ADR-102 covers stateless parsers in-process (Jazzer). State, lifecycle and timing bugs (quit during a pending teleport, reload mid-episode, chunk-ticket growth) only show on a real server.
- `bench_fuzz.py` started as a leak hunter. Its generator, corpus, search loop and shrinker are generic, but the detectors were only logged, so the verdict ignored them.
- Limits: about 2-2.5 min per episode with `--reps 3`, a small action vocabulary, and no code-level feedback ("plugin replies stand in for coverage").

#### Decision
1. **Two fuzzing tiers.**
   - Tier P, in-process (ADR-102): byte and wire parsers.
   - Tier L, live server (this ADR): player behaviour against one plugin per server restart.
   - A target goes in the cheapest tier that can show the bug.
   - A middle tier (Jazzer over the `rtp-core` command pipeline on a fake platform) is noted, not adopted.
2. **Tool invariants.**
   - Plugin-agnostic: the only per-target inputs are the command and, optionally, package prefixes (`--plugin-packages`).
   - The Python side is stdlib-only and reads only JSON from the harness.
   - Seeded and reproducible.
   - Each target restarts with only its own jar.
   - The `direct` control comes from the same run and shall never be reused across runs.
3. **Oracle model.**
   - A finding oracle (`begin`/`observe`) returns findings with a stable signature: class + owning plugin + root exception type + first frame in that plugin, digits dropped.
   - The leak oracle keeps its own confirmation path (checkpoints, fit, ladder).
   - Attribution comes from stack-frame packages, with no fallback to the target. The target matches only on an exact label / `--names` / `--jars` entry.
   - Server stalls are attributed to `Server`.
4. **Procedure.**
   - search -> keep (key sources: `(action, reply)` pairs, count extremes, finding signatures, and later coverage probes) -> control subtraction -> replay k of n (one run each, shared replay budget) -> ddmin shrink -> verdict.
   - A non-leak finding shall not need amplification (no ladder).
5. **Verdict semantics.**
   - FAIL: a confirmed leak or a reproduced target finding.
   - INCONCLUSIVE: a signal that didn't confirm.
   - PASS: nothing found within budget. It is never proof of absence, and once coverage exists it shall state how much of the target's code was reached.
   - `verdict.json` lists signatures and `confirmed_by`.
6. **Coverage feedback (direction).**
   - The JaCoCo agent (staged by `extractJacocoAgent`, as in ADR-103 / `docs-compose.coverage.yml`) attaches to the bench server with `includes=` limited to the target's packages.
   - A StressTestRTP command `/rtpstress coverage <label>` reads and resets execution data through the agent runtime API in the same JVM, and writes per-class probe JSON into the run folder.
   - Python feeds new probes to the corpus and to mutation weighting.
   - Coverage is reported as probes reached. Exact branch counts stay with `jacocoServerReport` offline.
   - Probes are booleans, so there are no hit counts.
7. **Fast fault mode, new oracles, vocabulary (direction).**
   - A reps=1 mode with no leak checkpoints; the leak oracle runs only on episodes that reached new coverage or a new signature.
   - New oracles tied to the safety rules: S-004 dropped teleport, S-001 unsafe landing, S-002 ticket growth, tick-time excess over the control, thread/task growth.
   - A larger action vocabulary (plugin.yml / tab completion / GUI / chat), prioritised by coverage gained.
8. **Gates and outputs.**
   - Runs are opt-in and local, never part of `gradlew test` or CI (consistent with ADR-094).
   - Every oracle needs a planted-bug test against the fake server in `scripts/tests/test_bench_fuzz.py`.
   - A confirmed finding in LeafRTP goes to `POTENTIAL_BUGS.md`, or to the Linear tracker with `source:bench`.
   - Claims about other plugins follow ADR-080's publication and provenance rules.

#### Alternatives Considered
| Alternative | Why rejected |
|---|---|
| Extend Jazzer only | Can't reach real-server races and lifecycle bugs |
| Run bench_fuzz in-process | Loses the real Paper scheduler and client timing that the bugs depend on |
| Python reads the agent's tcpserver `.exec` | Needs a hand-written binary parser in stdlib Python |
| `dumponexit` per restart | One coverage sample per target, so it can't guide the search |
| Custom bytecode instrumentation for hit counts | Large cost for little gain at minutes per episode |
| Plugin-specific oracles | Breaks the fairness and agnosticism goals |

#### Consequences
- **Positive:**
  - A crash in the target fails the run.
  - Findings dedupe.
  - PASS gains a meaning once coverage exists.
  - One procedure covers leaks and faults.
- **Negative:**
  - StressTestRTP gains a JaCoCo-core (exec reader) dependency once coverage lands.
  - Bench launch flags change.
  - Episodes stay slow until the fault mode exists.
  - The permission oracle stays near-dead under `--grant '*'`.

#### References
`scripts/bench_fuzz.py`, `scripts/tests/test_bench_fuzz.py`, `helpers/StressTestRTP/LEAK_FUZZING.md`, `docs/dev/BEHAVIOUR_FUZZING_PLAN.md`, `devstack/docker-compose.coverage.yml`, `build.gradle` (`extractJacocoAgent`), RTP-28.

(Note: the reference above shall read `devstack/docker-compose.coverage.yml`.)

# Technical Design

### Current Implementation
- `docs/adr/README.md` lists ADRs up to ADR-110. ADR-096 is an unused gap that shall not be reused, so the next number is **ADR-111**.
- ADR-102 (Accepted) covers Jazzer for `AnvilReader` / `RespProtocol` and the two-tier CI model, and has no link to the live-server fuzzer.
- ADR-103 and `devstack/docker-compose.coverage.yml` already attach `jacocoagent.jar` (`jmx=true`, `dumponexit`), staged by the `extractJacocoAgent` task in `build.gradle`. The coverage direction reuses this.
- `docs/dev/BEHAVIOUR_FUZZING_PLAN.md` § Process says the later stages need "an ADR next to ADR-094". That sentence becomes a link to ADR-111.

### Key Decisions (confirmed with user)
- **New ADR-111**, not an amendment of ADR-102. ADR-102 gets only a *Related ADRs* link.
- **Coverage channel:** StressTestRTP `/rtpstress coverage <label>` reads and resets JaCoCo in the same JVM and writes JSON. Python stays stdlib-only.
- **Status:** Accepted for what ships now, with the roadmap as accepted direction behind per-step D-005 proposals.

### File Structure
- **Add** `docs/adr/ADR-111-live-server-behaviour-fuzzing.md`.
- **Modify:**
  - `docs/adr/README.md`: index row after ADR-110, with the short parenthetical summary used by other rows.
  - `docs/adr/ADR-102-coverage-guided-fuzz-testing-and-parser-input-hardening.md`: add ADR-111 to *Related ADRs*.
  - `docs/dev/BEHAVIOUR_FUZZING_PLAN.md`: header links ADR-111; the § Process line points to it.
  - `helpers/StressTestRTP/LEAK_FUZZING.md`: one-line governing-ADR link near the top.
  - `docs/dev/INDEX.md`: add ADR-111 next to the existing fuzzing entry.

### Risks
- The ADR could drift from the code. Mitigation: check each shipped-behaviour claim against `bench_fuzz.py` symbols while writing.
- The ADR could pick up process shorthand. Mitigation: state the direction by capability, and keep stage numbering in the plan doc only.
- `Accepted` covering unbuilt work could be misread. Mitigation: an explicit "accepted direction, each behind D-005" sentence in the Status and Decision §6-7.

# Testing

### Validation Approach
This change touches docs only, so no Gradle build or test run is needed (per *Build & Verification Gates*).

### Key Scenarios
- Every relative link in ADR-111 and in the edited files resolves to an existing file. Check with a path lookup for each target.
- Flags and file names cited in ADR-111 (`--finding-n`, `--finding-k`, `--finding-shrink`, `--plugin-packages`, `control-findings.json`, `finding-replays.jsonl`, `confirmed_by`) exist in `scripts/bench_fuzz.py`. Check with a search.
- The `docs/adr/README.md` table still has well-formed rows, and ADR-111 sits after ADR-110.

### Edge Cases
- Encoding scan over all touched files: no BOM, no CRLF, no `â€` / `Ã` / `Â` markers.
- No `Stage N` / `Step N` / `*_PLAN.md` wording in ADR-111's normative Decision text. It may appear only in References.

# Delivery Steps

### ✓ Step 1: Write ADR-111 for live-server behaviour fuzzing
`docs/adr/ADR-111-live-server-behaviour-fuzzing.md` exists, is Accepted and records the fuzzer's tiers, invariants, oracle model, procedure, verdict meaning and roadmap direction.

- Use the `ADR-TEMPLATE.md` structure plus a *Related ADRs* line (ADR-102, ADR-103, ADR-094, ADR-080, ADR-091, ADR-008, ADR-012).
- Context:
  - ADR-102 covers parsers only.
  - Why live-server state, lifecycle and timing bugs need a separate tier.
  - The speed, vocabulary and no-coverage limits.
- Decision sections 1-8:
  - two tiers;
  - tool invariants (plugin-agnostic, stdlib Python, same-run `direct` control);
  - oracle and signature model;
  - procedure (keep -> control subtraction -> replay k of n -> ddmin -> verdict, no ladder for faults);
  - FAIL / INCONCLUSIVE / PASS semantics;
  - coverage via `/rtpstress coverage` and the JaCoCo runtime API with JSON output, reusing `extractJacocoAgent` and the ADR-103 agent setup;
  - fast fault mode, S-001/S-002/S-004 oracles and vocabulary as accepted direction behind D-005;
  - opt-in local gates, planted-bug tests, and the finding-routing rules (`POTENTIAL_BUGS.md` / Linear `source:bench`, ADR-080 publication rules).
- Alternatives table: Jazzer-only, in-process bench_fuzz, tcpserver `.exec` parsing, `dumponexit`, custom hit-count instrumentation, plugin-specific oracles.
- Consequences and References.
- Check every flag and file name against `scripts/bench_fuzz.py`.
- Use `shall` phrasing and no process shorthand in the Decision text.

### ✓ Step 2: Cross-link ADR-111 from the index and fuzzing docs
Every existing fuzzing entry point leads to ADR-111, and the ADR catalog lists it.

- `docs/adr/README.md`: add the ADR-111 row after ADR-110, with a one-line parenthetical summary and status `Accepted (date)`.
- ADR-102: add ADR-111 to *Related ADRs* only; the decision text stays unchanged.
- `docs/dev/BEHAVIOUR_FUZZING_PLAN.md`:
  - link ADR-111 in the header;
  - replace the "needs an ADR next to ADR-094" sentence with a link stating the coverage channel decided there.
- `helpers/StressTestRTP/LEAK_FUZZING.md`: one-line governing-ADR link.
- `docs/dev/INDEX.md`: add ADR-111 beside the behaviour-fuzzing entry.
- Verify:
  - all relative links resolve;
  - an encoding scan (BOM, CRLF, mojibake markers) over every touched file comes back clean;
  - no Gradle run, since the change is docs only.