import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { SessionProjection } from "../api/session";
import type { TransferApiClient, TransferSummary } from "../api/transfer";
import { TransferWorkspace } from "./TransferWorkspace";
import { TransportWorkspace } from "./TransportWorkspace";

const organizationId = "11111111-1111-4111-8111-111111111111";
const facilityId = "22222222-2222-4222-8222-222222222222";
const otherFacilityId = "33333333-3333-4333-8333-333333333333";
const transferId = "44444444-4444-4444-8444-444444444444";

function session(
  capabilities = ["TransferViewAuthority", "TransportStatusAuthority"],
  scopeKind: "ORGANIZATION" | "FACILITY" = "FACILITY",
): SessionProjection {
  return {
    deploymentMode: "EMPTY",
    subject: {
      kind: "HUMAN",
      issuer: "https://identity.synthetic.invalid/issuer",
      identifierType: "subject",
      identifier: "SYN-OPERATOR-001",
    },
    capabilities: capabilities.map((authorityCode) => ({
      authorityCode,
      scopes:
        scopeKind === "ORGANIZATION"
          ? [
              {
                kind: "ORGANIZATION" as const,
                organization: {
                  issuer: "urn:ayuvahak:directory",
                  identifierType: "Organization",
                  identifier: organizationId,
                },
              },
            ]
          : [
              {
                kind: "FACILITY" as const,
                organization: {
                  issuer: "urn:ayuvahak:directory",
                  identifierType: "Organization",
                  identifier: organizationId,
                },
                facility: {
                  issuer: "urn:ayuvahak:directory",
                  identifierType: "Facility",
                  identifier: facilityId,
                },
              },
            ],
    })),
  };
}

function transfer(status: string, reservationOutcome: string | null): TransferSummary {
  return {
    transferId,
    journeyId: "55555555-5555-4555-8555-555555555555",
    requestAttemptId: "66666666-6666-4666-8666-666666666666",
    referralId: "77777777-7777-4777-8777-777777777777",
    originOrganizationId: organizationId,
    originFacilityId: facilityId,
    receivingOrganizationId: organizationId,
    receivingFacilityId: otherFacilityId,
    status,
    version: 2,
    createdAt: "2026-10-20T10:00:00Z",
    reservationOutcome,
  };
}

function client(overrides: Partial<TransferApiClient> = {}): TransferApiClient {
  return {
    loadTransfers: vi.fn().mockResolvedValue({
      items: [transfer("AWAITING_RESERVATION", "UNKNOWN")],
      page: 0,
      size: 50,
      hasMore: false,
    }),
    loadIncoming: vi.fn().mockResolvedValue({ items: [], page: 0, size: 50, hasMore: false }),
    loadTransportRequests: vi.fn().mockResolvedValue({
      items: [
        {
          transportRequestId: "88888888-8888-4888-8888-888888888888",
          transferId,
          originFacilityId: facilityId,
          destinationFacilityId: otherFacilityId,
          status: "ARRIVED",
          requestedAt: "2026-10-20T11:00:00Z",
          version: 3,
        },
      ],
      page: 0,
      size: 50,
      hasMore: false,
    }),
    ...overrides,
  };
}

describe("Transfer and Transport workspaces", () => {
  it("keeps Transfer state separate from reservation outcome", async () => {
    const api = client();
    render(
      <TransferWorkspace
        session={session()}
        getAccessToken={async () => "token"}
        apiClient={api}
        view="transfers"
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Load Transfers" }));

    expect(await screen.findByRole("cell", { name: "AWAITING_RESERVATION" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "UNKNOWN" })).toBeInTheDocument();
    expect(api.loadTransfers).toHaveBeenCalledWith(
      { key: `FACILITY:${facilityId}`, kind: "FACILITY", organizationId, facilityId },
      0,
      50,
    );
  });

  it("shows a facility-scoped receiving queue without implying acceptance", async () => {
    const api = client({
      loadIncoming: vi.fn().mockResolvedValue({
        items: [
          {
            requestAttemptId: transferId,
            journeyId: "55555555-5555-4555-8555-555555555555",
            referralId: "77777777-7777-4777-8777-777777777777",
            originOrganizationId: organizationId,
            originFacilityId: otherFacilityId,
            receivingFacilityId: facilityId,
            requestedAt: "2026-10-20T10:00:00Z",
            expiresAt: "2026-10-20T12:00:00Z",
          },
        ],
        page: 0,
        size: 50,
        hasMore: false,
      }),
    });
    render(
      <TransferWorkspace
        session={session()}
        getAccessToken={async () => "token"}
        apiClient={api}
        view="receiving"
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Load pending requests" }));

    expect(await screen.findByRole("rowheader", { name: transferId })).toBeInTheDocument();
    expect(screen.getByText(/Acceptance and reservation are separate states/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /accept/i })).not.toBeInTheDocument();
  });

  it("keeps Transport request state distinct from Transfer state", async () => {
    const api = client({
      loadTransfers: vi.fn().mockResolvedValue({
        items: [transfer("IN_TRANSIT", null)],
        page: 0,
        size: 50,
        hasMore: false,
      }),
    });
    render(
      <TransportWorkspace
        session={session()}
        getAccessToken={async () => "token"}
        apiClient={api}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Load visible Transfers" }));
    await waitFor(() => expect(screen.getByLabelText("Visible Transfer")).toHaveValue(transferId));
    fireEvent.click(screen.getByRole("button", { name: "Load Transport requests" }));

    expect(await screen.findByRole("cell", { name: "ARRIVED" })).toBeInTheDocument();
    expect(
      screen.getByText(/arrival observation does not mean handover is complete/),
    ).toBeInTheDocument();
    expect(api.loadTransportRequests).toHaveBeenCalledWith(
      { key: `FACILITY:${facilityId}`, kind: "FACILITY", organizationId, facilityId },
      transferId,
      0,
      50,
    );
  });

  it("does not load a receiving queue for an organization-only grant", () => {
    const api = client();
    render(
      <TransferWorkspace
        session={session(["TransferViewAuthority"], "ORGANIZATION")}
        getAccessToken={async () => "token"}
        apiClient={api}
        view="receiving"
      />,
    );
    expect(screen.getByText("Receiving facility scope is not available")).toBeInTheDocument();
    expect(api.loadIncoming).not.toHaveBeenCalled();
  });
});
