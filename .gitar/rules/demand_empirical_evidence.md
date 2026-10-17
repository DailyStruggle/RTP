# Gitar Rule: Demand Empirical Evidence on Critical Paths

When a pull request is opened, updated, or marked ready for review:

## 1. Critical Blast Radius Subsystem Detection
Check if the pull request modifies files in any of the following critical subsystems:
- **Execution Pipelines:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tasks/teleport/`
- **Resource Lifecycle:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tasks/MemoryTracker.java`
- **Regional Threading:** `platforms/rtp-folia/`
- **Region Prefiltering:** `api/anvil-api/`

---

## 2. Evidence Verification Gate
If a critical subsystem file has a behavioral change (comment-, formatting-, and import-only edits do not trigger this gate), and the stated claim is thread safety, leak freedom, or performance:
- Require only the evidence that matches the touched subsystem: leak fuzzing for `MemoryTracker`/teleport tasks, devstack for `rtp-folia`/scheduler changes, off-tick and bounded-memory tests for `anvil-api` (see `.gitar/review/evidence.md`).
- Verify tests against the changed lines (would a wrong version fail?), not aggregate coverage.
- Inspect the pull request description, commit messages, and attached CI artifacts for empirical validation proof:
  1. **Leak Fuzzing Attestation (ADR-111):**
     - Summary of a minimum 24-episode fuzzing run using `scripts/bench_fuzz.py`.
     - Affirmation of `retained+0` player sessions post-GC across all client disconnect scenarios.
     - Affirmation of zero `ServerLogOracle` exceptions.
  2. **Concurrency / Regional Ownership Verification:**
     - Traceable unit tests asserting regional thread ownership (`Bukkit.isOwnedByCurrentRegion`).
     - Devstack acceptance test output (`devstack/run-acceptance.ps1 -Scenario rtptest`) demonstrating clean multi-platform execution without thread lockups.

---

## 3. Automated Enforcement Action
If empirical proof is missing or incomplete:
- Apply label: `evidence-required`
- Report it as blocker B-EVIDENCE with the missing artifact as the clearing condition (no percentage verdicts).
- Post the following required evidence checklist as a review comment:

```markdown
### 🛑 Empirical Evidence Required (Critical Blast Radius)

This pull request touches subsystems governing concurrent execution, regional threading, or memory lifecycles. In accordance with repository policy (`.gitar/review/evidence.md`), theoretical safety assurances are not accepted. Empirical proof is mandatory before this pull request can proceed to review.

Please run the verification tooling and update the pull request description with the results:

- [ ] **24-Episode Leak Fuzzing:** Execute `python scripts/bench_fuzz.py --mode leak --episodes 24 --reps 3` (see `helpers/StressTestRTP/LEAK_FUZZING.md`).
- [ ] **Zero Retained Sessions:** Verify `retained+0` player sessions post-GC across all disconnect episodes.
- [ ] **Clean Log Oracle:** Verify zero `ServerLogOracle` exceptions or unhandled pipeline errors.
- [ ] **Devstack Regional Verification:** Execute `.\devstack\run-acceptance.ps1 -Scenario rtptest` and verify all platform backends report passing accessors.
```
