# Local first run: Synthetic Demo or Empty

The local launcher requires an explicit installation mode. It binds AyuVahak and Keycloak to
loopback and uses a dedicated Compose project and database volume for that installation.

## Start the installation

Run `scripts/local/Start-AyuVahak.ps1` and choose one of:

- **Synthetic Demo**: imports the local-only Keycloak realm and synthetic seed/operator identities.
- **Empty**: imports an empty local Keycloak realm with no user or business data. Create the first
  local identity through Keycloak before continuing. Institutional deployments should instead
  connect their organization-managed OIDC identity provider.

The choice is recorded for that local installation. Changing modes requires a separate clean
installation; the launcher will not reuse one mode's database volume for the other.
On the first start, the local database and Keycloak initialize before AyuVahak starts. The launcher
allows up to fifteen minutes for first dependency initialization. A fresh local Empty package on the
acceptance host took about ten and a half minutes for Keycloak's initial Quarkus augmentation,
database initialization and realm import; later starts reuse its initialized local volume. This
ordering keeps dependency initialization from competing with the application's bounded FHIR
standards-profile initialization. The
application may then take several minutes to become ready; the launcher reports progress and waits
up to ten minutes before returning a readiness diagnostic. If a configured loopback port is unavailable, change the corresponding `AYUVAHAK_HTTP_PORT` or
`AYUVAHAK_KEYCLOAK_PORT` in `.env` and retry.

## Create a local identity in Empty mode

The packaged Empty installation includes a local Keycloak realm, but it does not create a human
identity or an AyuVahak password. Open the loopback Keycloak administration page using the port in
`.env` (by default `http://localhost:8081/admin`). Sign in with the local administrator name and
generated password stored in that same ignored `.env` file. Select the `ayuvahak` realm, create a
human user, set a local password, and record the user's Keycloak ID. That ID is the OIDC `sub` used
by the AyuVahak bootstrap command below. Keep this local identity confined to the disposable local
installation.

## Establish the first AyuVahak grant

An IdP account does not automatically grant AyuVahak access. After the application and database are
ready, run the existing operator-controlled one-shot grant through the packaged application image:

```powershell
# Synthetic Demo: targets the subject in infra/keycloak/demo-manifest.json
scripts/local/Initialize-AyuVahak.ps1

# Empty: provide the Keycloak user ID (`sub`) created above, or the exact `sub` from your configured IdP
scripts/local/Initialize-AyuVahak.ps1 -GranteeIdentifier "<OIDC subject>"
```

The helper asks for the confirmation phrase and grants only platform
`AccessGrantAdministration`. It does not create an IdP account. It writes the existing audited
bootstrap record through the one-shot CLI and refuses if bootstrap or grant state already exists.
If the local app is running, the helper stops it briefly while the isolated non-web bootstrap
process initializes, then restarts it and waits for readiness. The database and identity provider
remain running. If the result is unknown, inspect the local state before trying again.

Synthetic Demo identities use the local-only password `local-only-not-a-secret`. This fixed
demonstration credential is intentionally public and is valid only in the loopback-bound Synthetic
Demo realm. Never use it outside that disposable mode. `synthetic.system-admin` is the bootstrap access
administrator; `synthetic.demo-seeder` is a separate fixture operator. The role-specific browser
personas are `synthetic.org-admin`, `synthetic.referral-coordinator`,
`synthetic.receiving-coordinator`, `synthetic.capacity-manager`,
`synthetic.transport-coordinator`, and `synthetic.analyst`. Each persona receives only its declared
organization or facility grants when the Demo seed completes. These identities and grants exist
only to demonstrate the real capability projection and server authorization using fictional local
records. Empty mode contains no default human identity or shared application password.

## Seed the Synthetic Demo workflows

After the one-shot grant above succeeds, run:

```powershell
scripts/local/Seed-AyuVahakDemo.ps1
```

The command is available only for the dedicated `SYNTHETIC_DEMO` installation and sends requests
only to its configured localhost AyuVahak and Keycloak ports. It uses the audited grant API and the
Directory, Capacity, Referral, Matching, Transfer, Transport, and Analytics owner APIs; it does not
write business rows directly. The versioned dataset is in `demo-data/demo-manifest.json`. Its
accounts, organizations, facilities, capability, observation, Referral and Patient reference are
fictional `SYN-*` records. The seed command is safe to re-run after it completes; it reports the
existing completion marker without changing data. An interrupted seed is marked `IN_PROGRESS` and
is not replayed automatically because a request may have committed before a local failure. After
confirming `.local-state/installation.json` and `.local-state/demo-seed-state.json` identify the same
dedicated Demo installation and manifest, resume with:

```powershell
scripts/local/Seed-AyuVahakDemo.ps1 -ResumeInProgress
```

The resume option is restricted to the exact manifest version and installation ID and reuses the
persisted request timestamps and idempotency keys. If the seed state or installation identity cannot
be verified, use the explicitly confirmed Demo reset before starting again.

The Demo realm includes a separate `synthetic.demo-seeder` browser identity for the seed workflow.
Its public local-only credential is the same fixed demonstration string shown above and is usable
only through the loopback-bound Demo realm. A Demo-only public OIDC client enables the local command
to use that identity; it is absent from Empty and production configuration. The bootstrap
administrator, role-specific browser personas and seed identity are separate, so the initial
administrator does not grant new authority to itself.

The seed provisions one Referral → deterministic Matching → receiving assessment/Acceptance →
Transport request flow and one aggregate Referral submission indicator. It intentionally leaves
federation and external export as `NOT_CONFIGURED`; it creates no peer, DHIS2 partner, target
metadata, or outbound operation, and contacts no external system. Capacity becomes stale under its
owner freshness policy; the interface must keep that state distinct from zero. The persistent demo
banner remains visible after seeding.

## Add Directory setup access

The first administrator cannot grant themselves more authority. Configure a second local/IdP
identity, then use **Access management** to grant that separate operator the
**Platform Directory setup operator** template. The recipient can use Directory setup to create an
organization and facility through the Directory owner API. In Synthetic Demo mode the interface
accepts only fictional `SYN-*` names.

Use Access management again to grant each stakeholder only the existing authority and organization
or facility scopes they need. Role templates expand to normal backend grants; the server remains the
authorization authority. AyuVahak does not create or manage production IdP accounts.

Choosing Demo still does not seed business records during application startup. The realm manifest
identifies the local bootstrap and seed identities, and the owner-API seed is a separate explicit
operator action. Empty mode remains free of synthetic business records and the Demo seed command
refuses to run against it.

## Stop and reset

`scripts/local/Stop-AyuVahak.ps1` stops only the current installation. The optional
`-ResetDemo` path removes the dedicated disposable Demo volumes after a separate typed confirmation.
It refuses to reset Empty mode. Back up anything you need before resetting; the reset is destructive
to that local Demo database and identity-provider state.
