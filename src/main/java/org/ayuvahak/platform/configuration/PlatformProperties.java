package org.ayuvahak.platform.configuration;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ayuvahak.platform")
public record PlatformProperties(@NotBlank String serviceName) {}
