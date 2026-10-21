import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { OperatorSupportApiClient } from "../api/operator-support";
import { OperatorSupportWorkspace } from "./OperatorSupportWorkspace";

describe("OperatorSupportWorkspace", () => {
  it("shows a cited advisory and lets the operator close it without a workflow action", async () => {
    const apiClient: OperatorSupportApiClient = {
      answer: vi.fn().mockResolvedValue({
        status: "ADVISORY",
        answer: "Confirm freshness with the Capacity owner.",
        sources: [
          {
            sourceId: "capacity-stale-guide",
            path: "docs/operations/capacity.md",
            section: "Staleness",
            contentHash: "a".repeat(64),
          },
        ],
        capabilityVersion: "operator-support-v1",
        corpusVersion: "operator-support-corpus-v1",
        reasonCode: "NONE",
      }),
    };
    render(
      <OperatorSupportWorkspace getAccessToken={async () => "unused"} apiClient={apiClient} />,
    );

    fireEvent.change(screen.getByLabelText("Documentation topic"), {
      target: { value: "CAPACITY_STALENESS" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Show advisory guidance" }));

    expect(await screen.findByText("Confirm freshness with the Capacity owner.")).toBeTruthy();
    expect(screen.getByText("ADVISORY")).toBeTruthy();
    expect(screen.getByText("docs/operations/capacity.md")).toBeTruthy();
    expect(
      screen.getByText(
        "This response does not change AyuVahak state. Verify operational decisions against authoritative records.",
      ),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Close advisory" }));
    await waitFor(() =>
      expect(screen.queryByText("Confirm freshness with the Capacity owner.")).toBeNull(),
    );
  });

  it("explains disabled assistance and keeps the ordinary application available", async () => {
    const apiClient: OperatorSupportApiClient = {
      answer: vi.fn().mockResolvedValue({
        status: "ABSTAINED",
        answer: "",
        sources: [],
        capabilityVersion: "operator-support-v1",
        corpusVersion: "operator-support-corpus-v1",
        reasonCode: "DISABLED",
      }),
    };
    render(
      <OperatorSupportWorkspace getAccessToken={async () => "unused"} apiClient={apiClient} />,
    );

    fireEvent.change(screen.getByLabelText("Documentation topic"), {
      target: { value: "DATABASE_RESTORE" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Show advisory guidance" }));

    expect(
      await screen.findByText(
        "Operator support is disabled by this deployment. Core AyuVahak workflows remain available.",
      ),
    ).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Operator support" })).toBeTruthy();
  });
});
