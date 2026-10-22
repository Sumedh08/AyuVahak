import { expect, test } from "@playwright/test";

const username = process.env.AYUVAHAK_E2E_USERNAME;
const password = process.env.AYUVAHAK_E2E_PASSWORD;

test("Synthetic Demo workspaces read the seeded owner data", async ({ page }) => {
  if (!username || !password) {
    throw new Error(
      "Set AYUVAHAK_E2E_USERNAME and AYUVAHAK_E2E_PASSWORD to the local synthetic Demo operator.",
    );
  }

  const baseUrl = process.env.AYUVAHAK_E2E_BASE_URL ?? "http://localhost:8080";
  const host = new URL(baseUrl).hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error("Synthetic Demo E2E may only use a loopback application URL.");
  }

  await page.goto("/");
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  await page.getByLabel("Username or email").fill(username);
  await page.locator("input#password").fill(password);
  await page.getByRole("button", { name: "Sign In" }).click();

  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(page.getByText("SYNTHETIC DEMONSTRATION ENVIRONMENT")).toBeVisible();

  await page.getByRole("link", { name: "Directory", exact: true }).click();
  await expect(
    page.getByText("SYN-FACILITY-ORIGIN — Synthetic Origin Facility", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("SYN-FACILITY-RECEIVING — Synthetic Receiving Facility", { exact: true }),
  ).toBeVisible();

  await page.getByRole("link", { name: "Capacity", exact: true }).click();
  await page.getByLabel("Configured dimension code").fill("SYN-TRANSFER-SLOT");
  await page.getByRole("button", { name: "Check current evidence" }).click();
  await expect(page.getByRole("heading", { name: "Current evidence" })).toBeVisible();
  await expect(page.getByText("4 slot", { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "Referrals", exact: true }).click();
  await expect(page.getByRole("table").getByRole("row").nth(1)).toBeVisible();

  await page.getByRole("link", { name: "Matching", exact: true }).click();
  await expect(page.getByRole("table").getByRole("row").nth(1)).toBeVisible();
  await page.getByRole("button", { name: "Review submitted revision" }).click();
  await page.getByRole("button", { name: "Request deterministic recommendation" }).click();
  await expect(
    page.getByRole("heading", { name: "Structured Matching result", exact: true }).first(),
  ).toBeVisible();

  await page.getByRole("link", { name: "Transfers", exact: true }).click();
  await page.getByRole("button", { name: "Load Transfers" }).click();
  await expect(page.getByRole("table").getByRole("row").nth(1)).toBeVisible();

  await page.getByRole("link", { name: "Transport", exact: true }).click();
  await page.getByRole("button", { name: "Load visible Transfers" }).click();
  await page.getByRole("button", { name: "Load Transport requests" }).click();
  await expect(page.getByRole("table").getByRole("row").nth(1)).toBeVisible();

  await page.getByRole("link", { name: "Analytics", exact: true }).click();
  await expect(page.getByText("SYN-DEMO-REFERRAL-SUBMISSIONS v1", { exact: true })).toBeVisible();
  await expect(page.getByText("COMPLETE", { exact: true })).toBeVisible();
  await expect(page.getByText("0", { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "Operator support", exact: true }).click();
  if (process.env.AYUVAHAK_E2E_EXPECT_EXTERNAL_PROFILE === "true") {
    await expect(
      page.getByText(/External provider use is paused until this exact profile is reviewed/u),
    ).toBeVisible();
    await page.getByLabel("Documentation topic").selectOption("DATABASE_RESTORE");
    await expect(page.getByRole("button", { name: "Show advisory guidance" })).toBeDisabled();
  } else {
    await expect(page.getByText("Operator support is disabled", { exact: false })).toBeVisible();
    await page.getByLabel("Documentation topic").selectOption("DATABASE_RESTORE");
    await page.getByRole("button", { name: "Show advisory guidance" }).click();
    await expect(page.getByRole("heading", { name: "No advisory answer" })).toBeVisible();
    await expect(
      page.getByText("Core AyuVahak workflows remain available", { exact: false }),
    ).toBeVisible();
  }
});
