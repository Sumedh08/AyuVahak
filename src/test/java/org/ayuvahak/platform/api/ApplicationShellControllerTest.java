package org.ayuvahak.platform.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApplicationShellControllerTest {

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ApplicationShellController()).build();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/",
        "/auth/callback",
        "/directory",
        "/referrals/SYN-REF-001",
        "/operator-support"
      })
  void forwardsOnlyPresentationRoutesToTheStaticApplicationShell(String path) throws Exception {
    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(view().name("forward:/index.html"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/v1/referrals", "/actuator/env", "/unknown-route"})
  void doesNotMapApiOrUnrecognizedPathsToTheApplicationShell(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isNotFound());
  }

  @org.junit.jupiter.api.Test
  void doesNotForwardTheIndexResourceBackToItself() throws Exception {
    mockMvc.perform(get("/index.html")).andExpect(status().isNotFound());
  }
}
