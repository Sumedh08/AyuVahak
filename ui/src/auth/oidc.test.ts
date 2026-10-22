import { beforeEach, describe, expect, it } from "vitest";
import {
  createOidcSettings,
  type OidcConfiguration,
  oidcConfigurationFromEnvironment,
} from "./oidc";

const configuration: OidcConfiguration = {
  authority: "http://localhost:8081/realms/ayuvahak-local",
  clientId: "ayuvahak-browser",
  scope: "openid profile ayuvahak-api",
  expectedApplicationOrigin: window.location.origin,
};

describe("OIDC public-client configuration", () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it("uses authorization code with PKCE and keeps the user token store in memory", async () => {
    const settings = createOidcSettings(configuration, window);
    expect(settings).not.toBeNull();
    expect(settings).toMatchObject({
      authority: configuration.authority,
      client_id: configuration.clientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      response_type: "code",
      disablePKCE: false,
      automaticSilentRenew: false,
      fetchRequestCredentials: "omit",
    });
    expect(settings).not.toHaveProperty("client_secret");

    const store = settings?.userStore;
    expect(store).toBeDefined();
    await store?.set("synthetic-user", "synthetic-access-token");
    expect(await store?.get("synthetic-user")).toBe("synthetic-access-token");
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it("uses session storage only for temporary OIDC protocol state", async () => {
    const settings = createOidcSettings(configuration, window);
    const stateStore = settings?.stateStore;
    expect(stateStore).toBeDefined();
    await stateStore?.set("synthetic-oidc-state", "temporary-code-verifier");
    expect(window.sessionStorage.length).toBeGreaterThan(0);
    expect(window.localStorage.length).toBe(0);
  });

  it.each([
    { ...configuration, authority: "http://example.invalid/issuer" },
    { ...configuration, authority: "https://user:secret@example.invalid/issuer" },
    { ...configuration, scope: "profile" },
    { ...configuration, clientId: "" },
    { ...configuration, expectedApplicationOrigin: "https://unexpected.example" },
    { ...configuration, expectedApplicationOrigin: `${window.location.origin}/path` },
  ])("fails closed for an unsafe or incomplete provider configuration", (candidate) => {
    expect(createOidcSettings(candidate, window)).toBeNull();
  });

  it("requires an explicitly configured expected application origin", () => {
    expect(
      oidcConfigurationFromEnvironment({
        VITE_OIDC_AUTHORITY: configuration.authority,
        VITE_OIDC_CLIENT_ID: configuration.clientId,
        VITE_OIDC_SCOPE: configuration.scope,
      }),
    ).toBeNull();
    expect(
      oidcConfigurationFromEnvironment({
        VITE_OIDC_AUTHORITY: configuration.authority,
        VITE_OIDC_CLIENT_ID: configuration.clientId,
        VITE_OIDC_SCOPE: configuration.scope,
        VITE_EXPECTED_APPLICATION_ORIGIN: configuration.expectedApplicationOrigin,
      }),
    ).toEqual(configuration);
  });
});
