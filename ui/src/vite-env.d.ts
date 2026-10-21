/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_OIDC_SCOPE?: string;
}

interface Window {
  ayuvahakRuntimeConfig?: {
    readonly oidcAuthority?: string;
    readonly oidcClientId?: string;
    readonly oidcScope?: string;
  };
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
