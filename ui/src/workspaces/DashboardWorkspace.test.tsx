import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { SessionProjection } from "../api/session";
import { DashboardWorkspace } from "./DashboardWorkspace";

const session: SessionProjection = {
  subject: {
    kind: "HUMAN",
    issuer: "https://identity.example.test/realms/ayuvahak",
    identifierType: "subject",
    identifier: "synthetic-coordinator",
  },
  capabilities: [
    {
      authorityCode: "ReferralRead",
      scopes: [
        {
          kind: "ORGANIZATION",
          organization: {
            issuer: "urn:ayuvahak:directory",
            identifierType: "Organization",
            identifier: "08b72645-81d8-45f6-b54a-94d03f58feba",
          },
        },
      ],
    },
    {
      authorityCode: "ReferralCreate",
      scopes: [
        {
          kind: "ORGANIZATION",
          organization: {
            issuer: "urn:ayuvahak:directory",
            identifierType: "Organization",
            identifier: "08b72645-81d8-45f6-b54a-94d03f58feba",
          },
        },
      ],
    },
  ],
  deploymentMode: "EMPTY",
};

describe("DashboardWorkspace", () => {
  it("shows only authorized workspaces and makes no unsupported metric claims", () => {
    render(<DashboardWorkspace session={session} />);

    expect(screen.getByRole("link", { name: /Referrals/ })).toHaveAttribute("href", "/referrals");
    expect(screen.queryByRole("link", { name: /Analytics/ })).toBeNull();
    expect(
      screen.getByText("No demonstration business data is implied by this deployment mode."),
    ).toBeTruthy();
    expect(screen.getByText(/does not invent counts/)).toBeTruthy();
  });

  it("explains when no operational workspace is authorized", () => {
    render(<DashboardWorkspace session={{ ...session, capabilities: [] }} />);

    expect(
      screen.getByText("No operational workspace is authorized for this session"),
    ).toBeTruthy();
  });
});
