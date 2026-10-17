# Domain documentation

## Layout

AyuVahak is initially documented as one product domain. The root `GLOSSARY.md` is the canonical term list. The root `CONTEXT.md` records higher-level domain context and relationships that do not belong in the glossary; it contains no implementation details. System-wide architecture decisions live in `docs/adr/`. Product and architecture documents under `docs/` explain the product scope, actors, data ownership, bounded contexts, and workflows.

## Consumer rules

- Read `AGENTS.md`, `CONTEXT.md`, `GLOSSARY.md`, `docs/architecture/constitution.md`, the relevant architecture documents, and applicable `docs/adr/` decisions before later specification or implementation work.
- Use glossary terms consistently; do not treat standards, frameworks, vendors, or infrastructure as business bounded contexts.
- Keep system context distinct from short definitions and from implementation plans.
- Surface any conflict with an accepted ADR or project-owner working decision before changing direction.
- Treat frozen phase artifacts as authoritative for their phase. Create or change specifications, local tickets, or implementation only when the active master prompt and root `AGENTS.md` authorize that phase and workflow; do not use a Phase 0 restriction to override later authorized phase gates.

