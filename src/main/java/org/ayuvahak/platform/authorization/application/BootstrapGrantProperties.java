package org.ayuvahak.platform.authorization.application;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ayuvahak.bootstrap")
public record BootstrapGrantProperties(
    @NotBlank String operatorIssuer,
    @NotBlank String operatorReference,
    @NotBlank String granteeIssuer,
    @NotBlank String granteeKind,
    @NotBlank String granteeIdentifier,
    @NotBlank String scope,
    @NotBlank String confirmation) {}
