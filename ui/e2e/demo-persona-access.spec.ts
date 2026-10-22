import { readFile } from "node:fs/promises";
import { expect, type Page, test } from "@playwright/test";

const baseUrl = process.env.AYUVAHAK_E2E_BASE_URL ?? "http://localhost:8080";
const password = process.env.AYUVAHAK_E2E_PASSWORD;
const seedStatePath = process.env.AYUVAHAK_E2E_SEED_STATE_FILE;

interface DemoSeedState {
  readonly resourceIds?: {
    readonly originOrganization?: string;
    readonly receivingOrganization?: string;
    readonly originFacility?: string;
    readonly receivingFacility?: string;
  };
}

interface Persona {
  readonly username: string;
  readonly workspaces: readonly string[];
  readonly hidden: readonly string[];
}

const personas: readonly Persona[] = [
  {
    username: "synthetic.system-admin",
    workspaces: ["Access management"],
    hidden: ["Directory", "Referrals", "Capacity", "Analytics"],
  },
  {
    username: "synthetic.org-admin",
    workspaces: ["Directory"],
    hidden: ["Access management", "Referrals", "Analytics"],
  },
  {
    username: "synthetic.referral-coordinator",
    workspaces: ["Referrals", "Matching", "Transfers"],
    hidden: ["Directory", "Receiving work", "Transport", "Analytics"],
  },
  {
    username: "synthetic.receiving-coordinator",
    workspaces: ["Transfers", "Receiving work"],
    hidden: ["Referrals", "Matching", "Capacity", "Transport", "Analytics"],
  },
  {
    username: "synthetic.capacity-manager",
    workspaces: ["Capacity"],
    hidden: ["Referrals", "Matching", "Transfers", "Analytics"],
  },
  {
    username: "synthetic.transport-coordinator",
    workspaces: ["Transfers", "Transport"],
    hidden: ["Referrals", "Matching", "Receiving work", "Analytics"],
  },
  {
    username: "synthetic.analyst",
    workspaces: ["Analytics"],
    hidden: ["Referrals", "Matching", "Transfers", "Transport", "Access management"],
  },
];

async function signIn(page: Page, username: string): Promise<void> {
  await page.goto(baseUrl);
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  await page.getByLabel("Username or email").fill(username);
  await page.locator("input#password").fill(password ?? "");
  await page.getByRole("button", { name: "Sign In" }).click();
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(page.getByText("SYNTHETIC DEMONSTRATION ENVIRONMENT")).toBeVisible();
}

test("Synthetic Demo role identities get only their scoped workspaces and APIs", async ({
  browser,
  request,
}) => {
  test.setTimeout(7 * 60_000);
  const host = new URL(baseUrl).hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error("Demo persona E2E may only use a loopback AyuVahak URL.");
  }
  if (!password || !seedStatePath) {
    throw new Error(
      "Provide the local Demo persona password and generated seed-state path for scope checks.",
    );
  }
  const seedState = JSON.parse(await readFile(seedStatePath, "utf8")) as DemoSeedState;
  const ids = seedState.resourceIds;
  if (
    !ids?.originOrganization ||
    !ids.receivingOrganization ||
    !ids.originFacility ||
    !ids.receivingFacility
  ) {
    throw new Error(
      "The local synthetic Demo seed state is incomplete for organization isolation.",
    );
  }

  let analystToken: string | null = null;
  let referralCoordinatorToken: string | null = null;
  for (const persona of personas) {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on("request", (request) => {
      if (new URL(request.url()).pathname !== "/api/v1/session") return;
      void request.headerValue("authorization").then((value) => {
        if (persona.username === "synthetic.analyst") analystToken = value;
        if (persona.username === "synthetic.referral-coordinator") referralCoordinatorToken = value;
      });
    });

    try {
      await signIn(page, persona.username);
      for (const workspace of persona.workspaces) {
        await expect(page.getByRole("link", { name: workspace, exact: true })).toBeVisible();
      }
      for (const workspace of persona.hidden) {
        await expect(page.getByRole("link", { name: workspace, exact: true })).toHaveCount(0);
      }
      if (persona.username === "synthetic.analyst") {
        await page.evaluate(() => {
          window.history.pushState(null, "", "/referrals");
          window.dispatchEvent(new PopStateEvent("popstate"));
        });
        await expect(
          page.getByRole("heading", { name: "Workspace access is not listed for this session" }),
        ).toBeVisible();
      }
      if (persona.username === "synthetic.org-admin") {
        await page.getByRole("link", { name: "Directory", exact: true }).click();
        await expect(page.getByRole("heading", { name: "Directory", level: 1 })).toBeVisible();
      }
    } finally {
      await context.close();
    }
  }

  await expect.poll(() => analystToken).toBeTruthy();
  await expect.poll(() => referralCoordinatorToken).toBeTruthy();
  const referralResponse = await request.get(`${baseUrl}/api/v1/referrals?page=0&size=25`, {
    headers: {
      Authorization: analystToken ?? "",
      "X-AyuVahak-Execution-Scope": "ORGANIZATION",
      "X-AyuVahak-Organization-Id": ids.originOrganization,
    },
  });
  expect(referralResponse.status()).toBe(403);

  const crossScopeResponse = await request.get(`${baseUrl}/api/v1/referrals?page=0&size=25`, {
    headers: {
      Authorization: referralCoordinatorToken ?? "",
      "X-AyuVahak-Execution-Scope": "FACILITY",
      "X-AyuVahak-Organization-Id": ids.receivingOrganization,
      "X-AyuVahak-Facility-Id": ids.receivingFacility,
    },
  });
  expect(crossScopeResponse.status()).toBe(403);
});
