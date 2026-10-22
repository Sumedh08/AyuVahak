import { expect, test } from "@playwright/test";

const baseUrl = process.env.AYUVAHAK_E2E_BASE_URL ?? "http://localhost:8080";
const username = process.env.AYUVAHAK_E2E_USERNAME;
const password = process.env.AYUVAHAK_E2E_PASSWORD;
const forbiddenSecret = process.env.AYUVAHAK_E2E_FORBIDDEN_SECRET;

test("external provider profile is visible without secret readback and stays paused pending review", async ({
  page,
}) => {
  const host = new URL(baseUrl).hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error("Provider-profile E2E may only use a loopback AyuVahak deployment.");
  }
  if (!username || !password || !forbiddenSecret) {
    throw new Error("Set local synthetic operator-support E2E credentials.");
  }

  let externalProviderRequests = 0;
  let profileBody: Record<string, unknown> | undefined;
  await page.route("https://api.openai.com/**", async (route) => {
    externalProviderRequests += 1;
    await route.abort();
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

  await expect(
    page.getByText(/External provider use is paused until this exact profile is reviewed/u),
  ).toBeVisible();
  await page.getByLabel("Documentation topic").selectOption("DATABASE_RESTORE");
  await expect(page.getByRole("button", { name: "Show advisory guidance" })).toBeDisabled();
  await expect.poll(() => profileBody).toBeDefined();
  expect(profileBody).toMatchObject({
    enabled: true,
    provider: "OPENAI_COMPATIBLE",
    configurationValid: true,
    secretReferenceConfigured: true,
    evaluationRequired: true,
    evaluationCurrent: false,
  });
  expect(profileBody).not.toHaveProperty("endpoint");
  expect(profileBody).not.toHaveProperty("secretReference");
  expect(profileBody).not.toHaveProperty("apiKey");
  const pageText = (await page.locator("body").textContent()) ?? "";
  expect(pageText.includes(forbiddenSecret)).toBe(false);
  expect(externalProviderRequests).toBe(0);
});
