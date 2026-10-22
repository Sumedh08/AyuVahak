/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_OIDC_SCOPE?: string;
  readonly VITE_EXPECTED_APPLICATION_ORIGIN?: string;
}

interface Window {
  ayuvahakRuntimeConfig?: {
    readonly oidcAuthority?: string;
    readonly oidcClientId?: string;
    readonly oidcScope?: string;
    readonly applicationOrigin?: string;
  };
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
