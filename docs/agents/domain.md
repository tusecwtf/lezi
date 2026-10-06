# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

**Layout:** single-context (one glossary + one ADR tree).

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — domain glossary only (no implementation detail)
- **`docs/adr/`** — ADRs that touch the area you're about to work in; start from [`docs/adr/README.md`](../adr/README.md) for status

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

```
/
├── CONTEXT.md
├── docs/
│   └── adr/
│       ├── README.md
│       ├── 0001-….md
│       └── …
└── …
```

There is no `CONTEXT-MAP.md` and no per-package `docs/adr/` tree in this repo.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0008 (fresh-current only) — but worth reopening because…_

## Where other docs fit

| Path | Role |
|------|------|
| [`docs/spec/`](../spec/) | Product behaviour after ship (not the glossary) |
| [`.scratch/`](../../.scratch/) | In-flight specs and tickets (issue tracker) |
| [`docs/design/`](../design/) | Intermediate design notes not yet folded into spec/ADR |
| [`docs/agents/`](./) | Skill configuration (this file, tracker, labels, PR) |
| `docs/reviews/`, `docs/research/` | Gitignored local drafts only — not source of truth |
