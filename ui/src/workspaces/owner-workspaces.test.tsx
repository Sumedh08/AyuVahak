import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { type OwnerApiClient, OwnerApiError } from "../api/owner";
import type { SessionProjection } from "../api/session";
import { CapacityWorkspace } from "./CapacityWorkspace";
import { DirectoryWorkspace } from "./DirectoryWorkspace";

const organizationId = "6d383f64-9f14-4cf6-a8a1-3e119e8fc882";
const facilityId = "9eb1d3e6-86c4-45aa-b6bb-e15596ee05ec";
const session: SessionProjection = {
  subject: {
    kind: "HUMAN",
    issuer: "https://identity.synthetic.invalid/issuer",
    identifierType: "subject",
    identifier: "SYN-OPERATOR-001",
  },
  capabilities: [
    {
      authorityCode: "DirectoryRead",
      scopes: [
        {
          kind: "FACILITY",
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
    },
    {
      authorityCode: "CapacityEvidenceReview",
      scopes: [
        {
          kind: "FACILITY",
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
    },
  ],
};

const directoryPage = {
  mode: "DIRECTORY_FILTERED" as const,
  facilities: [
    {
      facilityId,
      managingOrganizationId: organizationId,
      facilityName: "Synthetic Facility A",
      status: "ACTIVE",
      version: 1,
    },
  ],
  unlocated: [],
  page: 0,
  size: 25,
  hasMore: false,
  hasMoreUnlocated: false,
};

function capacityProjection(freshness: "UNKNOWN" | "STALE" | "CURRENT", quantity: number | null) {
  return {
    facilityId,
    evaluatedAt: "2026-10-21T10:00:00Z",
    dimensions: [
      {
        dimensionCode: "SYN_BEDS",
        freshness,
        dimensionVersion: freshness === "UNKNOWN" ? null : 1,
        policyVersion: freshness === "UNKNOWN" ? null : 1,
        projectionVersion: "capacity-projection-v1",
        valueKind: quantity === null ? null : "QUANTITY",
        quantity,
        state: null,
        unit: quantity === null ? null : "beds",
        evidence: [],
        fallbackUsed: false,
        reasonCodes: freshness === "UNKNOWN" ? ["CAPACITY_EVIDENCE_UNKNOWN"] : [],
      },
    ],
  };
}

function apiClient(overrides: Partial<OwnerApiClient> = {}): OwnerApiClient {
  return {
    loadDirectoryFacilities: vi.fn().mockResolvedValue(directoryPage),
    loadCurrentCapacity: vi.fn().mockResolvedValue(capacityProjection("UNKNOWN", null)),
    ...overrides,
  };
}

describe("read-only Directory workspace", () => {
  it("shows only facilities returned by the scoped owner API", async () => {
    const api = apiClient();
    render(
      <DirectoryWorkspace session={session} getAccessToken={async () => "token"} apiClient={api} />,
    );
    expect(await screen.findByRole("cell", { name: organizationId })).toBeInTheDocument();
    expect(screen.getByText("Synthetic Facility A")).toBeInTheDocument();
    expect(api.loadDirectoryFacilities).toHaveBeenCalledWith(
      { key: `FACILITY:${facilityId}`, kind: "FACILITY", organizationId, facilityId },
      0,
      25,
      expect.any(AbortSignal),
    );
  });

  it("distinguishes an owner-returned empty page from unavailable data", async () => {
    const api = apiClient({
      loadDirectoryFacilities: vi.fn().mockResolvedValue({ ...directoryPage, facilities: [] }),
    });
    render(
      <DirectoryWorkspace session={session} getAccessToken={async () => "token"} apiClient={api} />,
    );
    expect(
      await screen.findByRole("heading", { name: "No facilities were returned" }),
    ).toBeInTheDocument();
  });

  it("shows denial without rendering any cached or assumed records", async () => {
    const api = apiClient({
      loadDirectoryFacilities: vi.fn().mockRejectedValue(new OwnerApiError(403, "FORBIDDEN")),
    });
    render(
      <DirectoryWorkspace session={session} getAccessToken={async () => "token"} apiClient={api} />,
    );
    expect(
      await screen.findByRole("heading", { name: "Directory access was denied" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("Synthetic Facility A")).not.toBeInTheDocument();
  });
});

describe("read-only Capacity workspace", () => {
  it("shows an explicit loading state while current evidence is requested", async () => {
    let resolveRequest: ((value: ReturnType<typeof capacityProjection>) => void) | undefined;
    const api = apiClient({
      loadCurrentCapacity: vi.fn().mockImplementation(
        () =>
          new Promise((resolve) => {
            resolveRequest = resolve;
          }),
      ),
    });
    render(
      <CapacityWorkspace session={session} getAccessToken={async () => "token"} apiClient={api} />,
    );
    fireEvent.change(screen.getByLabelText("Configured dimension code"), {
      target: { value: "SYN_BEDS" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check current evidence" }));
    expect(screen.getByRole("status")).toHaveTextContent(/loading current capacity evidence/i);
    resolveRequest?.(capacityProjection("UNKNOWN", null));
    expect(await screen.findByRole("heading", { name: "Unknown evidence" })).toBeInTheDocument();
  });

  it("keeps UNKNOWN distinct from a numeric zero", async () => {
    const api = apiClient({
      loadCurrentCapacity: vi.fn().mockResolvedValue(capacityProjection("UNKNOWN", null)),
    });
    render(
      <CapacityWorkspace session={session} getAccessToken={async () => "token"} apiClient={api} />,
    );
    fireEvent.change(screen.getByLabelText("Configured dimension code"), {
      target: { value: "SYN_BEDS" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check current evidence" }));
    expect(await screen.findByText("No value reported")).toBeInTheDocument();
    expect(screen.getByText(/not treated as zero/i)).toBeInTheDocument();
  });

  it("shows stale evidence and preserves a current numeric zero", async () => {
    const api = apiClient({
      loadCurrentCapacity: vi.fn().mockResolvedValue(capacityProjection("STALE", 0)),
    });
    render(
      <CapacityWorkspace session={session} getAccessToken={async () => "token"} apiClient={api} />,
    );
    fireEvent.change(screen.getByLabelText("Configured dimension code"), {
      target: { value: "SYN_BEDS" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Check current evidence" }));
    expect(await screen.findByRole("heading", { name: "Stale evidence" })).toBeInTheDocument();
    expect(screen.getByText("0 beds")).toBeInTheDocument();
    expect(screen.getByText(/evidence is stale/i)).toBeInTheDocument();
  });

  it("does not request or display Capacity without a recognized owner scope", async () => {
    const api = apiClient();
    const noCapacity: SessionProjection = { ...session, capabilities: [] };
    render(
      <CapacityWorkspace
        session={noCapacity}
        getAccessToken={async () => "token"}
        apiClient={api}
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { name: "Capacity review scope is not available" }),
      ).toBeInTheDocument(),
    );
    expect(api.loadCurrentCapacity).not.toHaveBeenCalled();
  });
});
