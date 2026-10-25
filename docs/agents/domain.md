# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

This repo is **single-context** but keeps its domain docs under `architecture/` rather than the default `CONTEXT.md` + `docs/adr/`. Treat the paths below as the equivalents.

## Before exploring, read these

- **`architecture/glossary.md`**: the domain glossary (plays the role of `CONTEXT.md`).
- **`architecture/decisions/`**: ADRs (`ADR-NNN-<slug>.md`). Read the ones that touch the area you're about to work in.
- **`architecture/README.md`**, `architecture/use-cases/` and `architecture/c4/` for broader context when the task needs it.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) adds to them when terms or decisions actually get resolved.

## File structure

```
/
├── architecture/
│   ├── glossary.md
│   ├── decisions/
│   │   ├── ADR-001-microservices-maven-monorepo.md
│   │   └── ADR-002-rest-plus-kafka-with-outbox.md
│   ├── use-cases/
│   └── c4/
└── specs/
```

New ADRs go in `architecture/decisions/` following the existing `ADR-NNN-<slug>.md` numbering. New glossary terms go in `architecture/glossary.md`.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `architecture/glossary.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-003 (orchestrated saga), but worth reopening because…_
