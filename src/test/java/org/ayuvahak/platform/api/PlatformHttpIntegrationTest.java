package org.ayuvahak.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PlatformHttpIntegrationTest.AyuVahakHttpTestConfiguration.class)
class PlatformHttpIntegrationTest {

  private static final String CORRELATION_HEADER = "X-Correlation-ID";

  @Autowired private MockMvc mockMvc;

  @AfterEach
  void requestCorrelationIsRemovedFromTheTestThread() {
    org.assertj.core.api.Assertions.assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void exposesOnlyNonSensitivePlatformInformationWithGeneratedCorrelation() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/system-info"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.serviceName").value("AyuVahak"))
            .andExpect(jsonPath("$.apiMajorVersion").value(1))
            .andExpect(jsonPath("$.applicationVersion").value("0.1.0-SNAPSHOT"))
            .andExpect(
                header()
                    .string(
                        CORRELATION_HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
            .andReturn();

    Map<String, Object> responseBody =
        JsonPath.<Map<String, Object>>read(result.getResponse().getContentAsString(), "$");
    assertThat(responseBody)
        .containsOnlyKeys("serviceName", "apiMajorVersion", "applicationVersion");
  }

  @Test
  void acceptsSafeCorrelationIdAndMakesItAvailableInRequestLoggingContext() throws Exception {
    mockMvc
        .perform(get("/__test/correlation-context").header(CORRELATION_HEADER, "corr-42.worker_1"))
        .andExpect(status().isOk())
        .andExpect(header().string(CORRELATION_HEADER, "corr-42.worker_1"))
        .andExpect(content().string("corr-42.worker_1"));
  }

  @Test
  void replacesUnsafeCorrelationIdBeforeItCanReachTheResponseOrLoggingContext() throws Exception {
    mockMvc
        .perform(get("/__test/correlation-context").header(CORRELATION_HEADER, "bad value\nforged"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(CORRELATION_HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
        .andExpect(content().string(org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  void replacesAmbiguousDuplicateCorrelationHeaders() throws Exception {
    mockMvc
        .perform(get("/__test/correlation-context").header(CORRELATION_HEADER, "first", "second"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(CORRELATION_HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  void returnsSafeProblemDetailsForValidationFailures() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/__test/validated")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"value\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty())
            .andExpect(jsonPath("$.validationErrors[0].field").value("value"))
            .andReturn();
    assertProblemCorrelationMatchesResponse(result);
  }

  @Test
  void mapsMalformedPayloadsToSafeProblemDetails() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/__test/validated")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty())
            .andReturn();
    assertProblemCorrelationMatchesResponse(result);
  }

  @Test
  void mapsUnsupportedMediaTypesToSafeProblemDetails() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/__test/validated")
                    .contentType(MediaType.APPLICATION_XML)
                    .content("<value>synthetic</value>"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty())
            .andReturn();
    assertProblemCorrelationMatchesResponse(result);
  }

  @Test
  void hidesUnexpectedExceptionDetailsBehindSafeProblemDetails() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/__test/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
            .andExpect(jsonPath("$.correlationId").isNotEmpty())
            .andReturn();

    assertProblemCorrelationMatchesResponse(result);
    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("sensitive SQL")
        .doesNotContain("secret detail")
        .doesNotContain("IllegalStateException");
  }

  @Test
  void mapsUnknownProductionRoutesToSafeNotFoundProblemDetails() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/unknown"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andReturn();

    assertProblemCorrelationMatchesResponse(result);
  }

  @Test
  void mapsUnsupportedMethodsToSafeProblemDetailsAndPreservesAllowHeader() throws Exception {
    MvcResult result =
        mockMvc
            .perform(post("/api/v1/system-info"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
            .andReturn();

    assertProblemCorrelationMatchesResponse(result);
  }

  private static void assertProblemCorrelationMatchesResponse(MvcResult result) throws Exception {
    String responseCorrelationId = result.getResponse().getHeader(CORRELATION_HEADER);
    String problemCorrelationId =
        JsonPath.read(result.getResponse().getContentAsString(), "$.correlationId");
    assertThat(problemCorrelationId).isEqualTo(responseCorrelationId);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class AyuVahakHttpTestConfiguration {

    @Bean
    HttpTestSupportController httpTestSupportController() {
      return new HttpTestSupportController();
    }
  }

  @RestController
  static class HttpTestSupportController {

    @GetMapping("/__test/correlation-context")
    String correlationContext() {
      return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    @PostMapping("/__test/validated")
    void validate(@Valid @RequestBody SyntheticRequest request) {}

    @GetMapping("/__test/unexpected")
    void failUnexpectedly() {
      throw new IllegalStateException("sensitive SQL and secret detail");
    }
  }

  record SyntheticRequest(@NotBlank String value) {}
}
