package org.ayuvahak.platform.authentication;

import java.util.Collection;
import java.util.List;
import org.ayuvahak.platform.core.ActorReference;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/** Authenticated request identity without retaining the bearer token as a credential. */
public final class ActorAuthentication extends AbstractAuthenticationToken {

  private final ActorReference actor;

  public ActorAuthentication(ActorReference actor) {
    this(actor, List.of());
  }

  private ActorAuthentication(
      ActorReference actor, Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.actor = actor;
    setAuthenticated(true);
  }

  @Override
  public ActorReference getPrincipal() {
    return actor;
  }

  @Override
  public Object getCredentials() {
    return null;
  }
}
