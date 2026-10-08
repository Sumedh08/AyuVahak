package org.ayuvahak.directory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.junit.jupiter.api.Test;

class DirectoryAuthoritiesTest {

  @Test
  void registersTheDirectoryAuthoritiesAtTheirDeclaredScopes() {
    assertThat(DirectoryAuthorities.read().code()).isEqualTo("DirectoryRead");
    assertThat(DirectoryAuthorities.read().allowedScopeKinds())
        .containsExactlyInAnyOrder(
            ExecutionScope.Kind.PLATFORM,
            ExecutionScope.Kind.ORGANIZATION,
            ExecutionScope.Kind.FACILITY);

    assertThat(DirectoryAuthorities.curation().code()).isEqualTo("DirectoryCuration");
    assertThat(DirectoryAuthorities.curation().allowedScopeKinds())
        .containsExactlyInAnyOrder(
            ExecutionScope.Kind.PLATFORM,
            ExecutionScope.Kind.ORGANIZATION,
            ExecutionScope.Kind.FACILITY);

    assertThat(DirectoryAuthorities.vocabularyAdministration().code())
        .isEqualTo("DirectoryVocabularyAdministration");
    assertThat(DirectoryAuthorities.vocabularyAdministration().allowedScopeKinds())
        .isEqualTo(Set.of(ExecutionScope.Kind.PLATFORM));
  }

  @Test
  void authorityCatalogRejectsDuplicateCodesAcrossOwningModules() {
    assertThatThrownBy(
            () ->
                new FunctionalAuthorityCatalog(
                    List.of(DirectoryAuthorities.read(), DirectoryAuthorities.read())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate functional authority code: DirectoryRead");
  }
}
