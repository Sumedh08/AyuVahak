package org.ayuvahak.platform.authorization.application;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.authorization.domain.GrantScope;
import org.ayuvahak.platform.core.ActorReference;

/** Builds a presentation-only view from the authenticated actor's currently registered grants. */
public final class SubjectCapabilityQuery {

  private static final Comparator<Capability> CAPABILITY_ORDER =
      Comparator.comparing(Capability::authorityCode)
          .thenComparing(capability -> capability.scope().kind().name())
          .thenComparing(
              capability ->
                  capability
                      .scope()
                      .organization()
                      .map(reference -> reference.issuer().value())
                      .orElse(""))
          .thenComparing(
              capability ->
                  capability
                      .scope()
                      .organization()
                      .map(reference -> reference.resourceType())
                      .orElse(""))
          .thenComparing(
              capability ->
                  capability.scope().organization().map(reference -> reference.value()).orElse(""))
          .thenComparing(
              capability ->
                  capability
                      .scope()
                      .facility()
                      .map(reference -> reference.issuer().value())
                      .orElse(""))
          .thenComparing(
              capability ->
                  capability
                      .scope()
                      .facility()
                      .map(reference -> reference.resourceType())
                      .orElse(""))
          .thenComparing(
              capability ->
                  capability.scope().facility().map(reference -> reference.value()).orElse(""));

  private final SubjectCapabilityLookup lookup;
  private final FunctionalAuthorityCatalog authorityCatalog;

  public SubjectCapabilityQuery(
      SubjectCapabilityLookup lookup, FunctionalAuthorityCatalog authorityCatalog) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
    this.authorityCatalog = Objects.requireNonNull(authorityCatalog, "authorityCatalog");
  }

  public List<Capability> findFor(ActorReference actor) {
    Objects.requireNonNull(actor, "actor");
    return lookup.findActiveFor(actor).stream()
        .filter(
            grant ->
                authorityCatalog
                    .find(grant.authorityCode())
                    .map(authority -> authority.allowedScopeKinds().contains(grant.scope().kind()))
                    .orElse(false))
        .map(grant -> new Capability(grant.authorityCode(), grant.scope()))
        .distinct()
        .sorted(CAPABILITY_ORDER)
        .toList();
  }

  public record Capability(String authorityCode, GrantScope scope) {
    public Capability {
      Objects.requireNonNull(authorityCode, "authorityCode");
      Objects.requireNonNull(scope, "scope");
    }
  }
}
