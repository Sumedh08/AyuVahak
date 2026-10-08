package org.ayuvahak.platform.authorization.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The authorities that the running deployment currently recognizes. */
public final class FunctionalAuthorityCatalog {

  private final Map<String, FunctionalAuthority> authoritiesByCode;

  public FunctionalAuthorityCatalog(Collection<FunctionalAuthority> authorities) {
    Objects.requireNonNull(authorities, "authorities");
    this.authoritiesByCode =
        authorities.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    FunctionalAuthority::code,
                    Function.identity(),
                    (first, duplicate) -> {
                      throw new IllegalArgumentException(
                          "duplicate functional authority code: " + first.code());
                    }));
  }

  public Optional<FunctionalAuthority> find(String code) {
    return code == null ? Optional.empty() : Optional.ofNullable(authoritiesByCode.get(code));
  }
}
