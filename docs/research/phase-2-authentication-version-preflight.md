# Phase 2 Authentication Version Preflight

**Retrieved:** 2026-10-08  
**Scope:** Read-only verification of the frozen Spring Boot 4.1.x / Java 25 baseline and the stable Keycloak local reference image for Phase 2. This note records source facts and compatibility implications; it does not change the approved architecture. No Keycloak image was pulled, and no Keycloak instance was contacted.

## Verified source facts

### Spring Boot and Java

- **Framework fact:** At retrieval, Spring Boot's official System Requirements page identifies Spring Boot 4.1.1 as the documented stable version. It lists 4.1.2 as a snapshot, while the Boot 4.2.0-M2 line is preview. The official supported-versions page says the 4.1 and 4.0 lines are actively maintained. Therefore, 4.1.1 is the latest stable patch shown for the frozen 4.1 line on this date. Sources: [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html), [Spring Boot supported versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions), and [Spring Boot releases](https://github.com/spring-projects/spring-boot/releases).
- **Framework fact:** Spring Boot 4.1.1 requires at least Java 17 and is compatible through Java 26. Java 25 is within that supported range. The page also identifies Spring Framework 7.0.9 or later as required. Source: [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html).
- **Framework fact:** The Spring Boot 4.1.1 release notes list an upgrade to Spring Security 7.1.1. The Spring Security reference identifies 7.1.1 as stable and 7.2.0-M2 as preview at retrieval. Thus the Spring Security version managed by the Boot 4.1.1 baseline is 7.1.1. Sources: [Spring Boot 4.1.1 release notes](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1) and [Spring Security reference](https://docs.spring.io/spring-security/reference/).

### Keycloak container

- **Product fact:** Keycloak announced 26.8.0 on 2026-10-01, and its official downloads page listed 26.8.0 as the current download release on 2026-10-08. Sources: [Keycloak 26.8.0 release announcement](https://www.keycloak.org/2026/10/keycloak-2680-released) and [Keycloak downloads](https://www.keycloak.org/downloads).
- **Product fact:** Keycloak's official Docker getting-started guide uses the versioned image reference `quay.io/keycloak/keycloak:26.8.0`; it states that Keycloak container images are published in Keycloak's Quay organization. Keycloak's release documentation also identifies `quay.io/keycloak/keycloak` as the server container-image repository and documents `<major>.<minor>.<patch>` release tags. Sources: [Keycloak Docker guide](https://www.keycloak.org/getting-started/getting-started-docker) and [Keycloak release policy](https://github.com/keycloak/keycloak/blob/main/RELEASES.md).
- **Registry fact:** Read-only `docker buildx imagetools inspect quay.io/keycloak/keycloak:26.8.0` on 2026-10-08 returned the multi-platform OCI index digest `sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc`. Its `linux/amd64` child manifest is `sha256:d79bc4bf1c54e802735ef91926b5c003de1fbb50b1a93382611972277219c9ad`; `linux/arm64` is `sha256:6741e706bd2cc2fcc2c97a74f227589c7283e44cdb210e47ea99258f243f6f74`. Pin the index digest alongside the version tag so the local reference remains multi-platform and content-addressed.
- **Product fact:** The Keycloak 26.8.0 Server Developer Guide says the official container image uses OpenJDK 21. The Keycloak container runs in its own image/JVM; this does not change the AyuVahak application JDK baseline. Source: [Keycloak 26.8.0 Server Developer Guide](https://www.keycloak.org/docs/26.8.0/server_development/).
- **Operational caveat:** Keycloak's Docker getting-started page demonstrates `start-dev` and sample bootstrap credentials for local experimentation. Those examples are not production configuration and are intentionally not copied into this note. Phase 2 must use synthetic local-only configuration and must never treat the documented sample values as secrets or production defaults. Source: [Keycloak Docker guide](https://www.keycloak.org/getting-started/getting-started-docker).

## AyuVahak decisions and implications

These are the existing AyuVahak choices, not requirements imposed by the cited projects:

- Keep the application baseline at Java 25 and Spring Boot 4.1.x. For the authentication preflight dated 2026-10-08, the newest stable compatible patch shown is Spring Boot 4.1.1, with Spring Security 7.1.1 managed by that release.
- Use `quay.io/keycloak/keycloak:26.8.0@sha256:b0f60d489d51c5d113390bdf5461d4c06e6051be026c05549f2e1e10ec352bcc` as the local reference-provider image. Keep it local and developer-controlled. Recheck the release and digest before a future intentional image update.
- Keep provider-specific setup behind the already-defined authentication adapter boundary. This version check does not make Keycloak a mandatory production IdP and does not change AyuVahak's provider-compatible OIDC design.

## Limits

The Keycloak version/digest check and documentation retrieval were read-only; no Keycloak image was pulled, and no external Keycloak instance or identity provider was contacted. For local Phase 2 verification, Temurin JDK `25.0.4.1+1` was downloaded from the official Adoptium API and its published SHA-256 `00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283` was verified before extraction into a user-local toolchain directory; no global environment setting was changed. No credentials, accounts, or patient data were used. The related architecture and authentication decisions remain in [the Phase 2 platform-kernel design](../architecture/phase-2-platform-kernel.md), [the Phase 2 specification](../specs/phase-2-platform-kernel-spec.md), and [the identity-provider research note](phase-2-identity-provider-research.md).

