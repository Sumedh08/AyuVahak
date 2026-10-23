# Local release-evidence helpers (Phase 13)

These commands create and verify local release evidence. They do not publish a
release, contact a registry, submit a DPG application, or establish security,
SLSA, SPDX, or DPG certification. Use synthetic fixtures and local artifacts.

## Local requirements

- Python 3.12 or newer.
- A local JDK providing `keytool` and `jarsigner` for signing tests and bundles.
- The pinned cdxgen v13.4.0 Windows AMD64 executable at
  `.scratch/local-toolchain/spdx-3-acceptance/cdxgen-windows-amd64.exe` for the
  current Windows workflow. Its SHA-256 is
  `0b4a6ec411a70127d72e2f6fc45277f03fa64d4237a40325d0ff117634b76375`.
- The local SPDX validator environment installed from the pinned requirements
  in `requirements-spdx3.lock`.

The generator is obtained from the official cdxgen v13.4.0 release and remains
in ignored local tooling storage; it is not committed or published. SPDX
3.0.1 JSON Schema, SHACL model/context, and SPDX License List assets are vendored
under `spdx-assets/3.0.1/`. Their official source locations and SHA-256 values
are recorded in that directory's README. Validation uses only those local
assets and denies network/socket access.

Maven generation must have the repository's `.mvn/settings.xml`, contain no
server/proxy credentials, use the repository-selected mirror identity, and run
in offline runtime-dependency mode. cdxgen is constrained to the local Maven
wrapper and loopback hosts. Missing tools, incomplete dependency resolution,
unexpected execution/network trace, digest mismatch, or schema/model/license
validation errors fail closed.

Create the validator environment from the pinned lock using locally available
packages. This workflow does not require a public DHIS2, healthcare endpoint,
model service, or release registry.

Run helper tests with:

```powershell
python -m unittest discover -s tools/release_evidence -p 'test_*.py' -v
```

Signing tests create a synthetic self-signed key in a temporary directory and
remove it at test teardown. That identity is test-only and must never be used
for a release.

## Generate and validate the local SPDX SBOM

Build the JAR first, then run the pinned generator through the release-evidence
command. The application PURL is used only to bind the actual JAR digest to the
package representing the application. Unknown/ambiguous POM license names are
omitted rather than guessed.

```powershell
$python = '.scratch/local-toolchain/spdx3-validate-env/Scripts/python.exe'
& $python tools/release_evidence/release_evidence.py sbom `
  --generator .scratch/local-toolchain/spdx-3-acceptance/cdxgen-windows-amd64.exe `
  --project-root . `
  --artifact target/ayuvahak-platform-0.1.0.jar `
  --application-purl 'pkg:maven/org.ayuvahak/ayuvahak-platform@0.1.0?type=jar' `
  --maven-repository "$env:USERPROFILE/.m2/repository" `
  --validator-python $python `
  --java-home $env:JAVA_HOME `
  --output .scratch/release-evidence/ayuvahak.spdx.json `
  --validation-report .scratch/release-evidence/ayuvahak.spdx-validation.json
```

The validation report records SPDX 3.0.1 format, generator digest, application
JAR digest, official schema/model/context/license-list digests, package and
declared-license relationship counts, and local-only execution policy. The
vendored SPDX License List is version 3.29.0. The validator checks the JSON
Schema, SHACL model, and every emitted simple license identifier against that
list. The normalization exception for cdxgen's external creator is explicit
and narrow; all other SHACL violations fail validation.

## Provenance and manifest

Write local provenance for the current source/build artifacts. Artifact paths
may be files or build-output directories. A directory digest binds every
non-link file by its ordinal relative path and exact bytes. For the local
distribution, include both the application JAR and the completed frontend tree
as outputs named `ayuvahak.jar` and `frontend-dist`. This records source,
builder, build type, parameters, and output digests; it is SLSA-informed
evidence and does not claim a SLSA level.

```powershell
python tools/release_evidence/release_evidence.py provenance `
  --source-revision 0123456789abcdef0123456789abcdef01234567 `
  --builder-id local://SYN-BUILDER-001 `
  --build-type maven-package `
  --version 1.2.3-rc.1 `
  --parameter migrationRange=V1-V99 `
  --artifact ayuvahak.jar=target/ayuvahak-platform-0.1.0.jar `
  --artifact frontend-dist=ui/dist `
  --output .scratch/release-evidence/provenance.json
```

Create a manifest bound to the actual SPDX SBOM and its offline validation
report. The command revalidates the SBOM, checks report/output hashes, and
requires the SBOM-bound application JAR digest to be in the manifest artifact
set.

```powershell
python tools/release_evidence/release_evidence.py manifest `
  --version 1.2.3-rc.1 `
  --source-revision 0123456789abcdef0123456789abcdef01234567 `
  --builder-id local://SYN-BUILDER-001 `
  --build-type maven-package `
  --artifact ayuvahak.jar=target/ayuvahak-platform-0.1.0.jar `
  --artifact frontend-dist=ui/dist `
  --migration-range V1-V99 `
  --supported-upgrade-path '0.1.x to 1.2.3' `
  --sbom .scratch/release-evidence/ayuvahak.spdx.json `
  --sbom-report .scratch/release-evidence/ayuvahak.spdx-validation.json `
  --validator-python .scratch/local-toolchain/spdx3-validate-env/Scripts/python.exe `
  --output .scratch/release-evidence/manifest.json
```

Release eligibility still requires verified provenance, a signer-pinned signed
evidence bundle, and a reviewed local vulnerability inventory bound to the exact
SBOM and application artifact. The SBOM report distinguishes total package nodes
from runtime dependency components; scanner component counts are checked against
the latter. SPDX validation alone never marks a release eligible.

## Signing and vulnerability review

`sign-bundle` accepts an operator-selected local PKCS#12 identity and reads its
password only from a named environment variable. `verify-bundle` requires the
expected signer certificate SHA-256 pin and can enforce the expected content
entry set. Acceptance uses a local ephemeral identity only; it does not create
or claim a production signing identity.

Vulnerability input is a local JSON inventory with explicit severity,
reachability, known-exploited status, fix availability, and exposure. The
classifier emits review dispositions; it does not scan dependencies, decide
exploitability, or automatically accept a finding. A time-bounded exception
must match the finding and contain an owner, rationale, compensating controls,
approver, and future expiry. Exceptions do not erase findings or independently
authorize release.

The reviewed inventory uses schema `ayuvahak.vulnerability-inventory/v1` and
binds the local scan to the SHA-256 digests of the SPDX document, application
JAR, and raw scanner result. Record the scanner/version, local database source,
ecosystem, retrieval and last-modified times, and database digest. The review
record identifies a reviewer, timestamp, and count of individually reviewed
findings. Every finding must record its affected component/version, severity,
reachability (true, false, or unknown), known-exploited status, fix availability,
deployment exposure, owner, rationale, and an explicit disposition. Release
eligibility accepts only a reviewed finding marked not applicable after a
documented unreachable assessment or covered by a current time-bounded
exception; unresolved findings fail closed. A zero-finding scan is valid only
when it is bound to the exact current SBOM/JAR and the matching local database
snapshot. A database miss is not proof that a component is safe.

## Safety boundary

Examples and tests use synthetic identifiers and local ephemeral keys. The
helpers do not contact healthcare systems, publish artifacts, scan external
targets, generate production signing identities, create credentials, submit a
DPG application, or implement Phase 14 functionality.
