import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { AnalyticsApiClient, AnalyticsSnapshotPage } from "../api/analytics";
import type { SessionProjection } from "../api/session";
import { AnalyticsWorkspace } from "./AnalyticsWorkspace";

const organizationId = "6d383f64-9f14-4cf6-a8a1-3e119e8fc882";
const session: SessionProjection = {
  deploymentMode: "EMPTY",
  subject: {
    kind: "HUMAN",
    issuer: "https://identity.synthetic.invalid/issuer",
    identifierType: "subject",
    identifier: "SYN-ANALYST-001",
  },
  capabilities: [
    {
      authorityCode: "AnalyticsAggregateRead",
      scopes: [
        {
          kind: "ORGANIZATION",
          organization: {
            issuer: "urn:ayuvahak:directory",
            identifierType: "Organization",
            identifier: organizationId,
          },
        },
      ],
    },
  ],
};

const page: AnalyticsSnapshotPage = {
  page: 0,
  size: 50,
  hasMore: false,
  items: [
    {
      snapshotId: "4d9a7e33-d9f5-450a-9cd7-3b689d9d1d88",
      indicatorKey: "SYN-INDICATOR-001",
      indicatorVersion: 1,
      periodStart: "2026-01-01",
      periodEndExclusive: "2026-02-01",
      timeZone: "UTC",
      completeness: "COMPLETE",
      value: "0",
      computedAt: "2026-02-01T00:00:00Z",
      revision: 1,
      revisionState: "PROVISIONAL",
      exportState: "ACKNOWLEDGED",
      exportResultClass: "IMPORTED",
    },
    {
      snapshotId: "310e8c70-bc09-4f76-b378-8eb45832f0bd",
      indicatorKey: "SYN-INDICATOR-002",
      indicatorVersion: 1,
      periodStart: "2026-01-01",
      periodEndExclusive: "2026-02-01",
      timeZone: "UTC",
      completeness: "MISSING_SOURCE",
      value: null,
      computedAt: "2026-02-01T00:00:00Z",
      revision: null,
      revisionState: null,
      exportState: "OUTCOME_UNKNOWN",
      exportResultClass: "RESPONSE_LOST_AFTER_SEND",
    },
  ],
};

describe("AnalyticsWorkspace", () => {
  it("shows complete zero separately from missing source and unresolved export", async () => {
    const apiClient: AnalyticsApiClient = {
      loadSnapshots: vi.fn().mockResolvedValue(page),
    };
    render(
      <AnalyticsWorkspace
        session={session}
        getAccessToken={async () => "synthetic-token"}
        apiClient={apiClient}
      />,
    );

    expect(await screen.findByText("Value unavailable")).toBeInTheDocument();
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.getByText("MISSING SOURCE")).toBeInTheDocument();
    expect(screen.getByText("Unresolved — reconciliation required")).toBeInTheDocument();
    expect(apiClient.loadSnapshots).toHaveBeenCalledWith(
      expect.objectContaining({ kind: "ORGANIZATION", organizationId }),
      0,
      50,
      expect.any(AbortSignal),
    );
  });

  it("renders an explicit no-access state when Analytics read is not granted", () => {
    render(
      <AnalyticsWorkspace
        session={{ ...session, capabilities: [] }}
        getAccessToken={async () => "synthetic-token"}
        apiClient={{ loadSnapshots: vi.fn() }}
      />,
    );
    expect(
      screen.getByRole("heading", { name: "Analytics read scope is not available" }),
    ).toBeInTheDocument();
  });
});
