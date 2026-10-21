import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { SessionProjection } from "../api/session";
import { AppShell } from "./AppShell";

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
          kind: "ORGANIZATION",
          organization: {
            issuer: "urn:synthetic:directory",
            identifierType: "organization",
            identifier: "SYN-ORG-001",
          },
        },
      ],
    },
    {
      authorityCode: "CapacityReporting",
      scopes: [
        {
          kind: "FACILITY",
          organization: {
            issuer: "urn:synthetic:directory",
            identifierType: "organization",
            identifier: "SYN-ORG-001",
          },
          facility: {
            issuer: "urn:synthetic:directory",
            identifierType: "facility",
            identifier: "SYN-FACILITY-A",
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

  it("uses same-document navigation so the in-memory token survives workspace changes", () => {
    const onNavigate = vi.fn();
    render(
      <AppShell session={session} currentPath="/" onSignOut={vi.fn()} onNavigate={onNavigate} />,
    );

    fireEvent.click(screen.getByRole("link", { name: /directory/i }));

    expect(onNavigate).toHaveBeenCalledWith("/directory");
  });
});
