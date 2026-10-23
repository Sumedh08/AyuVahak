import { expect, test } from "@playwright/test";

const baseUrl = process.env.AYUVAHAK_E2E_BASE_URL ?? "http://localhost:8080";
const username = process.env.AYUVAHAK_E2E_USERNAME;
const password = process.env.AYUVAHAK_E2E_PASSWORD;

test("deterministic local operator support returns cited advisory guidance only", async ({
  page,
}) => {
  const host = new URL(baseUrl).hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error("Local fake provider E2E may only use a loopback AyuVahak deployment.");
  }
  if (!username || !password) {
    throw new Error("Set local synthetic operator-support E2E credentials.");
  }

  const mutationRequests: Array<{ path: string; body: unknown }> = [];
  let profileBody: Record<string, unknown> | undefined;
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin !== new URL(baseUrl).origin || request.method() === "GET") return;
    mutationRequests.push({ path: url.pathname, body: request.postDataJSON() });
  });
  page.on("response", async (response) => {
    if (new URL(response.url()).pathname !== "/api/v1/operator-support/profile") return;
    profileBody = (await response.json()) as Record<string, unknown>;
  });

  await page.goto(baseUrl);
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  await page.getByLabel("Username or email").fill(username);
  await page.locator("input#password").fill(password);
  await page.getByRole("button", { name: "Sign In" }).click();
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();

  await page.getByRole("link", { name: "Operator support", exact: true }).click();
  await expect(page.getByText("The deterministic local provider is enabled.")).toBeVisible();
  await page.getByLabel("Documentation topic").selectOption("DATABASE_RESTORE");
  await page.getByRole("button", { name: "Show advisory guidance" }).click();

  await expect(page.getByRole("heading", { name: "Advisory guidance" })).toBeVisible();
  await expect(page.getByText("ADVISORY", { exact: true })).toBeVisible();
  await expect(page.getByText("This response does not change AyuVahak state.")).toBeVisible();
  await expect(page.getByText(/DATABASE_RESTORE_RUNBOOK/u)).toBeVisible();
  await expect(page.getByText(/Restore only to an isolated clean target/u)).toBeVisible();
  await expect
    .poll(() => profileBody)
    .toMatchObject({
      enabled: true,
      provider: "DETERMINISTIC_LOCAL",
      configurationValid: true,
      evaluationRequired: false,
      evaluationCurrent: true,
    });
  expect(profileBody).not.toHaveProperty("endpoint");
  expect(mutationRequests).toEqual([
    { path: "/api/v1/operator-support/answers", body: { questionKey: "DATABASE_RESTORE" } },
  ]);

  await page.getByRole("button", { name: "Close advisory" }).click();
  await expect(page.getByRole("heading", { name: "Advisory guidance" })).toHaveCount(0);
});
