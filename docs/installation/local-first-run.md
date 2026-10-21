# Local first run: Synthetic Demo or Empty

The local launcher requires an explicit installation mode. It binds AyuVahak and Keycloak to
loopback and uses a dedicated Compose project and database volume for that installation.

## Start the installation

Run `scripts/local/Start-AyuVahak.ps1` and choose one of:

- **Synthetic Demo**: imports the local-only Keycloak realm and a synthetic sign-in identity.
- **Empty**: imports no demonstration identity or business data. Configure the external local
  identity provider with the first human identity before continuing.

The choice is recorded for that local installation. Changing modes requires a separate clean
installation; the launcher will not reuse one mode's database volume for the other.

## Establish the first AyuVahak grant

An IdP account does not automatically grant AyuVahak access. After the application and database are
ready, run the existing operator-controlled one-shot grant through the packaged application image:

```powershell
# Synthetic Demo: targets the subject in infra/keycloak/demo-manifest.json
scripts/local/Initialize-AyuVahak.ps1

# Empty: provide the exact `sub` claim from the configured identity provider
scripts/local/Initialize-AyuVahak.ps1 -GranteeIdentifier "<OIDC subject>"
```

The helper asks for the confirmation phrase and grants only platform
`AccessGrantAdministration`. It does not create an IdP account. It writes the existing audited
bootstrap record through the one-shot CLI and refuses if bootstrap or grant state already exists.
If the result is unknown, inspect the local state before trying again.

Synthetic Demo sign-in is `synthetic.person` with the local-only password
`local-only-not-a-secret`. This fixed demonstration credential is intentionally public and is valid
only in the loopback-bound Synthetic Demo realm. Never use it outside that disposable mode. Empty
mode contains no default AyuVahak administrator or shared application password.

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
