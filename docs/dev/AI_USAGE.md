# AI Usage in RTP Development

This is a writeup of how and why AI tooling is used on RTP. It's for two audiences: skeptics who want to know whether I'm using AI responsibly, and younger devs trying to figure out what AI is and isn't good for. I'm not selling anything. Where AI does poorly, that's on the page too.

---

## A. The "why"

My background is academic. The part that matters here isn't the credential, it's that I studied this in college and designed tensor processing units alongside peers - modeled hardware and statistical models for machine learning. So when I look at an LLM I'm not looking at magic. I'm looking at compression and retrieval over an n-dimensional table (one that could run a lot leaner than the way it's currently served), and I have a pretty good idea what it's doing and what it isn't.

LLMs are designed to sound like the people who produced them. The training corpus is human-written text and the objective is to produce text another human would find plausible. They talk like me only because I'm one of the humans in the pile. More accurately they talk like the aggregate of every author whose work landed in the training set, weighted by how the data was curated.

Plausible isn't the same as correct. When the model is asked something the training didn't cover, it doesn't know that. It falls back on the closest neighboring memory and produces something confident. That's basically what a human does while dreaming: yesterday's fragments restitched. We call them hallucinations for good reason.

Some models, including the one Junie runs on, are tuned to hold lower confidence and to ask before acting. That's what makes them usable for the kind of work I describe below. Without it you've got a confident stranger typing fast.

I brought AI into RTP for two reasons and neither of them is the reason it usually gets marketed on.

First, repetitive wrappers. RTP's V3 architecture is modular on purpose. A new shape, a new platform adapter, a new safety predicate - these all slot into seams I designed during V2. The interesting work happened when I built the seams. Writing the Nth thing that conforms to them is gruntwork, and gruntwork is exactly the kind of locally-specified problem an LLM can chew through without having to invent any design.

Second, knowledge gaps on specific APIs. I know RTP thoroughly. I don't know Fabric's intermediary mappings or Velocity's plugin SPI to the same depth. The system design for the Fabric and proxy frontiers is mine. The specific method signatures, the obf/unobf carrier split, the Velocity event class names - those aren't things I'd otherwise pick up in the timeframe I had.

The timeframe is a real part of this. I was in the middle of a personal move during V3 work and didn't have time to burn on extraneous gruntwork in a system I already know. So the honest answer isn't "AI is the future of programming". It's "I had a list of mechanical tasks and a finite week".

---

## B. The "how"

The ADR workflow and the documentation discipline in this repo (the `shall` phrasing in `REQUIREMENTS.md`, the separation of *what* from *how*, the per-subproject ADR sequences, the REQ-* traceability matrix) didn't come from AI. That came from my time working on human-safety-related systems, where auditors and post-incident reviewers expect a particular shape of paper trail.

Lucky for me, that same shape happens to be exactly what regulates an LLM well. The format forces explicit *what*, explicit *why*, explicit trade-offs, explicit consequences, and numbered so nothing gets quietly retconned. A model told "produce an ADR matching this template, here's the brainstorm, here are the trade-offs we identified" produces useful drafts. A model told "design the system" produces plausible noise.

When AI is left to prioritize on its own it picks badly. It treats whatever was mentioned most recently as the most important thing, regardless of whether it matters at the system level. The fix is what you'd do with a junior dev who just learned a new pattern and is now seeing it everywhere: somebody with the wider context window says "no, that's a footnote, not a chapter".

Refactoring only happens after a human design review and a written checklist. I halt sessions mid-stream more than half the time to fix the model's assumptions and redirect it. That's not me complaining about the tool, that's the intended workflow. The model is tuned to ask, I'm calibrated to interrupt. A session that runs end-to-end without me jumping in is a session I should reread carefully, because either the task was small or I missed a wrong turn. The checklist discipline in [`.junie/AGENTS.md`](../../.junie/AGENTS.md) ("Checklist-Based State Tracking") exists partly for this reason - it's how I make an interrupted session resumable from a verified state instead of from the model's summary of what it thinks it did.

Unit tests are a lower-risk place to let AI write. The inputs, the call, and the expected outputs are all locally specified. A failing test is the artifact that proves I understood the bug before I fixed it. And a bad test is loud - it fails, or it passes trivially in a way someone will notice. A bad refactor is quiet.

The V2 branch was written without AI, mostly because generative AI wasn't really a thing back then. That timing was lucky. The seams V3 plugs into (region pipeline, safety predicates, shape contributors, platform adapters) are mine, designed before I trusted an LLM to participate. AI gets used for wrappers around that human work or repeated code in the same shape. A new `Shape` that conforms to the existing `Shape` SPI is a good fit: the contract is fixed, the tests around it already exist, and the architecture-boundary tests (`RTPArchitectureTest`) fail loudly if a wrapper reaches across a module it shouldn't. AI accelerates the Nth instance of a pattern I already designed. It doesn't get to invent the pattern.

Two pieces of tooling in this repo are worth calling out because they look like human workflows and aren't. The locale TSV pipeline under `scripts/out/` is only used by AI. A human translator fluent in the target language can just open `lang/<locale>/<file>.yml` and edit it, and the TSV layer is unnecessary overhead. The pipeline exists because AI-based translation benefits from a single condensed table of every locale's translation of every key, comments included, with placeholders pre-masked. That's the shape of input an LLM can review without losing track. So the pipeline is tooling I built for AI specifically, with the simpler human path left alone.

The devstack at `platforms/rtp-proxy/devstack/` is the reverse case. I designed it to help me run integration tests and code reviews. AI didn't design it. AI wrote the Docker Compose YAML, the entrypoint shims, and the reset script after I told it the topology (1 Redis, 2 proxies, 2 lobbies, 3 platform-asymmetric backends).

What AI is *not* used for, as a rule:

- **Code review.** A reviewer has to hold the whole change in mind, decide what's missing, and be accountable. An LLM can't be accountable.
- **Automated git operations.** Ever. And never "optimizing" human-written logic. The git-safety prohibitions in [`.junie/AGENTS.md`](../../.junie/AGENTS.md) exist because that failure mode has already happened in this repo. It's not theoretical.
- **System design.** Design happens in human brainstorming sessions and AI formats the result. The other direction is how you end up with a codebase nobody understands.
- **Agentic troubleshooting.** AI gets used for investigation - reading the relevant files, summarizing what they do, listing where a bug might live - but it frequently misattributes the error source. It can advise. It does not jump to execution. Bug fixes are directed by humans and commanded explicitly.
- **Independent system docs.** This document is the example. Every paragraph is human-directed prose that an LLM then helped format. The substance is mine. If you see an AI-written ADR or requirement in this repo that drifts from that, file an issue. It's a defect.

That's the writeup. If you want to continue the conversation - push back on something here, ask why a specific thing is on the "not used for" list, or share a workflow of your own - open an issue or catch me on the subreddit.
