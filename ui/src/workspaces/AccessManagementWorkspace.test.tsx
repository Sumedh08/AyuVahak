import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { type AccessGrantApiClient, AccessGrantApiError } from "../api/access-grants";
import type { SessionProjection } from "../api/session";
import { AccessManagementWorkspace } from "./AccessManagementWorkspace";

const session: SessionProjection = {
  subject: {
    kind: "HUMAN",
    issuer: "https://identity.example.test/realms/ayuvahak",
    identifierType: "subject",
    identifier: "synthetic-admin",
  },
  capabilities: [{ authorityCode: "AccessGrantAdministration", scopes: [{ kind: "PLATFORM" }] }],
  deploymentMode: "EMPTY",
};

describe("AccessManagementWorkspace", () => {
  it("offers a read-only template for the optional operator-support profile status", async () => {
    const apiClient: AccessGrantApiClient = {
      list: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      create: vi.fn().mockResolvedValue(undefined),
      revoke: vi.fn().mockResolvedValue(undefined),
    };
    render(
      <AccessManagementWorkspace
        session={session}
        getAccessToken={async () => "unused"}
        apiClient={apiClient}
      />,
    );

    fireEvent.change(screen.getByLabelText("Access preset"), {
      target: { value: "operator-support-reader" },
    });
    fireEvent.change(screen.getByLabelText("IdP subject"), {
      target: { value: "synthetic-support-operator" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Apply template grants" }));

    await waitFor(() => expect(apiClient.create).toHaveBeenCalledTimes(1));
    expect(apiClient.create).toHaveBeenCalledWith(
      {
        kind: "HUMAN",
        issuer: session.subject.issuer,
        subject: "synthetic-support-operator",
      },
      "OperatorSupportRead",
      { kind: "PLATFORM" },
    );
    expect(
      await screen.findByText("Created 1 separate grants from “Operator support reader”."),
    ).toBeTruthy();
  });

  it("expands a selected preset into backend-authorized grant requests", async () => {
    const apiClient: AccessGrantApiClient = {
      list: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      create: vi.fn().mockResolvedValue(undefined),
      revoke: vi.fn().mockResolvedValue(undefined),
    };
    render(
      <AccessManagementWorkspace
        session={session}
        getAccessToken={async () => "unused"}
        apiClient={apiClient}
      />,
    );

    fireEvent.change(screen.getByLabelText("Access preset"), {
      target: { value: "platform-access-administrator" },
    });
    fireEvent.change(screen.getByLabelText("IdP subject"), {
      target: { value: "synthetic-new-operator" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Apply template grants" }));

    await waitFor(() => expect(apiClient.create).toHaveBeenCalledTimes(1));
    expect(apiClient.create).toHaveBeenCalledWith(
      {
        kind: "HUMAN",
        issuer: session.subject.issuer,
        subject: "synthetic-new-operator",
      },
      "AccessGrantAdministration",
      { kind: "PLATFORM" },
    );
    expect(
      await screen.findByText("Created 1 separate grants from “Platform access administrator”."),
    ).toBeTruthy();
    expect(
      screen.getByText(
        /User accounts and authentication remain with the configured identity provider/,
      ),
    ).toBeTruthy();
  });

  it("does not show grant records or assume privileges when the API denies access", async () => {
    const apiClient: AccessGrantApiClient = {
      list: vi.fn().mockRejectedValue(new AccessGrantApiError(403, "FORBIDDEN")),
      create: vi.fn(),
      revoke: vi.fn(),
    };
    render(
      <AccessManagementWorkspace
        session={session}
        getAccessToken={async () => "unused"}
        apiClient={apiClient}
      />,
    );

    expect(await screen.findByText("Grant administration was denied")).toBeTruthy();
    expect(screen.queryByText("No grants were returned for this page.")).toBeNull();
  });

  it("blocks self-grant attempts before they reach the grant API", async () => {
    const apiClient: AccessGrantApiClient = {
      list: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      create: vi.fn(),
      revoke: vi.fn(),
    };
    render(
      <AccessManagementWorkspace
        session={session}
        getAccessToken={async () => "unused"}
        apiClient={apiClient}
      />,
    );
    fireEvent.change(screen.getByLabelText("IdP subject"), {
      target: { value: session.subject.identifier },
    });
    fireEvent.click(screen.getByRole("button", { name: "Apply template grants" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "The current operator cannot grant or change their own access.",
    );
    expect(apiClient.create).not.toHaveBeenCalled();
  });
});
