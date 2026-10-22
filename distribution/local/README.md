# AyuVahak local distribution

This reference bundle starts the prebuilt AyuVahak JAR and UI with a local PostgreSQL/PostGIS
database and Keycloak. It does not require Maven, Node.js, a Java installation, or a source checkout.
Docker Compose downloads the pinned runtime images on first use.

On Windows, run `scripts/local/Start-AyuVahak.cmd` and explicitly choose Synthetic Demo or Empty.
Then follow `docs/installation/local-first-run.md` to establish the first one-shot AyuVahak grant.
Demo business records are created only by the separate versioned `scripts/local/Seed-AyuVahakDemo.cmd`
command, which uses synthetic `SYN-*` data through owner APIs. Empty mode has no synthetic identity
or business records. For the optional, disabled-by-default documentation support provider profile,
see `docs/installation/operator-support-provider.md`.

The application, database and identity provider publish ports on loopback only. This is a local
reference installation, not a production deployment. Do not expose these services to a network.

Stop with `scripts/local/Stop-AyuVahak.cmd`. The optional confirmed Demo reset removes the disposable
Demo database and identity-provider volumes. It is unavailable in Empty mode.

The project has no designated license. This bundle grants no reuse rights. It is not certified as a
Digital Public Good and makes no healthcare safety, legal-compliance, or universal production
readiness claim.
