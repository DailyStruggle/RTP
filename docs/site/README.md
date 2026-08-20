# `docs/site/` - narrative / non-functional pages

Source content for the **non-functional** pages of the generated documentation site
(home / landing, "why", "intended usage", "what not to do", and similar narrative
material). These are the pages a static-site generator (MkDocs Material or similar)
renders around the reference documentation.

## Why this folder exists

The user-facing reference pages historically lived in the detached GitHub Wiki repo
(`<repo>.wiki.git`, cloned into the top-level `wiki/` working copy). That wiki repo is
retired: it is not part of the main repository's history, is awkward to reach from
pipeline jobs, and its content now lives under `docs/` (operator reference in
`docs/admin/`, narrative pages here).

This folder is the tracked home, inside the main repo, for the narrative pages that do
not describe a specific config knob or command. Keeping them here means:

- they go through the same pull-request / review flow as code,
- pipeline jobs that check out the main repo already have them (no second clone, no
  separate credentials),
- a docs generator can build them into the public site with the rest of `docs/`.

## What belongs here

- Landing / home page (`index.md`).
- Motivation and background (`why.md`).
- The mental model and recommended workflow (`intended-usage.md`).
- Anti-patterns and cautionary pages (`what-not-to-do.md`).

## What does NOT belong here

- Per-knob configuration reference (core config, regions, worlds, ...) - that lives in
  `docs/admin/configuration/`.
- Engineering docs (`dev/`), decisions (`adr/`), architecture slices (`architecture/`),
  and operator runbooks (`admin/`) - those already have homes under `docs/`.

## Conventions

- UTF-8, no BOM, LF line endings; ASCII punctuation (no em/en dashes).
- MkDocs Material admonition syntax (`!!! note`, `!!! warning`, `!!! tip`) and content
  tabs (`=== "..."`) are used where helpful, matching the pages already authored in that
  style.
- Links between pages in this folder are relative (e.g. `what-not-to-do.md`), as are
  links into the reference set (e.g. `../admin/configuration/CORE_CONFIG.md`). Do not
  link to the retired GitHub Wiki.
- Absolute site URLs (`https://dailystruggle.github.io/RTP/<path>/`) are for copy that is
  read *outside* the repo: `README.md`, `SUPPORT.md`, `FRONT_PAGE*`, issue templates,
  listing bodies. Inside `docs/`, keep links relative so they also resolve on GitHub and
  in the jar-bundled copy under `plugins/RTP/docs/`.
