# Local first run: Synthetic Demo or Empty

The local launcher requires an explicit installation mode. It binds AyuVahak and Keycloak to
loopback and uses a dedicated Compose project and database volume for that installation.

## Start the installation

Run `scripts/local/Start-AyuVahak.ps1` and choose one of:

- **Synthetic Demo**: imports the local-only Keycloak realm and a synthetic sign-in identity.
- **Empty**: imports an empty local Keycloak realm with no user or business data. Create the first
  local identity through Keycloak before continuing. Institutional deployments should instead
  connect their organization-managed OIDC identity provider.

The choice is recorded for that local installation. Changing modes requires a separate clean
installation; the launcher will not reuse one mode's database volume for the other.
On the first start, the local database and Keycloak initialize before AyuVahak starts. This keeps
their cold-start work from competing with the application's bounded FHIR standards-profile
initialization. The application may then take several minutes to become ready; the launcher reports
progress and waits up to ten minutes before returning a readiness diagnostic. If a configured
loopback port is unavailable, change the corresponding `AYUVAHAK_HTTP_PORT` or
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
If the result is unknown, inspect the local state before trying again.

Synthetic Demo sign-in is `synthetic.person` with the local-only password
`local-only-not-a-secret`. This fixed demonstration credential is intentionally public and is valid
only in the loopback-bound Synthetic Demo realm. Never use it outside that disposable mode. Empty
mode contains no default human identity or shared application password.

## Add Directory setup access

The first administrator cannot grant themselves more authority. Configure a second local/IdP
identity, then use **Access management** to grant that separate operator the
**Platform Directory setup operator** template. The recipient can use Directory setup to create an
organization and facility through the Directory owner API. In Synthetic Demo mode the interface
accepts only fictional `SYN-*` names.

Use Access management again to grant each stakeholder only the existing authority and organization
or facility scopes they need. Role templates expand to normal backend grants; the server remains the
authorization authority. AyuVahak does not create or manage production IdP accounts.

Synthetic Demo business records are not currently auto-seeded. The realm manifest identifies the
local synthetic bootstrap user and records `businessData: NOT_SEEDED`; no workflow data is implied
by choosing Demo. Empty mode remains free of synthetic business records.

## Stop and reset

`scripts/local/Stop-AyuVahak.ps1` stops only the current installation. The optional
`-ResetDemo` path removes the dedicated disposable Demo volumes after a separate typed confirmation.
It refuses to reset Empty mode. Back up anything you need before resetting; the reset is destructive
to that local Demo database and identity-provider state.
