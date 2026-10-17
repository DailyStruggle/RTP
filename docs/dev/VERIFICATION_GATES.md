# Verification Gates and Evidence

Canonical detail for the *Verifiable Correctness Gates* summarized in [`.junie/AGENTS.md`](../../.junie/AGENTS.md). Applies to every change, human or LLM authored, and targets regression risk from less-experienced contributors. Correctness takes priority over speed.

Gates 1-4 are never skipped for code changes. They bound the proportional-verification rule in [`TESTING_GUIDE.md`](TESTING_GUIDE.md): scope may shrink, these gates do not. Items without a reference are project practice, not research findings.

## Gates

1. **Reproduce before fixing.** A bug fix starts with a test that fails on the old code and passes on the new code. Record both results in `### Verification`. (Project practice.)
2. **Mechanical checks over discipline.** Rules that matter shall fail the build: compiler warnings as errors where enabled, static analysis in CI, architecture tests for S-00x and module boundaries (e.g., no `org.bukkit.*` in `rtp-core`/`rtp-api`, no sync `getChunkAt` on main-thread paths). Prefer adding such a check over adding a prose rule. [R3][R4]
3. **Errors are handled, not ignored.** Check return values; never catch-and-swallow; each error path has a test or an explicit reason it cannot. [R5]
4. **Independent review before merge.** Every change gets a reviewer who did not write it (for LLM output: a human who reads the diff, not the summary). Reviewers check tests and error paths, not only the happy path. [R1]
5. **Assess test quality, not only coverage.** Coverage is a smell detector, never a target. For changed lines ask "would a wrong version of this line fail a test?" (mutation-style check; run a mutation tool on the diff for safety-critical classes when available). [R2][R6]
6. **Small, decomposed changes.** One behavioral change per commit/PR; refactors separate from behavior changes. (Project practice; no cited threshold.)
7. **Contracts at boundaries.** Public and cross-module methods state preconditions (`Objects.requireNonNull`, argument checks) and the invariant they preserve in a one-line comment. Assertions are encouraged; no per-function quota. [R7]
8. **No unverified claims.** Do not report "fixed", "passes", or "no regression" without the command and result that shows it. State what was *not* verified.
9. **Regression memory.** Each fixed defect keeps its reproducing test permanently, named with the REQ-* or issue ID. Never delete or weaken a failing test to pass a gate.

10. **Feature evidence.** A new feature, or a replacement of an existing mechanism, ships with evidence of the claim that justifies it, committed or linked from the PR. Passing unit tests show correctness, not that the feature is worth its cost. (Project practice; see the table below.)

### Feature evidence by change type

The PR states the claim (e.g., "faster", "fewer retries", "clearer menu") and attaches the matching artifact. Where the artifact cannot be produced, the PR says why under `not verified` (gate 8).

| Change type | Required evidence |
|-------------|-------------------|
| Algorithm or shape replacing another (e.g., dual-layer shapes replacing the Archimedean spiral) | Differential benchmark against the old implementation on the same seeds/worlds: metric (e.g., chunk reads, attempts to a valid location, latency), sample size, spread (not only a mean), command and environment. Distribution-quality check if placement uniformity is a requirement. The old path stays runnable until the comparison is recorded. |
| Performance claim | Before/after numbers from the same harness and machine, with run-to-run variance. A claim without variance is advisory only. |
| UX change (menus, books, chat messages, GUI) | Before/after screenshots or recorded output, including the narrow-contrast cases for books (see `ADDON_MENUS.md`). |
| New command or config key | Example invocation with real output, plus `messages.yml`/locale parity result. |
| Platform adapter or proxy behavior | Devstack acceptance run output per `TESTING_GUIDE.md`. |
| Safety-relevant change (S-001..S-007) | Test that fails if the safeguard is removed. |

## Evidence & References

Verified from search results in the authoring session; observational studies show association, not causation.

- **[R1]** McIntosh, Kamei, Adams, Hassan. "The Impact of Code Review Coverage and Code Review Participation on Software Quality." MSR 2014, pp. 192-201, doi:10.1145/2597073.2597076. Low review coverage and participation associated with more post-release defects (Qt, VTK, ITK). Observational.
- **[R2]** Petrovic, Ivankovic. "State of Mutation Testing at Google." ICSE-SEIP 2018. Mutants surfaced in code review on changed lines only; selective, diff-based surfacing was needed to keep it actionable.
- **[R3]** Sadowski et al. "Tricorder: Building a Program Analysis Ecosystem." ICSE 2015; and "Lessons from Building Static Analysis Tools at Google." CACM 61(4), 2018. Industrial experience; analyses must be low in false positives and integrated into the workflow to be acted on.
- **[R4]** Same sources as R3. Industrial experience, not a controlled experiment.
- **[R5]** Yuan et al. "Simple Testing Can Prevent Most Critical Failures." OSDI 2014. 92% of catastrophic failures in the studied distributed systems came from incorrect handling of non-fatal errors. Observational, distributed data systems only.
- **[R6]** Inozemtseva, Holmes. "Coverage Is Not Strongly Correlated with Test Suite Effectiveness." ICSE 2014. Correlation drops when suite size is controlled; do not use coverage as a target. (Contested: Gopinath, Jensen, Groce, ICSE 2014, report a stronger relationship.)
- **[R7]** Casalnuovo et al. "Assert Use in GitHub Projects." ICSE 2015, and the authors' replication, whose results largely reversed the original defect finding. Assertion density as a defect predictor is unsettled.

Not adopted because no supporting evidence was found: a fixed function-length cap, and the NASA/JPL "Power of Ten" rules as a package (Holzmann, IEEE Computer 39(6), 2006, is experience-based).
