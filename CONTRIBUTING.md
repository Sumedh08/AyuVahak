# Contributing to AyuVahak

Thank you for helping improve AyuVahak. Read [AGENTS.md](AGENTS.md), the [contributor guide](docs/development/contributor-guide.md), the [architecture index](docs/architecture/README.md), and the specification for the area you change before editing code.

## Development environment

- Java 25, using the repository Maven Wrapper.
- Node.js 24.21.x for the React/TypeScript client (`ui/.nvmrc`).
- Docker Compose v2 and a Docker-compatible local runtime for PostgreSQL/PostGIS and the integration-test fixtures.
- PowerShell 7 or Windows PowerShell for the supported local launcher and its tests.

Run backend verification from the repository root:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Run frontend checks from `ui/`:

```powershell
npm ci
npm run typecheck
npm run lint
npm test
npm run build
```

Use synthetic fixtures and local mocks only. Never use a public or shared healthcare endpoint as a test target. Do not send real patient or operational data to an external model provider. External provider tests use local mocks; provider use in a deployment is opt-in and disabled by default.

## Change expectations

- Keep business state in its owning backend context. The browser is a presentation client and cannot replace server authorization.
- Preserve the distinction between Referral, Transfer, and Transport; between Capacity evidence and readiness; and between facts, policy results, and advisory output.
- Add or update tests with behavior changes. Use PostgreSQL-backed tests where transaction, locking, migration, or recovery semantics matter.
- Update acceptance and operational documentation when a gate, interface, or operator action changes.
- Keep credentials, `.env`, local state, generated databases, build caches, logs, and `.scratch/` out of commits.
- Do not claim a certification, release property, capacity level, or production readiness without matching evidence.

Phase planning is kept locally under `.scratch/`; those planning files are deliberately not part of public releases. Review the applicable phase authorization before starting work beyond the current approved scope.

## Licensing

The repository owner has not designated a project license. Until that decision is made, the repository does not grant reuse rights. Contributions do not resolve or imply that missing license decision.
