# Production deployment profiles

AyuVahak provides two portable production configuration profiles. They describe operator responsibilities and startup
requirements; they do not provision infrastructure or designate a cloud platform. Local Compose remains the development
and acceptance topology, not a production profile.

## Choose a profile

| Profile | Intended operation | Operator responsibilities |
| --- | --- | --- |
| `production-single-site` | One institution operates one application deployment with PostgreSQL/PostGIS and an OIDC provider. | Operate the database, identity provider, TLS-terminating ingress, backup target, and monitoring; keep the management listener reachable only from the local operator plane. |
| `production-ha` | An institution operates multiple application replicas against one authoritative PostgreSQL/PostGIS deployment and a resilient OIDC provider. | Provide load balancing, database and identity-provider availability, durable database-backed work coordination, backup/restore, and centralized monitoring. Validate the actual topology against its declared SLO and recovery targets. |

Neither profile requires Kubernetes, a cloud provider, Redis, or a message broker. A profile does not claim high availability
unless the operator's database, identity, ingress, backup, and monitoring dependencies support the declared targets.

## Required deployment record

Before activating a production profile, record and approve:

- application profile and deployment identity;
- application, Java runtime, PostgreSQL/PostGIS, and OIDC provider versions;
- artifact digests and enabled integration categories;
- public HTTPS origin, issuer, audience, TLS termination owner, and explicit CORS origins if browser access is enabled;
- secret references only, never resolved secret values;
- management listener address, port, endpoint allowlist, and operator-plane access method;
- configured request and SSE limits;
- the reviewed retention schedule identifier and review date;
- SLI/SLO definitions, workload envelope, and deployment-specific RPO/RTO;
- logging/metrics destinations, backup topology, and accountable operations owner.

Do not place secret values in profile files, environment dumps, logs, diagnostics, or this deployment record.

## Activate and validate

Set exactly one profile, then provide the required values through the deployment's configuration and secret-injection
mechanism:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'production-single-site'
.\mvnw.cmd -B -ntp spring-boot:run
```

Use `production-ha` for the institutional profile. The application validates identity endpoints, HTTPS origin, management
isolation, CORS policy, positive configured resource limits, retention registration, and opaque secret references before
it serves requests. Missing or unsafe values fail startup. The values such as secret references declare the required
configuration contract; the provider-specific resolution mechanism is configured and verified by the later secret-provider
work item.

Production management endpoints are exposed only on a separate loopback listener and limited to minimal health and
read-only Prometheus surfaces. An operator agent sharing the process network namespace can scrape this listener; public
ingress must not proxy it. Liveness is process-local. Readiness includes required local dependencies; optional remote peer
outages do not by themselves require process restart.

The ingress operator terminates TLS, enforces HTTPS, and must apply a reviewed forwarding-header policy. CORS is disabled
unless an explicit HTTPS origin allowlist is configured, and production API CORS never enables credentials. The production
profile does not enable HSTS automatically; the operator must review hostname and subdomain coverage at the ingress.
