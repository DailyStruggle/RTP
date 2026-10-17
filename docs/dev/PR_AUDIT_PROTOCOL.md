# PR Audit Protocol (agentic and human reviewers)

Canonical procedure for automated reviewers (e.g., Gitar) and humans auditing a pull request. Goal: block on the right things, with evidence, instead of reporting a completion percentage. Extends [`VERIFICATION_GATES.md`](VERIFICATION_GATES.md) (gate 4) and [`TESTING_GUIDE.md`](TESTING_GUIDE.md). Items without a reference are project practice, not research findings.

## 1. Verdict vocabulary

1. A verdict is **BLOCKED** (>= 1 open blocker) or **CLEAR** (0 open blockers). Percent-complete figures ("90% done") shall not be used.
2. Report counts: `blockers open: N | advisories: M | not audited: <list>`.
3. Every blocker states: rule ID, `path:line`, evidence (command output or code excerpt), and the **clearing condition** (the exact check or change that resolves it). A finding without a clearing condition is an advisory.

## 2. What may block

A finding blocks only if it falls in one of these classes:

| Class | Example | Evidence required |
|-------|---------|-------------------|
| B1 Safety | S-001..S-007 violation (sync chunk load on main thread, swallowed teleport failure) | Code path from entry point to violation |
| B2 Mechanical | Build, test, architecture check, or pre-commit gate fails | Command and failing output |
| B3 Unverified behavior | Changed behavior where no test would fail if the line were wrong (gate 5) | Changed line and the absent assertion |
| B4 Unhandled error | Ignored return value or catch-and-swallow (gate 3) | `path:line` of the swallow |
| B5 Contract drift | Contradicts an Accepted ADR or REQ-*; public API/SPI change without SemVer/CHANGELOG entry | ADR/REQ ID and the contradicting line |
| B6 Traceability | New REQ-* behavior with no `TRACEABILITY.md` row; locale key without parity | Missing row/key |
| B7 Feature evidence | New feature or mechanism replacement with no artifact for its stated claim (gate 10: differential benchmark, screenshots, command output) | The claim in the PR and the absent artifact |

Style, naming, naming-of-tests, performance guesses, and "could be simplified" are **advisories** and never block. Speculative findings (no reproducing path) are advisories labelled `unconfirmed`.

## 3. Large-PR triage

For a PR with large net additions (e.g., > 10:1 added:removed lines), the auditor shall:

1. **Stratify** changed files by type: generated/vendored, docs, tests, production code, build/config. Report line counts per stratum. Exclude generated files from review but verify they regenerate from source.
2. **Rank production code by risk**, highest first: safety-critical paths (threading, chunk I/O, teleport), public API/SPI, error handling, config/locale parsing, everything else.
3. **Audit in risk order** and state what was not reached. Unaudited high-risk files are listed under `not audited` and count as an open blocker (`B-COVERAGE`) until audited; unaudited low-risk files do not.
4. **Check tests against changes, not totals.** For each new public class or behavior, find the test that would fail if it were wrong (gate 5). Aggregate coverage numbers are not evidence.
5. **Recommend decomposition** when a stratum cannot be audited with evidence (one behavioral change per PR, gate 6). A split request is an advisory unless it prevents B-COVERAGE from clearing.
6. **Run mechanical checks first**: build, targeted tests per [`TESTING_GUIDE.md`](TESTING_GUIDE.md), architecture/lint checks, pre-commit hook. These produce B2 findings cheaply and objectively.

## 4. Author-supplied context (PR manifest)

The PR description should supply what an auditor needs; absent items are reported as `not provided`, not guessed:

- Governing ADR/REQ IDs.
- Verification commands run and results; what was not verified (gate 8).
- Files grouped by risk class (section 3.2) and generated files with their generator command.
- Bug fixes: the reproducing test and its before/after result (gate 1).
- New features: the claim and its evidence artifact per gate 10 (benchmark, screenshots, output).
- Known open items, each with a clearing condition.

## 5. Reporting template

```
Verdict: BLOCKED | CLEAR
Blockers open: N   Advisories: M   Not audited: <files/strata>
Strata: production X lines, tests Y, docs Z, generated G, config C
Blockers:
  1. [B3] path:line - evidence - clears when: <condition>
Advisories (non-blocking): ...
Verified by command: <command -> result>
Not verified: ...
```

## Evidence & References

Observational and industrial studies show association, not causation. Verified against search results in the authoring session; full texts were not read.

- **[A1]** McIntosh, Kamei, Adams, Hassan. MSR 2014, doi:10.1145/2597073.2597076. Low review coverage and participation associated with more post-release defects. Supports reviewing every change.
- **[A2]** Bosu, Greiler, Bird. "Characteristics of Useful Code Reviews: An Empirical Study at Microsoft." MSR 2015. Across 1.5M comments, the more files in a change, the lower the proportion of useful comments. Supports decomposition and risk-ordered review (3.2, 3.5).
- **[A3]** Bacchelli, Bird. "Expectations, Outcomes, and Challenges of Modern Code Review." ICSE 2013. Defect finding is the main motivation but reviews yield fewer defects than expected; change understanding is a central challenge. Supports requiring a manifest (section 4).
- **[A4]** Sadowski et al. "Tricorder." ICSE 2015. Findings are acted on when they are low in false positives and actionable; supports evidence plus a clearing condition (1.3, 2).
- **[A5]** Petrovic, Ivankovic. "State of Mutation Testing at Google." ICSE-SEIP 2018. Diff-scoped surfacing kept results actionable; supports checking tests against changed lines (3.4).

Not supported by evidence found: any threshold at which a PR becomes "too large", and any link between a completion percentage and defect risk. The 10:1 ratio in section 3 is a triage trigger, not a research finding.
