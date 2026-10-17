# Issue tracker: Local Markdown

AyuVahak planning issues and Wayfinder decision records live as Markdown under `.scratch/`. This repository has no configured remote and Phase 0 forbids external issue-tracker writes.

## Conventions

- One effort per directory: `.scratch/<effort>/`.
- The Wayfinder map is `.scratch/<effort>/map.md`.
- Each Wayfinder ticket is one file under `.scratch/<effort>/issues/NN-<slug>.md`.
- Ticket metadata uses `Type:` (`research`, `prototype`, `grilling`, `task`) and `Status:` (`open`, `claimed`, `resolved`).
- Blocking is recorded with `Blocked by: NN, NN`; a ticket is unblocked when every referenced ticket is resolved.
- Record resolution under `## Answer`, then add a named context pointer to the map's `Decisions so far`.
- Do not create GitHub/GitLab issues or use any external tracker during Phase 0. If a future phase changes trackers, update this file only after explicit project-owner direction.

## Phase-aware use

The Phase 0 restriction above is historical and applies only to Phase 0 work. For later phases, the active human-authorized master prompt and root `AGENTS.md` determine which local planning/specification workflows may run. Keep planning artifacts in `.scratch/`; do not create an external tracker item unless the human explicitly authorizes that exact external mutation. A specification-completeness marker never authorizes implementation; the implementation phase still requires its own explicit human authorization and acceptance gate.
