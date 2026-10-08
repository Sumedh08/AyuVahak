package org.ayuvahak.directory.domain;

import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Directory-owned permissions and the scope kinds they are allowed to govern. */
public final class DirectoryAuthorities {

  private static final Set<ExecutionScope.Kind> DIRECTORY_SCOPES =
      Set.of(
          ExecutionScope.Kind.PLATFORM,
          ExecutionScope.Kind.ORGANIZATION,
          ExecutionScope.Kind.FACILITY);

  private static final FunctionalAuthority READ =
      new FunctionalAuthority("DirectoryRead", DIRECTORY_SCOPES);
  private static final FunctionalAuthority CURATION =
      new FunctionalAuthority("DirectoryCuration", DIRECTORY_SCOPES);
  private static final FunctionalAuthority VOCABULARY_ADMINISTRATION =
      new FunctionalAuthority(
          "DirectoryVocabularyAdministration", Set.of(ExecutionScope.Kind.PLATFORM));

  private DirectoryAuthorities() {}

  public static FunctionalAuthority read() {
    return READ;
  }

  public static FunctionalAuthority curation() {
    return CURATION;
  }

  public static FunctionalAuthority vocabularyAdministration() {
    return VOCABULARY_ADMINISTRATION;
  }
}
