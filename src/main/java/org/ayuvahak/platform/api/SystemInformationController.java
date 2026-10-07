package org.ayuvahak.platform.api;

import org.ayuvahak.platform.configuration.PlatformProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system-info")
public class SystemInformationController {

  private final PlatformProperties platformProperties;
  private final BuildProperties buildProperties;

  public SystemInformationController(
      PlatformProperties platformProperties, BuildProperties buildProperties) {
    this.platformProperties = platformProperties;
    this.buildProperties = buildProperties;
  }

  @GetMapping
  public SystemInformation systemInformation() {
    return new SystemInformation(platformProperties.serviceName(), 1, buildProperties.getVersion());
  }

  public record SystemInformation(
      String serviceName, int apiMajorVersion, String applicationVersion) {}
}
