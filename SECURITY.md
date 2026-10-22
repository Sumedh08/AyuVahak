# Security policy

## Supported release

Security fixes are assessed against the latest published AyuVahak release. A release is not a claim that the software is free of vulnerabilities or approved for a particular institution or jurisdiction.

## Reporting a vulnerability

Do not include patient information, real credentials, private keys, or confidential operational data in a report. Do not test against a public healthcare system or another party's infrastructure.

The repository owner has not designated a separate security contact or response-time commitment. If GitHub private vulnerability reporting is available for this repository, use that private channel. Otherwise, do not publish exploit details in a public issue; first ask the repository owner to designate a private reporting route.

Reports should include the affected version or commit, the local configuration needed to reproduce the issue, the impact, and a safe synthetic reproduction where possible. Security reports are handled as maintainer capacity permits; no service-level response commitment is made.

## Scope and safe testing

Only AyuVahak source code and locally controlled test fixtures are in scope. Public FHIR, DHIS2, identity providers, healthcare organizations, federation peers, registries, and cloud accounts are not test targets. Do not upload AyuVahak artifacts or dependency manifests to an external scanning service without separate authorization.

The project uses local dependency inventory and review evidence. A clean scan does not prove that the software is secure.
