package org.ayuvahak.platform.core;

import java.util.Objects;

/** A human or service actor identified within the issuer that authenticated it. */
public record ActorReference(ActorKind kind, ExternalReference identity) {

  public ActorReference {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(identity, "identity");
    String expectedType = kind == ActorKind.HUMAN ? "subject" : "client_id";
    if (!expectedType.equals(identity.resourceType())) {
      throw new IllegalArgumentException("actor identity resource type must match the actor kind");
    }
  }

  public static ActorReference human(IssuerId issuer, String subject) {
    return new ActorReference(ActorKind.HUMAN, new ExternalReference(issuer, "subject", subject));
  }

  public static ActorReference service(IssuerId issuer, String clientId) {
    return new ActorReference(
        ActorKind.SERVICE, new ExternalReference(issuer, "client_id", clientId));
  }

  @Override
  public String toString() {
    return "ActorReference[kind=" + kind + ", identity=redacted]";
  }
}
