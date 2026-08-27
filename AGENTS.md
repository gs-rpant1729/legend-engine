# AGENTS.md

Instructions for AI coding agents working in this repository.

## Primary reference

See `CLAUDE.md` at the repo root for build/run commands, test commands, architecture
overview, module taxonomy, and coding conventions. Those instructions apply to all
agents, not just Claude — read it first.

## Plans

When asked to produce a plan (implementation plan, refactor plan, migration plan,
PR-breakdown plan, etc.), save it as a markdown file under `docs/agent/plans/` instead
of only presenting it inline. Use a short kebab-case filename that describes the work,
e.g. `docs/agent/plans/relation-mapping-pr-split.md`.

- Update the plan file in place as the plan changes or as steps complete; don't create
  a new file per revision.
- These files are working documents for agent-assisted development, not end-user
  documentation — keep them out of `docs/engineering/` and other user-facing doc trees.

## Commits

Do not add a `Co-Authored-By` trailer (or any other AI-attribution line) to commit
messages in this repository. FINOS Legend Engine requires all contributions to be
covered by its CLA, which is tied to the human committer/signer; an AI co-author
trailer breaks that attribution and can fail CLA checks. Author commits as the human
user only.
