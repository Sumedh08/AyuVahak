package org.ayuvahak.platform.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.ayuvahak.platform.authorization.application.SubjectCapabilityQuery;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.configuration.DeploymentMode;
import org.ayuvahak.platform.configuration.DeploymentModeProperties;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class CurrentSessionControllerTest {

  @Test
  void exposesOnlyConfiguredDeploymentModeAsSessionPresentationMetadata() {
    for (DeploymentMode mode : List.of(DeploymentMode.EMPTY, DeploymentMode.SYNTHETIC_DEMO)) {
      DeploymentModeProperties properties = new DeploymentModeProperties();
      properties.setMode(mode);
      CurrentSessionController controller =
          new CurrentSessionController(
              new SubjectCapabilityQuery(
                  ignored -> List.of(), new FunctionalAuthorityCatalog(List.of())),
              properties);

      var response =
          controller.currentSession(
              ActorReference.human(new IssuerId("urn:test:idp"), "SYN-USER-01"));

      assertThat(response.getBody())
          .extracting(CurrentSessionController.SessionView::deploymentMode)
          .isEqualTo(mode);
      assertThat(response.getBody().subject().identifier()).isEqualTo("SYN-USER-01");
      assertThat(response.getBody().capabilities()).isEmpty();
    }
  }
}
