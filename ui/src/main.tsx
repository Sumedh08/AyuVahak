import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { createOidcAuthClient, oidcConfigurationFromEnvironment } from "./auth/oidc";
import "./styles.css";

const rootElement = document.getElementById("root");
if (!rootElement) throw new Error("Application root is missing.");

const authClient = createOidcAuthClient(oidcConfigurationFromEnvironment(import.meta.env));
createRoot(rootElement).render(
  <StrictMode>
    <App authClient={authClient} />
  </StrictMode>,
);
