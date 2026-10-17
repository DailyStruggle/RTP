# Contributor Review Experience

Canonical rules for how reviewers (agentic, e.g. Gitar, and human) communicate with PR authors. Goal: authors respond to a review with effort and stay, rather than disengage. This governs tone, stability, and ordering of feedback. What may block is defined in [`PR_AUDIT_PROTOCOL.md`](PR_AUDIT_PROTOCOL.md). Items marked (Project practice) have no cited study.

Sections 2.1, 2.5 and 2.6 are defaults for human reviewers; an agentic reviewer's own configuration (e.g. Gitar's) takes precedence on tone, praise and volume. The blocker rules in section 1 and `PR_AUDIT_PROTOCOL.md` apply to all reviewers.

## 1. Stable criteria (no moving goalposts)

1. **Blocker ledger.** The first review of a PR posts the complete blocker list. Each blocker has an ID (`BLK-1`...), rule class, `path:line`, evidence, and clearing condition.
2. **Ledger is append-only for the same diff.** A later review of unchanged lines shall not add a blocker. If the reviewer missed it, it is posted as `missed-earlier` and is an advisory unless it is B1 (safety) or B2 (mechanical failure that was not observable before).
3. **New blockers on new changes only.** A push may add blockers only on lines that push changed, or on defects the push introduced.
4. **Clearing is binary.** When the stated clearing condition is met, the blocker closes. The reviewer shall not raise the bar (e.g., ask for more evidence than the clearing condition named).
5. **Rule changes do not apply retroactively to open PRs.** The review rules in force when the PR opened apply until merge, unless a safety rule (S-001..S-007) changed.
6. **Report progress as closed/open blockers** (`BLK-1 cleared, BLK-2 open`), never as a percentage. (Project practice.)

## 2. Comment form

1. **Lead with what is correct** when a change is substantially sound; state it specifically rather than as a bare "LGTM". (Project practice.)
2. **Address the code, not the author.** No "you", no sarcasm, no rhetorical questions. Write "this call can return null at `X:42`", not "you forgot".
3. **Explain why.** Every requested change carries its rationale or a link to the governing rule. Explanations are frequently missing from reviews and are an identified gap. [C4]
4. **Make it actionable.** Prefer a concrete suggestion (GitHub suggestion block, or exact command to run) over a description of a problem. Suggestions make feedback actionable for submitters. [C5]
5. **Separate severity labels:** `blocker`, `advisory`, `question`, `nit`. For human reviewers, hold nits until blockers are cleared. (Project practice.)
6. **Bound the volume.** As a human default, keep advisories to about 5 per review, highest value first, and summarize the rest as a count. Reviews on many-file changes yield a lower share of useful comments. [C2]
7. **One consolidated review per push**, not a stream of comments. Bots keep noise low; noise and unclear bot output are reported by maintainers and contributors as discouraging. [C3]
8. **Explain tool output.** A failing check names what it means and how to reproduce locally.

## 3. Newcomers and first PRs

1. Poor or discouraging feedback on a contribution is associated with contributors not returning; social barriers to a first contribution are documented. [C3][C6]
2. For first-time contributors, state expectations once (link `CONTRIBUTING.md`), classify the single most important change first, and offer to take over mechanical fixes (formatting, locale parity) where policy allows.
3. Evidence requirements (gate 10) are stated with the matching artifact and an example, not only as a demand.

## 4. Pushback self-check (reviewer, before posting)

Pushback is a blocked change experienced as unnecessary interpersonal conflict, and it is associated with frustration and developers abandoning projects. It is rare but costly when it happens. [C1] Before posting a blocker, verify:

- It is in B1-B7 with evidence and a clearing condition.
- It was not already visible in an earlier push (section 1.2).
- The comment follows section 2.2 and 2.3.
- The author can clear it in one push without guessing.

## 5. Response expectations

- State expected turnaround for the author-side and reviewer-side once per PR; do not escalate silence into a blocker. (Project practice.)
- A disagreement on a non-safety blocker is resolved by the maintainer citing the rule; the reviewer does not repeat the demand.

## 6. Measuring (optional, per release)

Track per PR: blockers opened vs. cleared, `missed-earlier` count, push rounds to CLEAR, and author return rate for first-time contributors. Egelman et al. found logged review metrics can flag likely pushback with high recall but low precision, so use them to prompt reviewer self-review, never to judge individuals. [C1]

## Evidence & References

Verified against search results in the authoring session; full texts were not read. Observational studies show association, not causation.

- **[C1]** Egelman, Murphy-Hill, Kammer, Hodges, Green, Jaspan, Lin. "Pushback in Code Review: Characterizing and Detecting Negative Interpersonal Interactions" (also titled "Predicting Developers' Negative Feelings about Code Review"). ICSE 2020, pp. 174-185. Survey of 1,317 developers at one company plus review logs; pushback is relatively rare, has negative repercussions, can lead to frustration, stress, and abandoning projects; metrics predict it with high recall, low precision.
- **[C2]** Bosu, Greiler, Bird. "Characteristics of Useful Code Reviews: An Empirical Study at Microsoft." MSR 2015. ~1.5M comments; usefulness associated with change characteristics such as the number of files.
- **[C3]** Wessel et al. "Quality gatekeepers: investigating the effects of code review bots on pull request activities." Empirical Software Engineering, 2022. Maintainers reported communication noise and newcomer dropout as unexpected bot effects; the paper cites Steinmacher et al. ICSE 2018 ("Almost there") on poor PR feedback discouraging further contributions.
- **[C4]** Widyasari et al. "Explaining Explanations: An Empirical Study of Explanations in Code Reviews." ACM TOSEM, 2025. Explanations are important but unevenly supplied (as summarized in the Awesome Code Reviews overview; the paper itself was not opened).
- **[C5]** "How Do Developers Use Code Suggestions in Pull Request Reviews?" arXiv:2502.04835, 2025. Suggestions make feedback more actionable; reviewers use them more for newcomers. Preprint, 46 projects.
- **[C6]** Steinmacher et al. "Social barriers faced by newcomers placing their first contribution in open source software projects." CSCW 2015, pp. 1379-1392. Cited via secondary sources; not opened.

Not supported by evidence found: that a fixed ledger or append-only blocker rule reduces dropout (section 1 is project practice derived from the pushback and unnecessary-delay findings), and the specific cap of 5 advisories.
