# Local release-evidence helpers (P13-09 partial)

**Status: release eligibility is blocked.** The repository requires a generated
and schema/profile-validated SPDX 3.0 SBOM. This worktree has no locally
installed SPDX 3 generator/validator and no cached SPDX Maven artifact. Network
retrieval is not authorized for this task. The helpers below therefore never
mark a release eligible and do not emit or claim an SPDX SBOM.

This is deliberately a partial P13-09 contribution. It provides local,
synthetic-only seams for SemVer validation, provenance bindings, signed evidence
archives, signer pinning, tamper rejection, and contextual vulnerability
triage. It is not a complete release pipeline or a security attestation.

## Local requirements

- Python 3.12 or newer, standard library only.
- A local JDK providing `keytool` and `jarsigner` for signed archive commands.
- No network access, registry, external scanner, cloud signer, or publication
  service is used by these commands.

Run the tests with:

```powershell
python -m unittest discover -s tools/release_evidence -p 'test_*.py' -v
```

The signing tests create a synthetic self-signed key in a temporary directory
and remove it at test teardown. That identity is test-only and must never be
used for a release.

## Commands

Write provenance for local synthetic/build artifacts. Artifact labels are
archive-relative names; the generated record includes SHA-256 digests and
explicit source, builder, build type, and build parameters. It states that no
SLSA level or conformance is claimed.

```powershell
python tools/release_evidence/release_evidence.py provenance `
  --source-revision 0123456789abcdef0123456789abcdef01234567 `
  --builder-id local://SYN-BUILDER-001 `
  --build-type maven-package `
  --version 1.2.3-rc.1 `
  --parameter migrationRange=V1-V99 `
  --artifact ayuvahak.jar=target/ayuvahak-platform.jar `
  --output .scratch/release-evidence/provenance.json
```

Create a manifest with the current SPDX gate explicitly blocked:

```powershell
python tools/release_evidence/release_evidence.py manifest `
  --version 1.2.3-rc.1 `
  --source-revision 0123456789abcdef0123456789abcdef01234567 `
  --builder-id local://SYN-BUILDER-001 `
  --build-type maven-package `
  --artifact ayuvahak.jar=target/ayuvahak-platform.jar `
  --migration-range V1-V99 `
  --supported-upgrade-path '0.1.x to 1.2.3' `
  --output .scratch/release-evidence/manifest.json
```

The manifest always records `releaseEligibility.eligible=false` while SPDX 3.0
generation and schema/profile validation tooling is absent. A caller-supplied
`VALIDATED` string is not sufficient to clear the gate.

`sign-bundle` accepts an operator-selected local PKCS#12 signing identity and
reads its password only from a named environment variable. It signs a JAR
archive containing the exact supplied files. `verify-bundle` requires the
expected signer certificate SHA-256 pin and can enforce the expected content
entry set. Signature presence alone does not establish signer trust, build
trust, SBOM validity, or release eligibility.

Vulnerability input is local JSON with explicit severity, reachability,
known-exploited status, fix availability, and exposure. The classifier emits
review dispositions; it does not scan dependencies, decide exploitability, or
automatically accept a finding. A time-bounded exception must match the finding
and contain an owner, rationale, compensating controls, approver, and future
expiry. An exception does not erase the finding or independently authorize a
release.

## P13-09 handoff blocker

The frozen acceptance gate requires all of the following before this ticket can
pass:

1. A maintained, locally available SPDX 3.0 generator selected for the Maven
   artifact/dependency set.
2. Its exact invocation and version pinned without adding a publication path.
3. The selected SPDX 3.0 schema/profile validator and required schema assets
   available locally.
4. A reproducible fixture proving generated component and relationship
   coverage and validation, then a signed release pack whose manifest binds the
   SBOM digest to source/artifact provenance.

No local `spdx`, `spdx3`, `spdx-tools`, `cdxgen`, or `syft` executable was
available during this run, and no matching SPDX Maven artifact was present in
the local Maven repository. Do not substitute SPDX 2.x or CycloneDX, hand-author
a document and call it validated, or fetch assets until separately authorized.
Once an approved local generator/validator is available, extend the command
boundary and tests, validate the actual SBOM against its selected profile, and
then evaluate release eligibility.

## Safety boundary

All samples and tests use synthetic identifiers and local ephemeral keys. The
tool does not contact external systems, publish artifacts, scan external
targets, generate production signing identities, or claim DPG/SLSA/security
certification. No Phase 14 functionality belongs here.
