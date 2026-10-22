# Optional operator-support provider profile

Operator support is disabled by default. AyuVahak's care-coordination workflows do not depend on it.
When enabled, it accepts only a finite topic key and a bounded set of reviewed documentation excerpts.
It does not receive patient, clinical, Referral, Transfer, Transport, or deployment-record data. It has
no tool calls or mutation authority. Its responses remain advisory and their citations are checked
against the supplied source identifiers.

## Configure an external compatible provider

An administrator configures the profile through deployment configuration and restarts the application.
The endpoint is not accepted from a browser request or model output. Configure the exact host in the
deployment allowlist, and use the fixed Chat Completions path over HTTPS on port 443:

```text
AYUVAHAK_OPERATOR_SUPPORT_ENABLED=true
AYUVAHAK_OPERATOR_SUPPORT_PROVIDER=OPENAI_COMPATIBLE
AYUVAHAK_OPERATOR_SUPPORT_ENDPOINT=https://api.openai.com/v1/chat/completions
AYUVAHAK_OPERATOR_SUPPORT_ALLOWED_HOSTS=api.openai.com
AYUVAHAK_OPERATOR_SUPPORT_MODEL=<deployment-approved-model-id>
AYUVAHAK_OPERATOR_SUPPORT_PROVIDER_PROFILE_REVISION=<non-secret-revision>
AYUVAHAK_OPERATOR_SUPPORT_SECRET_REFERENCE=env:AYUVAHAK_OPERATOR_SUPPORT_API_KEY
AYUVAHAK_OPERATOR_SUPPORT_TIMEOUT=PT8S
AYUVAHAK_OPERATOR_SUPPORT_MAX_RESPONSE_BYTES=16384
AYUVAHAK_OPERATOR_SUPPORT_USE_CASE=DOCUMENTATION_OPERATOR_SUPPORT
AYUVAHAK_OPERATOR_SUPPORT_ALLOWED_DATA_CLASS=APPROVED_DOCUMENTATION_ONLY
AYUVAHAK_OPERATOR_SUPPORT_RETENTION_DISPOSITION=NO_PROMPT_RETENTION
AYUVAHAK_OPERATOR_SUPPORT_TRAINING_USE=NO_TRAINING
AYUVAHAK_OPERATOR_SUPPORT_PROCESSING_JURISDICTION=<reviewed deployment jurisdiction code>
AYUVAHAK_OPERATOR_SUPPORT_MONTHLY_SPEND_CAP=<positive provider-side monthly cap>
AYUVAHAK_OPERATOR_SUPPORT_MONTHLY_SPEND_CAP_CURRENCY=<ISO-4217 currency>
AYUVAHAK_OPERATOR_SUPPORT_SPEND_CAP_ENFORCED=true
AYUVAHAK_OPERATOR_SUPPORT_SPEND_CAP_CONTROL_REFERENCE=ref:<reviewed provider budget control>
AYUVAHAK_LIMIT_OPERATOR_SUPPORT_PER_MINUTE=<deployment-reviewed per-user limit>
```

Place the actual key in the deployment's secret environment under the referenced name. AyuVahak
resolves it through the existing secret-provider abstraction for the duration of a request and never
returns or persists the value. The endpoint host must be explicitly allowlisted; the HTTPS transport
vets all DNS answers against the production egress policy, pins the vetted addresses for the request,
verifies the connected peer, disables redirects/retries, and bounds request, response, and time.

The external profile fails closed unless the administrator records the finite documentation-only
purpose/data class, provider retention disposition, no-training policy, processing jurisdiction and
provider-side monthly spending cap/control reference. These are deployment review attestations, not
claims AyuVahak can verify against the provider. The spending cap is enforced at the provider account;
AyuVahak enforces its own per-principal request budget and bounded, isolated provider executor. The
production request budget is database-shared across replicas. The per-user value must be included in
the deployment's production profile and reviewed with the expected provider latency/cost.

The status shown in the operator-support workspace reports whether a syntactically valid secret
reference is present; it never reads back or verifies the referenced secret value. Resolution is
attempted only for an authorized support request, and a missing or oversized credential produces an
abstention without exposing provider or secret details.

Only the documented provider profile is supported. Use the deterministic local provider or leave the
feature disabled for acceptance and local development. Automated acceptance uses a local in-memory
transport/provider fake and never calls an external model. Do not use real patient data, production
operational records, secrets, or credentials as provider input. Review the provider's retention,
training-use, jurisdiction, and contractual terms before a deployment administrator enables it.

## Re-evaluation gate

An enabled external profile is paused unless deployment configuration contains the exact profile
fingerprint that was reviewed and evaluated:

```text
AYUVAHAK_OPERATOR_SUPPORT_EVALUATED_PROFILE_FINGERPRINT=sha256:<64 lowercase hex characters>
```

An authorized operator can read the current required fingerprint and whether it matches from
`GET /api/v1/operator-support/profile`. The fingerprint covers the provider endpoint and model,
non-secret provider profile revision, secret reference name, egress allowlist, timeout and response
bound, allowed data class, provider retention/training dispositions, processing jurisdiction,
provider-side monthly spend cap and control reference, approved corpus version and content hashes,
capability contract, actual prompt text, purpose,
prompt-template, input-policy, and output-schema versions. It contains no secret value.
Changes to any covered field invalidate the configured fingerprint and cause the service to abstain
with `RE_EVALUATION_REQUIRED` until a new deployment review is recorded. Bump the corresponding
purpose/template/input-policy or capability version when its semantics change. Increment the
non-secret provider profile revision when rotating the provider account/tenant, credential identity,
retention/training terms, jurisdiction, or any provider-side policy that could affect handling. Do
not hash or expose the credential itself.

Setting this value is a deployment-governance attestation: the accountable deployment owner must
complete and retain the applicable local synthetic evaluation and review record for the exact
fingerprint before setting it. The automated test suite uses only deterministic fakes; it does not
evaluate a real external model or establish provider quality, factuality, fairness, or clinical
safety. Do not set the fingerprint merely to enable the integration. If the deployment cannot
perform and retain that review, leave external operator support disabled.

Configuration changes require a restart. The UI reports advisory/disabled behavior without exposing
the endpoint secret or its value. A response failure or invalid citation abstains; ordinary
documentation and all care-coordination operations remain available.
