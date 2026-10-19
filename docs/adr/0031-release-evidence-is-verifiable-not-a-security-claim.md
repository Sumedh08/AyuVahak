---
status: accepted
date: 2026-10-20
---

# ADR 0031: Release evidence is verifiable, not a security claim

## Context

Phase 13 requires transparent component inventory and artifact provenance that adopters can verify. SBOM, signatures, provenance and vulnerability scans each answer different questions; none alone proves an artifact secure or a build trustworthy.

## Decision

Stable application releases use immutable SemVer 2.0.0 identifiers; pre-release versions identify candidates and `SNAPSHOT` builds are not production releases. The declared public product/API compatibility policy and migration support are documented; API URI major versions are independently governed. Each release links source revision, artifact digest, notes and supported upgrade path.

The initial release design uses one validated SPDX 3.0 SBOM per distributable artifact, artifact digests, machine-verifiable SLSA provenance and a signature or signed manifest with documented expected identity and verification steps. An implementation must verify generator and consumer compatibility before selecting tooling. No SLSA level, certification, or “secure because scanned” claim is made without evidence for the full claim. Vulnerabilities are prioritized using severity, exploit evidence (including CISA KEV as one input), reachability/exposure, fix availability and deployment context. Exceptions require a bounded owner, rationale, mitigation and review expiry. Digest pinning pairs with a stated base-image/runtime update cadence.

## Consequences

- Release consumers can inspect artifact identity and declared build inputs.
- SPDX is the single initial canonical SBOM, avoiding unsupported dual-format maintenance.
- Signing/provider/tool choice remains implementation-time and portable; Sigstore is a candidate only.
- Continuous vulnerability management and emergency update paths remain necessary after signing.

## Alternatives considered

- A custom SBOM was rejected in favor of a versioned open standard.
- Automatically requiring two SBOM formats was rejected absent consumer evidence.
- Claiming a SLSA level from the existence of CI was rejected.
- Blocking on every scanner result without context was rejected in favor of risk-based, auditable triage.

## References

- [Volume V production/security research](../research/volume-5-production-security-supply-chain-research.md)
- [Phase 13 specification](../specs/phase-13-production-hardening-dpg-readiness-spec.md)
