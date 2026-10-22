import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { createOidcAuthClient, oidcConfigurationFromEnvironment } from "./auth/oidc";
import "./styles.css";

const rootElement = document.getElementById("root");
if (!rootElement) throw new Error("Application root is missing.");

const runtimeAuth = window.ayuvahakRuntimeConfig;
const authEnvironment = {
  ...import.meta.env,
  ...(runtimeAuth?.oidcAuthority ? { VITE_OIDC_AUTHORITY: runtimeAuth.oidcAuthority } : {}),
  ...(runtimeAuth?.oidcClientId ? { VITE_OIDC_CLIENT_ID: runtimeAuth.oidcClientId } : {}),
  ...(runtimeAuth?.oidcScope ? { VITE_OIDC_SCOPE: runtimeAuth.oidcScope } : {}),
  ...(runtimeAuth?.applicationOrigin
    ? { VITE_EXPECTED_APPLICATION_ORIGIN: runtimeAuth.applicationOrigin }
    : {}),
};
const authClient = createOidcAuthClient(oidcConfigurationFromEnvironment(authEnvironment));
createRoot(rootElement).render(
  <StrictMode>
    <App authClient={authClient} />
  </StrictMode>,
);
