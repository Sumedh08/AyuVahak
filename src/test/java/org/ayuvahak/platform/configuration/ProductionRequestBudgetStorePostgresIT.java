package org.ayuvahak.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.ayuvahak.platform.authentication.ActorAuthentication;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Local PostgreSQL acceptance for request budgets shared by independent application instances. */
@Testcontainers
class ProductionRequestBudgetStorePostgresIT {

  private static final String TEST_PASSWORD = "synthetic-local-only";
  private static final DockerImageName POSTGIS_IMAGE =
      DockerImageName.parse(
              "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
          .asCompatibleSubstituteFor("postgres");

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGIS_IMAGE)
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  private static DataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateBudgetStore() {
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setUrl(POSTGRES.getJdbcUrl());
    source.setUsername(POSTGRES.getUsername());
    source.setPassword(POSTGRES.getPassword());
    dataSource = source;
    jdbc = new JdbcTemplate(dataSource);
    new ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/migration/V94__create_platform_security_request_budgets.sql"))
        .execute(dataSource);
  }

  @Test
  void twoIndependentFiltersSharePrincipalAndOperationBudgetsAcrossSourceAddresses()
      throws Exception {
    jdbc.update("TRUNCATE TABLE platform_security.request_budget_bucket");
    ProductionProfileProperties properties = properties();
    ProductionRequestAbuseFilter firstInstance = newFilter(properties);
    ProductionRequestAbuseFilter secondInstance = newFilter(properties);
    ActorReference actor =
        ActorReference.human(
            new IssuerId("https://identity.example.test/issuer"), "SYN-REPLICA-USER-1");
    int concurrentRequests = 16;
    CountDownLatch ready = new CountDownLatch(concurrentRequests);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger admitted = new AtomicInteger();
    java.util.ArrayList<Future<?>> tasks = new java.util.ArrayList<>();

    try (var executor = Executors.newFixedThreadPool(concurrentRequests)) {
      for (int index = 0; index < concurrentRequests; index++) {
        final int requestIndex = index;
        tasks.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  try {
                    start.await();
                    SecurityContextHolder.getContext()
                        .setAuthentication(new ActorAuthentication(actor));
                    ProductionRequestAbuseFilter filter =
                        requestIndex % 2 == 0 ? firstInstance : secondInstance;
                    MockHttpServletRequest request =
                        new MockHttpServletRequest(
                            "GET", "/api/v1/directory/SYN-FACILITY-A/summary");
                    request.setRequestURI("/api/v1/directory/SYN-FACILITY-A/summary");
                    request.setRemoteAddr(requestIndex % 2 == 0 ? "192.0.2.20" : "198.51.100.20");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> {});
                    if (response.getStatus() == 200) {
                      admitted.incrementAndGet();
                    }
                  } catch (Exception failure) {
                    throw new RuntimeException(failure);
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      }
      boolean allWorkersReady = ready.await(10, TimeUnit.SECONDS);
      start.countDown();
      assertThat(allWorkersReady).isTrue();
      for (Future<?> task : tasks) {
        task.get(30, TimeUnit.SECONDS);
      }
    }

    assertThat(admitted).hasValue(5);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform_security.request_budget_bucket", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform_security.request_budget_bucket "
                    + "WHERE bucket_slot < 0 OR bucket_slot >= 16384",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT budget_dimension FROM platform_security.request_budget_bucket",
                String.class))
        .containsExactlyInAnyOrder("OPERATION", "PRINCIPAL");
  }

  @Test
  void anonymousAddressBudgetIsSharedAcrossIndependentFilters() throws Exception {
    jdbc.update("TRUNCATE TABLE platform_security.request_budget_bucket");
    ProductionProfileProperties properties = properties();
    properties.getLimits().setAnonymousRequestsPerMinute(1);
    ProductionRequestAbuseFilter firstInstance = newFilter(properties);
    ProductionRequestAbuseFilter secondInstance = newFilter(properties);

    assertThat(invokeAnonymous(firstInstance, "192.0.2.31").getStatus()).isEqualTo(200);
    assertThat(invokeAnonymous(secondInstance, "192.0.2.31").getStatus()).isEqualTo(429);
    assertThat(invokeAnonymous(secondInstance, "198.51.100.31").getStatus()).isEqualTo(200);
  }

  @Test
  void operationClassBudgetsAreSharedAcrossReplicasButRemainDistinct() throws Exception {
    jdbc.update("TRUNCATE TABLE platform_security.request_budget_bucket");
    ProductionProfileProperties properties = properties();
    properties.getLimits().setPrincipalRequestsPerMinute(20);
    properties.getLimits().setAuthenticatedReadRequestsPerMinute(1);
    properties.getLimits().setSensitiveWriteRequestsPerMinute(1);
    ProductionRequestAbuseFilter firstInstance = newFilter(properties);
    ProductionRequestAbuseFilter secondInstance = newFilter(properties);
    ActorReference actor = actor("SYN-OPERATION-CLASS-USER");

    assertThat(
            invokeRoute(firstInstance, actor, "GET", "/api/v1/directory", "192.0.2.32")
                .getStatus())
        .isEqualTo(200);
    assertThat(
            invokeRoute(secondInstance, actor, "GET", "/api/v1/directory", "198.51.100.32")
                .getStatus())
        .isEqualTo(429);
    assertThat(
            invokeRoute(secondInstance, actor, "POST", "/api/v1/referrals", "198.51.100.32")
                .getStatus())
        .isEqualTo(200);
    assertThat(
            invokeRoute(firstInstance, actor, "POST", "/api/v1/referrals", "192.0.2.32")
                .getStatus())
        .isEqualTo(429);
    assertThat(
            jdbc.queryForList(
                "SELECT budget_class FROM platform_security.request_budget_bucket "
                    + "WHERE budget_dimension = 'OPERATION'",
                String.class))
        .containsExactlyInAnyOrder("READ", "SENSITIVE_WRITE");
  }

  @Test
  void exhaustedSharedBudgetRefillsAfterTheConfiguredWindow() throws Exception {
    jdbc.update("TRUNCATE TABLE platform_security.request_budget_bucket");
    ProductionProfileProperties properties = properties();
    properties.getLimits().setPrincipalRequestsPerMinute(1);
    properties.getLimits().setAuthenticatedReadRequestsPerMinute(1);
    ProductionRequestAbuseFilter firstInstance = newFilter(properties);
    ProductionRequestAbuseFilter secondInstance = newFilter(properties);
    ActorReference actor = actor("SYN-RATE-REFILL-USER");

    assertThat(
            invokeRoute(firstInstance, actor, "GET", "/api/v1/directory", "192.0.2.33")
                .getStatus())
        .isEqualTo(200);
    assertThat(
            invokeRoute(secondInstance, actor, "GET", "/api/v1/directory", "198.51.100.33")
                .getStatus())
        .isEqualTo(429);

    jdbc.update(
        "UPDATE platform_security.request_budget_bucket "
            + "SET tokens_micros = 0, last_refill_at = clock_timestamp() - interval '61 seconds'");
    assertThat(
            invokeRoute(secondInstance, actor, "GET", "/api/v1/directory", "198.51.100.33")
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void twoIndependentFiltersShareRequestedOrganizationScopeBudget() throws Exception {
    jdbc.update("TRUNCATE TABLE platform_security.request_budget_bucket");
    ProductionProfileProperties properties = properties();
    properties.getLimits().setScopeRequestsPerMinute(1);
    ProductionRequestAbuseFilter firstInstance = newFilter(properties);
    ProductionRequestAbuseFilter secondInstance = newFilter(properties);
    ActorReference firstActor = actor("SYN-ORG-OPERATOR-A");
    ActorReference secondActor = actor("SYN-ORG-OPERATOR-B");

    assertThat(invoke(firstInstance, firstActor, "SYN-ORG-001", "192.0.2.21").getStatus())
        .isEqualTo(200);
    assertThat(invoke(secondInstance, secondActor, "SYN-ORG-001", "198.51.100.21").getStatus())
        .isEqualTo(429);
    assertThat(invoke(secondInstance, secondActor, "SYN-ORG-002", "198.51.100.21").getStatus())
        .isEqualTo(200);
  }

  @Test
  void facilityScopeBudgetIsSharedOnlyForTheSameFacilityAcrossReplicas() throws Exception {
    jdbc.update("TRUNCATE TABLE platform_security.request_budget_bucket");
    ProductionProfileProperties properties = properties();
    properties.getLimits().setScopeRequestsPerMinute(1);
    ProductionRequestAbuseFilter firstInstance = newFilter(properties);
    ProductionRequestAbuseFilter secondInstance = newFilter(properties);

    assertThat(
            invokeFacilityScope(
                    firstInstance,
                    actor("SYN-FACILITY-OPERATOR-A"),
                    "SYN-ORG-001",
                    "SYN-FACILITY-A",
                    "192.0.2.24")
                .getStatus())
        .isEqualTo(200);
    assertThat(
            invokeFacilityScope(
                    secondInstance,
                    actor("SYN-FACILITY-OPERATOR-B"),
                    "SYN-ORG-001",
                    "SYN-FACILITY-A",
                    "198.51.100.24")
                .getStatus())
        .isEqualTo(429);
    assertThat(
            invokeFacilityScope(
                    secondInstance,
                    actor("SYN-FACILITY-OPERATOR-B"),
                    "SYN-ORG-001",
                    "SYN-FACILITY-B",
                    "198.51.100.24")
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void twoIndependentFiltersSharePartnerBudgetAcrossOperationClasses() throws Exception {
    jdbc.update("TRUNCATE TABLE platform_security.request_budget_bucket");
    ProductionProfileProperties properties = properties();
    properties.getLimits().setPrincipalRequestsPerMinute(100);
    properties.getLimits().setPartnerRequestsPerMinute(1);
    ProductionRequestAbuseFilter firstInstance = newFilter(properties);
    ProductionRequestAbuseFilter secondInstance = newFilter(properties);
    ActorReference partner =
        ActorReference.service(new IssuerId("https://identity.example.test/issuer"), "SYN-PEER-A");

    assertThat(
            invokeRoute(firstInstance, partner, "/api/federation/operations", "192.0.2.23")
                .getStatus())
        .isEqualTo(200);
    assertThat(
            invokeRoute(
                    secondInstance, partner, "/api/v1/transport-provider/outcomes", "198.51.100.23")
                .getStatus())
        .isEqualTo(429);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM platform_security.request_budget_bucket "
                    + "WHERE budget_dimension = 'PARTNER' AND budget_class = 'ALL'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void sseSubscriptionRateIsSharedAcrossInstancesWhileConnectionCeilingsStayNodeLocal()
      throws Exception {
    jdbc.update("TRUNCATE TABLE platform_security.request_budget_bucket");
    ProductionProfileProperties properties = properties();
    properties.getLimits().setSseSubscriptionsPerMinute(1);
    ProductionRequestAbuseFilter firstInstance = newFilter(properties);
    ProductionRequestAbuseFilter secondInstance = newFilter(properties);
    ActorReference actor = actor("SYN-SSE-OPERATOR-1");

    assertThat(invokeSse(firstInstance, actor, "192.0.2.22").getStatus()).isEqualTo(200);
    assertThat(invokeSse(secondInstance, actor, "198.51.100.22").getStatus()).isEqualTo(429);
  }

  private ProductionRequestAbuseFilter newFilter(ProductionProfileProperties properties) {
    return new ProductionRequestAbuseFilter(
        properties,
        new JdbcRequestBudgetStore(
            new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource)));
  }

  private static MockHttpServletResponse invoke(
      ProductionRequestAbuseFilter filter,
      ActorReference actor,
      String organizationId,
      String remoteAddress)
      throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));
    try {
      MockHttpServletRequest request =
          new MockHttpServletRequest("GET", "/api/v1/directory/SYN-FACILITY-A/summary");
      request.setRequestURI("/api/v1/directory/SYN-FACILITY-A/summary");
      request.setRemoteAddr(remoteAddress);
      request.addHeader("X-AyuVahak-Execution-Scope", "ORGANIZATION");
      request.addHeader("X-AyuVahak-Organization-Id", organizationId);
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, (req, res) -> {});
      return response;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static MockHttpServletResponse invokeFacilityScope(
      ProductionRequestAbuseFilter filter,
      ActorReference actor,
      String organizationId,
      String facilityId,
      String remoteAddress)
      throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));
    try {
      MockHttpServletRequest request =
          new MockHttpServletRequest("GET", "/api/v1/directory/SYN-FACILITY-A/summary");
      request.setRequestURI("/api/v1/directory/SYN-FACILITY-A/summary");
      request.setRemoteAddr(remoteAddress);
      request.addHeader("X-AyuVahak-Execution-Scope", "FACILITY");
      request.addHeader("X-AyuVahak-Organization-Id", organizationId);
      request.addHeader("X-AyuVahak-Facility-Id", facilityId);
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, (req, res) -> {});
      return response;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static MockHttpServletResponse invokeRoute(
      ProductionRequestAbuseFilter filter, ActorReference actor, String path, String remoteAddress)
      throws Exception {
    return invokeRoute(filter, actor, "POST", path, remoteAddress);
  }

  private static MockHttpServletResponse invokeRoute(
      ProductionRequestAbuseFilter filter,
      ActorReference actor,
      String method,
      String path,
      String remoteAddress)
      throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));
    try {
      MockHttpServletRequest request = new MockHttpServletRequest(method, path);
      request.setRequestURI(path);
      request.setRemoteAddr(remoteAddress);
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, (req, res) -> {});
      return response;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static MockHttpServletResponse invokeAnonymous(
      ProductionRequestAbuseFilter filter, String remoteAddress) throws Exception {
    SecurityContextHolder.clearContext();
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/system-info");
    request.setRequestURI("/api/v1/system-info");
    request.setRemoteAddr(remoteAddress);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {});
    return response;
  }

  private static MockHttpServletResponse invokeSse(
      ProductionRequestAbuseFilter filter, ActorReference actor, String remoteAddress)
      throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new ActorAuthentication(actor));
    try {
      MockHttpServletRequest request =
          new MockHttpServletRequest("GET", "/api/v1/notifications/stream");
      request.setRequestURI("/api/v1/notifications/stream");
      request.setRemoteAddr(remoteAddress);
      MockHttpServletResponse response = new MockHttpServletResponse();
      filter.doFilter(request, response, (req, res) -> {});
      return response;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static ActorReference actor(String id) {
    return ActorReference.human(new IssuerId("https://identity.example.test/issuer"), id);
  }

  private static ProductionProfileProperties properties() {
    ProductionProfileProperties properties = new ProductionProfileProperties();
    ProductionProfileProperties.Limits limits = new ProductionProfileProperties.Limits();
    limits.setPrincipalRequestsPerMinute(5);
    limits.setScopeRequestsPerMinute(100);
    limits.setPartnerRequestsPerMinute(100);
    limits.setAnonymousRequestsPerMinute(100);
    limits.setAuthenticatedReadRequestsPerMinute(100);
    limits.setSensitiveWriteRequestsPerMinute(100);
    limits.setAdminRequestsPerMinute(100);
    limits.setSseConnectionsPerPrincipal(4);
    limits.setSseConnectionsTotal(100);
    limits.setSseSubscriptionsPerMinute(100);
    limits.setFederationRequestsPerMinute(100);
    limits.setExchangeRequestsPerMinute(100);
    properties.setLimits(limits);
    return properties;
  }
}
