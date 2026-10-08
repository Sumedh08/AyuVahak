package org.ayuvahak.platform.executioncontext;

/** Safe authentication-mechanism categories; this value never contains credentials or tokens. */
public enum AuthenticationMechanism {
  HUMAN_ACCESS_TOKEN,
  SERVICE_CLIENT_CREDENTIALS,
  CONFIGURED_SERVICE_IDENTITY,
  LOCAL_OPERATOR_CONTROL
}
