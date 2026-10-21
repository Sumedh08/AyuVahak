import {
  InMemoryWebStorage,
  type User,
  UserManager,
  type UserManagerSettings,
  WebStorageStateStore,
} from "oidc-client-ts";

export interface OidcConfiguration {
  readonly authority: string;
  readonly clientId: string;
  readonly scope: string;
}

export type OidcConfigurationResult =
  | { readonly status: "configured"; readonly manager: UserManager }
  | { readonly status: "missing" }
  | { readonly status: "invalid" };

export interface AuthClient {
  readonly configurationStatus: "configured" | "missing" | "invalid";
  initialize(): Promise<string | null>;
  getAccessToken(): Promise<string | null>;
  signIn(): Promise<void>;
  clear(): Promise<void>;
}

function safeAuthority(authority: string): boolean {
  try {
    const url = new URL(authority);
    const loopbackHost =
      url.hostname === "localhost" || url.hostname === "127.0.0.1" || url.hostname === "[::1]";
    return (
      (url.protocol === "https:" || (url.protocol === "http:" && loopbackHost)) &&
      url.username === "" &&
      url.password === "" &&
      url.search === "" &&
      url.hash === ""
    );
  } catch {
    return false;
  }
}

export function createOidcSettings(
  configuration: OidcConfiguration,
  browser: Pick<Window, "location" | "sessionStorage">,
): UserManagerSettings | null {
  const authority = configuration.authority.trim();
  const clientId = configuration.clientId.trim();
  const scope = configuration.scope.trim();

  if (
    !safeAuthority(authority) ||
    clientId.length === 0 ||
    clientId.length > 128 ||
    /\s/.test(clientId) ||
    !scope.split(/\s+/).includes("openid")
  ) {
    return null;
  }

  return {
    authority,
    client_id: clientId,
    redirect_uri: new URL("/auth/callback", browser.location.origin).toString(),
    post_logout_redirect_uri: browser.location.origin,
    response_type: "code",
    scope,
    disablePKCE: false,
    automaticSilentRenew: false,
    monitorSession: false,
    loadUserInfo: false,
    fetchRequestCredentials: "omit",
    requestTimeoutInSeconds: 10,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: browser.sessionStorage }),
  };
}

export function createOidcAuthClient(
  configuration: OidcConfiguration | null,
  browser: Window = window,
): AuthClient {
  if (!configuration) {
    return {
      configurationStatus: "missing",
      async initialize() {
        return null;
      },
      async getAccessToken() {
        return null;
      },
      async signIn() {
        throw new Error("Identity provider configuration is unavailable.");
      },
      async clear() {},
    };
  }

  const settings = createOidcSettings(configuration, browser);
  if (!settings) {
    return {
      configurationStatus: "invalid",
      async initialize() {
        return null;
      },
      async getAccessToken() {
        return null;
      },
      async signIn() {
        throw new Error("Identity provider configuration is invalid.");
      },
      async clear() {},
    };
  }

  const manager = new UserManager(settings);
  let initialization: Promise<string | null> | undefined;
  const callbackPath = "/auth/callback";

  function usableAccessToken(user: User | null): string | null {
    if (!user || user.expired || user.access_token.length === 0) return null;
    return user.access_token;
  }

  async function initialize(): Promise<string | null> {
    if (initialization) return initialization;
    initialization = (async () => {
      if (browser.location.pathname === callbackPath) {
        const user = await manager.signinRedirectCallback();
        browser.history.replaceState(null, "", "/");
        return usableAccessToken(user);
      }

      return usableAccessToken(await manager.getUser());
    })();
    return initialization;
  }

  return {
    configurationStatus: "configured",
    initialize,
    async getAccessToken() {
      return usableAccessToken(await manager.getUser());
    },
    async signIn() {
      await manager.signinRedirect();
    },
    async clear() {
      await manager.removeUser();
      initialization = Promise.resolve(null);
    },
  };
}

export function oidcConfigurationFromEnvironment(
  environment: ImportMetaEnv,
): OidcConfiguration | null {
  const authority = environment.VITE_OIDC_AUTHORITY?.trim();
  const clientId = environment.VITE_OIDC_CLIENT_ID?.trim();
  const scope = environment.VITE_OIDC_SCOPE?.trim() || "openid profile";
  if (!authority || !clientId) return null;
  return { authority, clientId, scope };
}
