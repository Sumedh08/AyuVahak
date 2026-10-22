package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.authentication.ActorAuthentication;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ProductionRequestAbuseFilterTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void authenticatedLimitFollowsPrincipalAcrossSourceAddresses() throws Exception {
    var filter = filter(properties(1));
    ActorReference actor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1");
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));

    MockHttpServletResponse first =
        invoke(filter, request("GET", "/api/v1/directory", "192.0.2.1"));
    MockHttpServletResponse second =
        invoke(filter, request("GET", "/api/v1/directory", "198.51.100.2"));

    assertThat(first.getStatus()).isEqualTo(200);
    assertThat(first.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
    assertRateLimited(second);
  }

  @Test
  void independentPrincipalsAndTrafficClassesHaveIndependentBudgets() throws Exception {
    var filter = filter(properties(1));
    ActorReference firstActor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1");
    ActorReference secondActor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-2");

    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(firstActor));
    MockHttpServletResponse firstRead =
        invoke(filter, request("GET", "/api/v1/directory", "192.0.2.1"));
    MockHttpServletResponse limitedRead =
        invoke(filter, request("GET", "/api/v1/directory", "192.0.2.1"));
    MockHttpServletResponse writeClass =
        invoke(filter, request("POST", "/api/v1/directory", "192.0.2.1"));

    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(secondActor));
    MockHttpServletResponse secondPrincipal =
        invoke(filter, request("GET", "/api/v1/directory", "192.0.2.1"));

    assertThat(firstRead.getStatus()).isEqualTo(200);
    assertRateLimited(limitedRead);
    assertThat(writeClass.getStatus()).isEqualTo(200);
    assertThat(secondPrincipal.getStatus()).isEqualTo(200);
  }

  @Test
  void principalBudgetIsSharedAcrossOperationsAndServiceOrHumanIdentity() throws Exception {
    ProductionProfileProperties properties = properties(100);
    properties.getLimits().setPrincipalRequestsPerMinute(1);
    properties.getLimits().setSensitiveWriteRequestsPerMinute(100);
    var filter = filter(properties);
    ActorReference actor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1");
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));

    assertThat(invoke(filter, request("GET", "/api/v1/directory", "192.0.2.1")).getStatus())
        .isEqualTo(200);
    assertRateLimited(invoke(filter, request("POST", "/api/v1/referrals", "198.51.100.2")));
  }

  @Test
  void organizationScopeBudgetAppliesAcrossPrincipalsAndSeparatesScopes() throws Exception {
    ProductionProfileProperties properties = properties(100);
    properties.getLimits().setScopeRequestsPerMinute(1);
    var filter = filter(properties);
    ActorReference firstActor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1");
    ActorReference secondActor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-2");

    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(firstActor));
    assertThat(invoke(filter, scopedRequest("SYN-ORG-001", "192.0.2.1")).getStatus())
        .isEqualTo(200);

    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(secondActor));
    assertRateLimited(invoke(filter, scopedRequest("SYN-ORG-001", "198.51.100.2")));
    assertThat(invoke(filter, scopedRequest("SYN-ORG-002", "198.51.100.2")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void anonymousCallerCannotConsumeRequestedOrganizationBudget() throws Exception {
    ProductionProfileProperties properties = properties(100);
    properties.getLimits().setScopeRequestsPerMinute(1);
    var filter = filter(properties);

    assertThat(invoke(filter, scopedRequest("SYN-ORG-001", "192.0.2.1")).getStatus())
        .isEqualTo(200);
    ActorReference actor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1");
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));
    assertThat(invoke(filter, scopedRequest("SYN-ORG-001", "192.0.2.1")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void partnerBudgetFollowsVerifiedServiceAcrossOperationClassesAndSourceAddresses()
      throws Exception {
    ProductionProfileProperties properties = properties(100);
    properties.getLimits().setPartnerRequestsPerMinute(1);
    properties.getLimits().setFederationRequestsPerMinute(100);
    properties.getLimits().setExchangeRequestsPerMinute(100);
    var filter = filter(properties);
    ActorReference peer =
        ActorReference.service(new IssuerId("https://identity.example.test/issuer"), "SYN-PEER-A");
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(peer));

    assertThat(
            invoke(filter, request("POST", "/api/federation/operations", "192.0.2.1")).getStatus())
        .isEqualTo(200);
    assertRateLimited(
        invoke(filter, request("POST", "/api/v1/transport-provider/outcomes", "198.51.100.2")));
  }

  @Test
  void independentProductionFiltersShareTheirPrincipalAndOperationBudget() throws Exception {
    ProductionProfileProperties properties = properties(1);
    TestBudgetStore sharedStore = new TestBudgetStore();
    ProductionRequestAbuseFilter firstReplica =
        new ProductionRequestAbuseFilter(properties, sharedStore);
    ProductionRequestAbuseFilter secondReplica =
        new ProductionRequestAbuseFilter(properties, sharedStore);
    ActorReference actor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1");
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));

    assertThat(invoke(firstReplica, request("GET", "/api/v1/directory", "192.0.2.1")).getStatus())
        .isEqualTo(200);
    assertRateLimited(invoke(secondReplica, request("GET", "/api/v1/directory", "198.51.100.2")));
  }

  @Test
  void operatorSupportHasASeparateDatabaseSharedPerPrincipalBudget() throws Exception {
    ProductionProfileProperties properties = properties(100);
    properties.getLimits().setOperatorSupportRequestsPerMinute(1);
    TestBudgetStore sharedStore = new TestBudgetStore();
    ProductionRequestAbuseFilter firstReplica =
        new ProductionRequestAbuseFilter(properties, sharedStore);
    ProductionRequestAbuseFilter secondReplica =
        new ProductionRequestAbuseFilter(properties, sharedStore);
    ActorReference actor =
        ActorReference.human(new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1");
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));

    assertThat(
            invoke(firstReplica, request("POST", "/api/v1/operator-support/answers", "192.0.2.1"))
                .getStatus())
        .isEqualTo(200);
    assertRateLimited(
        invoke(secondReplica, request("POST", "/api/v1/operator-support/answers", "198.51.100.2")));
    assertThat(
            invoke(secondReplica, request("GET", "/api/v1/directory", "198.51.100.2")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void anonymousLimitUsesRemoteAddressAndDoesNotTrustForwardedHeaders() throws Exception {
    var filter = filter(properties(10, 1));
    MockHttpServletRequest firstRequest = request("GET", "/api/v1/system-info", "192.0.2.1");
    firstRequest.addHeader("X-Forwarded-For", "203.0.113.9");
    MockHttpServletRequest secondRequest = request("GET", "/api/v1/system-info", "192.0.2.1");
    secondRequest.addHeader("X-Forwarded-For", "198.51.100.9");

    assertThat(invoke(filter, firstRequest).getStatus()).isEqualTo(200);
    assertRateLimited(invoke(filter, secondRequest));
    assertThat(invoke(filter, request("GET", "/api/v1/system-info", "192.0.2.2")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void sseConnectionsAreBoundedPerPrincipalUntilAsyncCompletion() throws Exception {
    var filter = filter(properties(10, 10, 1, 10));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new ActorAuthentication(
                ActorReference.human(
                    new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1")));

    MockHttpServletRequest firstRequest =
        request("GET", "/api/v1/notifications/stream", "192.0.2.1");
    firstRequest.setAsyncSupported(true);
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    filter.doFilter(
        firstRequest,
        firstResponse,
        (request, response) -> ((MockHttpServletRequest) request).startAsync());

    MockHttpServletResponse secondResponse =
        invoke(filter, request("GET", "/api/v1/notifications/stream", "192.0.2.2"));
    assertRateLimited(secondResponse);

    firstRequest.getAsyncContext().complete();
    MockHttpServletResponse afterCompletion =
        invoke(filter, request("GET", "/api/v1/notifications/stream", "192.0.2.2"));
    assertThat(afterCompletion.getStatus()).isEqualTo(200);
  }

  @Test
  void sseConnectionsAreBoundedAcrossPrincipalsUntilAsyncCompletion() throws Exception {
    var filter = filter(properties(10, 10, 10, 1));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new ActorAuthentication(
                ActorReference.human(
                    new IssuerId("https://identity.example.test/issuer"), "SYN-USER-1")));

    MockHttpServletRequest firstRequest =
        request("GET", "/api/v1/notifications/stream", "192.0.2.1");
    firstRequest.setAsyncSupported(true);
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    filter.doFilter(
        firstRequest,
        firstResponse,
        (request, response) -> ((MockHttpServletRequest) request).startAsync());

    SecurityContextHolder.getContext()
        .setAuthentication(
            new ActorAuthentication(
                ActorReference.human(
                    new IssuerId("https://identity.example.test/issuer"), "SYN-USER-2")));
    assertRateLimited(invoke(filter, request("GET", "/api/v1/notifications/stream", "192.0.2.2")));

    firstRequest.getAsyncContext().complete();
    assertThat(
            invoke(filter, request("GET", "/api/v1/notifications/stream", "192.0.2.2")).getStatus())
        .isEqualTo(200);
  }

  @Test
  void routeClassesSelectConfiguredFederationExchangeAndAdminBudgets() throws Exception {
    var filter = filter(properties(100));
    ActorReference actor =
        ActorReference.service(
            new IssuerId("https://identity.example.test/issuer"), "SYN-SERVICE-1");
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));

    assertThat(
            invoke(filter, request("POST", "/api/federation/operations", "192.0.2.1")).getStatus())
        .isEqualTo(200);
    assertRateLimited(invoke(filter, request("POST", "/api/federation/operations", "192.0.2.1")));
    assertThat(
            invoke(filter, request("POST", "/api/v1/directory/mcsd/iti130", "192.0.2.1"))
                .getStatus())
        .isEqualTo(200);
    assertRateLimited(
        invoke(filter, request("POST", "/api/v1/directory/mcsd/iti130", "192.0.2.1")));
    assertThat(
            invoke(filter, request("POST", "/api/v1/platform/access-grants", "192.0.2.1"))
                .getStatus())
        .isEqualTo(200);
    assertRateLimited(
        invoke(filter, request("POST", "/api/v1/platform/access-grants", "192.0.2.1")));
  }

  @Test
  void storeFailureFailsClosedWithoutInvokingRequestHandler() throws Exception {
    ProductionRequestAbuseFilter filter =
        new ProductionRequestAbuseFilter(
            properties(100),
            charges -> {
              throw new DataAccessResourceFailureException("synthetic database outage");
            });
    MockHttpServletResponse response =
        invoke(filter, request("GET", "/api/v1/directory", "192.0.2.1"));

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("REQUEST_RATE_LIMITER_UNAVAILABLE");
  }

  private static MockHttpServletResponse invoke(
      ProductionRequestAbuseFilter filter, MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicInteger invocations = new AtomicInteger();
    filter.doFilter(request, response, (req, res) -> invocations.incrementAndGet());
    if (response.getStatus() == 200) {
      assertThat(invocations).hasValue(1);
    } else {
      assertThat(invocations).hasValue(0);
    }
    return response;
  }

  private static ProductionRequestAbuseFilter filter(ProductionProfileProperties properties) {
    return new ProductionRequestAbuseFilter(properties, new TestBudgetStore());
  }

  private static MockHttpServletRequest request(String method, String path, String remoteAddress) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setRequestURI(path);
    request.setRemoteAddr(remoteAddress);
    return request;
  }

  private static MockHttpServletRequest scopedRequest(String organizationId, String remoteAddress) {
    MockHttpServletRequest request = request("GET", "/api/v1/directory", remoteAddress);
    request.addHeader("X-AyuVahak-Execution-Scope", "ORGANIZATION");
    request.addHeader("X-AyuVahak-Organization-Id", organizationId);
    return request;
  }

  private static ProductionProfileProperties properties(int authenticatedReadLimit) {
    return properties(authenticatedReadLimit, 100, 3, 100);
  }

  private static ProductionProfileProperties properties(
      int authenticatedReadLimit, int anonymousLimit) {
    return properties(authenticatedReadLimit, anonymousLimit, 3, 100);
  }

  private static ProductionProfileProperties properties(
      int authenticatedReadLimit, int anonymousLimit, int sseLimit) {
    return properties(authenticatedReadLimit, anonymousLimit, sseLimit, 100);
  }

  private static ProductionProfileProperties properties(
      int authenticatedReadLimit, int anonymousLimit, int sseLimit, int sseTotalLimit) {
    ProductionProfileProperties properties = new ProductionProfileProperties();
    ProductionProfileProperties.Limits limits = new ProductionProfileProperties.Limits();
    limits.setPrincipalRequestsPerMinute(10_000);
    limits.setScopeRequestsPerMinute(10_000);
    limits.setPartnerRequestsPerMinute(10_000);
    limits.setAnonymousRequestsPerMinute(anonymousLimit);
    limits.setAuthenticatedReadRequestsPerMinute(authenticatedReadLimit);
    limits.setOperatorSupportRequestsPerMinute(10_000);
    limits.setSensitiveWriteRequestsPerMinute(1);
    limits.setAdminRequestsPerMinute(1);
    limits.setSseConnectionsPerPrincipal(sseLimit);
    limits.setSseConnectionsTotal(sseTotalLimit);
    limits.setSseSubscriptionsPerMinute(10_000);
    limits.setFederationRequestsPerMinute(1);
    limits.setExchangeRequestsPerMinute(1);
    properties.setLimits(limits);
    return properties;
  }

  private static void assertRateLimited(MockHttpServletResponse response) {
    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(
            new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
        .contains("REQUEST_RATE_LIMITED");
  }

  private static final class TestBudgetStore implements RequestBudgetStore {

    private final ConcurrentHashMap<TestBucketKey, Integer> remaining = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean tryAcquire(List<Charge> charges) {
      List<TestBucketKey> keys =
          charges.stream()
              .map(
                  charge ->
                      new TestBucketKey(
                          charge.dimension(), charge.budgetClass(), charge.identity()))
              .toList();
      if (keys.stream()
          .anyMatch(
              key -> remaining.computeIfAbsent(key, ignored -> limitFor(charges, key)) <= 0)) {
        return false;
      }
      keys.forEach(key -> remaining.computeIfPresent(key, (ignored, count) -> count - 1));
      return true;
    }

    private static int limitFor(List<Charge> charges, TestBucketKey key) {
      return charges.stream()
          .filter(
              charge ->
                  charge.dimension() == key.dimension()
                      && charge.budgetClass().equals(key.budgetClass())
                      && charge.identity().equals(key.identity()))
          .findFirst()
          .orElseThrow()
          .requestsPerMinute();
    }
  }

  private record TestBucketKey(
      RequestBudgetStore.Dimension dimension, String budgetClass, String identity) {}
}
