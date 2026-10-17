# Verdict Format and Large-PR Triage

Canonical procedure: `docs/dev/PR_AUDIT_PROTOCOL.md`. Verification gates: `docs/dev/VERIFICATION_GATES.md`. Do not paraphrase them in review comments; cite the section.

## Verdict

- Report exactly one of `BLOCKED` (>= 1 open blocker) or `CLEAR` (0 open blockers), followed by `blockers open: N | advisories: M | not audited: <list>`.
- Never report a completion percentage ("90% done"). A PR is not partially clear.
- A blocker states: rule ID, `path:line`, evidence, and a clearing condition. A finding missing any of these is an advisory.
- Blocker classes are B1-B7 in `PR_AUDIT_PROTOCOL.md`. Anything else (style, naming, "could be simplified", speculative risk with no reproducing path) is an advisory and never blocks.

## Author experience (stable criteria)

Canonical: `docs/dev/CONTRIBUTOR_REVIEW_EXPERIENCE.md`.

- First review posts the full blocker ledger (`BLK-n`). Later reviews of unchanged lines add no blockers; a late find is `missed-earlier` and an advisory unless it is B1 or a newly observable B2.
- Push N may add blockers only on lines it changed. Meeting a clearing condition closes the blocker; never raise the bar.
- Report progress as `BLK-1 cleared, BLK-2 open`, not a percentage.
- Comments address the code, give the reason or rule link, and include a concrete suggestion or command. Tone, praise, advisory volume and nit handling follow Gitar's own configuration; the human defaults in the canonical doc do not override it.

## Large PRs (net additions far exceeding removals)

1. Stratify changed files (generated, docs, tests, production, build/config) and report line counts per stratum.
2. Audit production code in risk order using `.gitar/config/risk.md`. List what was not reached.
3. Unaudited Critical-blast-radius files are an open blocker (`B-COVERAGE`); unaudited lower-risk files are not.
4. Run mechanical checks first (build, targeted tests, pre-commit hook) and report failures as B2.
5. Check tests against the changed lines, not aggregate coverage.
6. Suggest decomposition only as an advisory, unless it is the only way to clear `B-COVERAGE`.
