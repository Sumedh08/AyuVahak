package org.ayuvahak.platform.authorization.application;

import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.authorization.domain.AccessGrant;

public record GrantPage(List<AccessGrant> grants, Optional<String> nextCursor) {
  public GrantPage {
    grants = List.copyOf(grants);
  }
}
