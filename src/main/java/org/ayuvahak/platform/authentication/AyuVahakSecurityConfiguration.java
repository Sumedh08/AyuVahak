package org.ayuvahak.platform.authentication;

import java.time.Duration;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.core.IssuerId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(AuthenticationProperties.class)
public class AyuVahakSecurityConfiguration {

  @Bean
  SecurityFilterChain ayuVahakSecurityFilterChain(
      HttpSecurity http,
      AuthenticationEntryPoint problemAuthenticationEntryPoint,
      AccessDeniedHandler problemAccessDeniedHandler,
      Converter<Jwt, ActorAuthentication> actorAuthenticationConverter)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers("/api/v1/system-info", "/actuator/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(problemAuthenticationEntryPoint)
                    .accessDeniedHandler(problemAccessDeniedHandler))
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .authenticationEntryPoint(problemAuthenticationEntryPoint)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(actorAuthenticationConverter)));
    return http.build();
  }

  @Bean
  JwtDecoder ayuVahakJwtDecoder(AuthenticationProperties properties) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(2));
    requestFactory.setReadTimeout(Duration.ofSeconds(2));
    RestOperations jwkSetClient = new RestTemplate(requestFactory);
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .restOperations(jwkSetClient)
            .build();
    OAuth2TokenValidator<Jwt> audienceValidator =
        jwt ->
            jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "The token audience is invalid.", null));
    OAuth2TokenValidator<Jwt> keyIdValidator =
        jwt ->
            jwt.getHeaders().get("kid") instanceof String keyId && !keyId.isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(
                        "invalid_token", "The token signing key identifier is missing.", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.issuer()),
            audienceValidator,
            keyIdValidator));

    return token -> {
      try {
        return decoder.decode(token);
      } catch (JwtException exception) {
        if (containsRemoteKeySourceFailure(exception)) {
          throw new TrustedKeySourceUnavailableException(exception);
        }
        throw exception;
      }
    };
  }

  @Bean
  Converter<Jwt, ActorAuthentication> actorAuthenticationConverter(
      AuthenticationProperties properties) {
    TokenPrincipalResolver resolver =
        new TokenPrincipalResolver(
            new IssuerId(properties.issuer()), properties.serviceClientIds());
    return jwt -> {
      try {
        return new ActorAuthentication(resolver.resolve(jwt.getClaims()));
      } catch (InvalidAccessTokenException exception) {
        throw new BadCredentialsException("The access token principal is invalid.");
      }
    };
  }

  @Bean
  AuthenticationEntryPoint problemAuthenticationEntryPoint(ObjectMapper objectMapper) {
    return (request, response, exception) -> {
      boolean keySourceUnavailable =
          hasCause(exception, TrustedKeySourceUnavailableException.class);
      HttpStatus status =
          keySourceUnavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED;
      PlatformErrorCode code =
          keySourceUnavailable
              ? PlatformErrorCode.AUTHENTICATION_UNAVAILABLE
              : PlatformErrorCode.AUTHENTICATION_REQUIRED;
      String title =
          keySourceUnavailable
              ? "Authentication temporarily unavailable"
              : "Authentication required";
      String detail =
          keySourceUnavailable
              ? "The access token could not be verified because trusted signing keys are temporarily unavailable."
              : "A valid AyuVahak access token is required.";
      writeProblem(request, response, objectMapper, status, code, title, detail);
      if (status == HttpStatus.UNAUTHORIZED) {
        response.setHeader("WWW-Authenticate", "Bearer");
      }
    };
  }

  @Bean
  AccessDeniedHandler problemAccessDeniedHandler(ObjectMapper objectMapper) {
    return (request, response, exception) ->
        writeProblem(
            request,
            response,
            objectMapper,
            HttpStatus.FORBIDDEN,
            PlatformErrorCode.AUTHORIZATION_DENIED,
            "Authorization denied",
            "The authenticated principal is not authorized for this operation.");
  }

  private static void writeProblem(
      jakarta.servlet.http.HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response,
      ObjectMapper objectMapper,
      HttpStatus status,
      PlatformErrorCode code,
      String title,
      String detail)
      throws java.io.IOException {
    String correlationId = response.getHeader(CorrelationIdFilter.HEADER_NAME);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = "unavailable";
    }
    org.springframework.http.ProblemDetail problem =
        org.springframework.http.ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setProperty("code", code.name());
    problem.setProperty("correlationId", correlationId);
    response.setStatus(status.value());
    response.setContentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }

  private static boolean containsRemoteKeySourceFailure(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current.getClass().getName().equals("com.nimbusds.jose.RemoteKeySourceException")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
    Throwable current = throwable;
    while (current != null) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
