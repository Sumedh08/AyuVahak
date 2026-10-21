package org.ayuvahak.platform.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.ayuvahak.platform.authorization.application.GrantedSubjectCapability;
import org.ayuvahak.platform.authorization.application.SubjectCapabilityQuery;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthority;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.authorization.domain.GrantScope;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.junit.jupiter.api.Test;

class SubjectCapabilityQueryTest {

  private static final IssuerId DIRECTORY_ISSUER = new IssuerId("urn:ayuvahak:directory");

  @Test
  void exposesOnlyRegisteredAuthoritiesAtDeclaredScopeAndSortsDeterministically() {
    GrantScope organizationScope =
        new GrantScope(
            ExecutionScope.Kind.ORGANIZATION,
            Optional.of(new ExternalReference(DIRECTORY_ISSUER, "Organization", "SYN-ORG-01")),
            Optional.empty());
    GrantScope platformScope = GrantScope.platform();
    List<GrantedSubjectCapability> activeGrants =
        List.of(
            new GrantedSubjectCapability("UnregisteredAuthority", organizationScope),
            new GrantedSubjectCapability("DirectoryRead", platformScope),
            new GrantedSubjectCapability("DirectoryRead", organizationScope),
            new GrantedSubjectCapability("DirectoryRead", organizationScope));
    FunctionalAuthorityCatalog catalog =
        new FunctionalAuthorityCatalog(
            List.of(
                new FunctionalAuthority(
                    "DirectoryRead", Set.of(ExecutionScope.Kind.ORGANIZATION))));
    SubjectCapabilityQuery query = new SubjectCapabilityQuery(ignored -> activeGrants, catalog);

    var result =
        query.findFor(ActorReference.human(new IssuerId("https://id.test"), "SYN-USER-01"));

    assertThat(result)
        .containsExactly(new SubjectCapabilityQuery.Capability("DirectoryRead", organizationScope));
  }
}
