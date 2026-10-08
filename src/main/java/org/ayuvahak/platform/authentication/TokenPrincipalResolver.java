package org.ayuvahak.platform.authentication;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.IssuerId;

/** Maps the configured issuer's verified access-token profile to a minimized actor reference. */
public final class TokenPrincipalResolver {

  private final IssuerId issuer;
  private final Set<String> serviceClientIds;

  public TokenPrincipalResolver(IssuerId issuer, Set<String> serviceClientIds) {
    this.issuer = Objects.requireNonNull(issuer, "issuer");
    this.serviceClientIds = Set.copyOf(serviceClientIds);
    if (this.serviceClientIds.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("service client identifiers must not be blank");
    }
  }

  public ActorReference resolve(Map<String, Object> claims) {
    Objects.requireNonNull(claims, "claims");
    if (!"Bearer".equals(claims.get("typ"))) {
      throw new InvalidAccessTokenException();
    }

    String subject = nonBlankString(claims.get("sub"));
    if (subject == null) {
      throw new InvalidAccessTokenException();
    }

    if (claims.containsKey("client_id")) {
      String clientId = nonBlankString(claims.get("client_id"));
      if (clientId == null || !serviceClientIds.contains(clientId)) {
        throw new InvalidAccessTokenException();
      }
      return ActorReference.service(issuer, clientId);
    }

    return ActorReference.human(issuer, subject);
  }

  private static String nonBlankString(Object value) {
    if (!(value instanceof String string) || string.isBlank()) {
      return null;
    }
    return string;
  }
}
