package org.ayuvahak.platform.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the public, static browser shell; protected data remains behind authenticated APIs. */
@Controller
class ApplicationShellController {

  @GetMapping({
    "/",
    "/auth/callback",
    "/directory",
    "/directory/**",
    "/capacity",
    "/capacity/**",
    "/referrals",
    "/referrals/**",
    "/matching",
    "/matching/**",
    "/transfers",
    "/transfers/**",
    "/receiving",
    "/receiving/**",
    "/transport",
    "/transport/**",
    "/analytics",
    "/analytics/**",
    "/administration",
    "/administration/**",
    "/operator-support",
    "/operator-support/**"
  })
  String applicationShell() {
    return "forward:/index.html";
  }
}
