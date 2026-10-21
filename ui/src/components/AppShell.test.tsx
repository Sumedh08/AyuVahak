import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { SessionProjection } from "../api/session";
import { AppShell } from "./AppShell";

const session: SessionProjection = {
  deploymentMode: "EMPTY",
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
          kind: "ORGANIZATION",
          organization: {
            issuer: "urn:ayuvahak:directory",
            identifierType: "Organization",
            identifier: "6d383f64-9f14-4cf6-a8a1-3e119e8fc882",
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
            identifier: "6d383f64-9f14-4cf6-a8a1-3e119e8fc882",
          },
          facility: {
            issuer: "urn:ayuvahak:directory",
            identifierType: "Facility",
            identifier: "9eb1d3e6-86c4-45aa-b6bb-e15596ee05ec",
          },
        },
      ],
    },
  ],
};

describe("capability-aware application shell", () => {
  it("shows only workspaces supported by server-reported authorities and scope kinds", () => {
    render(<AppShell session={session} currentPath="/" onSignOut={vi.fn()} onNavigate={vi.fn()} />);
    expect(screen.getByRole("link", { name: /directory/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /capacity/i })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /referrals/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /access management/i })).not.toBeInTheDocument();
  });

  it("does not treat a direct deep link as authorization", () => {
    render(
      <AppShell
        session={session}
        currentPath="/referrals"
        onSignOut={vi.fn()}
        onNavigate={vi.fn()}
      />,
    );
    expect(
      screen.getByRole("heading", { name: "Workspace access is not listed for this session" }),
    ).toBeInTheDocument();
    expect(screen.getByText(/does not grant access/i)).toBeInTheDocument();
  });

  it("keeps the API authorization boundary explicit in navigation help", () => {
    render(<AppShell session={session} currentPath="/" onSignOut={vi.fn()} onNavigate={vi.fn()} />);
    expect(screen.getByText(/every api request is authorized by ayuvahak/i)).toBeInTheDocument();
  });

  it("shows the synthetic safety banner on every demo-mode authenticated screen", () => {
    render(
      <AppShell
        session={{ ...session, deploymentMode: "SYNTHETIC_DEMO" }}
        currentPath="/directory"
        onSignOut={vi.fn()}
        onNavigate={vi.fn()}
      />,
    );

    expect(screen.getByText("SYNTHETIC DEMONSTRATION ENVIRONMENT")).toBeInTheDocument();
    expect(screen.getByText("NO REAL PATIENT DATA — NOT FOR CLINICAL USE")).toBeInTheDocument();
  });

  it("does not label an empty deployment as synthetic", () => {
    render(<AppShell session={session} currentPath="/" onSignOut={vi.fn()} onNavigate={vi.fn()} />);
    expect(screen.queryByText("SYNTHETIC DEMONSTRATION ENVIRONMENT")).not.toBeInTheDocument();
  });

  it("uses same-document navigation so the in-memory token survives workspace changes", () => {
    const onNavigate = vi.fn();
    render(
      <AppShell session={session} currentPath="/" onSignOut={vi.fn()} onNavigate={onNavigate} />,
    );

    fireEvent.click(screen.getByRole("link", { name: /directory/i }));

    expect(onNavigate).toHaveBeenCalledWith("/directory");
  });
});
