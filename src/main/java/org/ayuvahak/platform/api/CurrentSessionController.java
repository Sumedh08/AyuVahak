package org.ayuvahak.platform.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.ayuvahak.platform.authorization.application.SubjectCapabilityQuery;
import org.ayuvahak.platform.authorization.domain.GrantScope;
import org.ayuvahak.platform.configuration.DeploymentMode;
import org.ayuvahak.platform.configuration.DeploymentModeProperties;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated presentation view of the caller's identity and active registered capabilities. */
@RestController
@RequestMapping("/api/v1/session")
public class CurrentSessionController {

  private final SubjectCapabilityQuery capabilities;
  private final DeploymentModeProperties deploymentMode;

  public CurrentSessionController(
      SubjectCapabilityQuery capabilities, DeploymentModeProperties deploymentMode) {
    this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    this.deploymentMode = Objects.requireNonNull(deploymentMode, "deploymentMode");
  }

  @GetMapping
  public ResponseEntity<SessionView> currentSession(@AuthenticationPrincipal ActorReference actor) {
    Objects.requireNonNull(actor, "authenticated actor");
    Map<String, LinkedHashSet<ScopeView>> scopesByAuthority = new TreeMap<>();
    capabilities
        .findFor(actor)
        .forEach(
            capability ->
                scopesByAuthority
                    .computeIfAbsent(capability.authorityCode(), ignored -> new LinkedHashSet<>())
                    .add(ScopeView.from(capability.scope())));
    List<CapabilityView> views =
        scopesByAuthority.entrySet().stream()
            .map(entry -> new CapabilityView(entry.getKey(), List.copyOf(entry.getValue())))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new SessionView(
                new SubjectView(
                    actor.kind().name(),
                    actor.identity().issuer().value(),
                    actor.identity().resourceType(),
                    actor.identity().value()),
                views,
                deploymentMode.getMode()));
  }

  public record SessionView(
      SubjectView subject, List<CapabilityView> capabilities, DeploymentMode deploymentMode) {
    public SessionView {
      Objects.requireNonNull(subject, "subject");
      capabilities = List.copyOf(capabilities);
      Objects.requireNonNull(deploymentMode, "deploymentMode");
    }
  }

  public record SubjectView(String kind, String issuer, String identifierType, String identifier) {}

  public record CapabilityView(String authorityCode, List<ScopeView> scopes) {
    public CapabilityView {
      scopes = List.copyOf(scopes);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ScopeView(String kind, ScopeReference organization, ScopeReference facility) {
    static ScopeView from(GrantScope scope) {
      return new ScopeView(
          scope.kind().name(),
          scope.organization().map(ScopeReference::from).orElse(null),
          scope.facility().map(ScopeReference::from).orElse(null));
    }
  }

  public record ScopeReference(String issuer, String identifierType, String identifier) {
    static ScopeReference from(ExternalReference reference) {
      return new ScopeReference(
          reference.issuer().value(), reference.resourceType(), reference.value());
    }
  }
}
