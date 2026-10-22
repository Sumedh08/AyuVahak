import { expect, test } from "@playwright/test";

const username = process.env.AYUVAHAK_E2E_USERNAME;
const password = process.env.AYUVAHAK_E2E_PASSWORD;

test("local Synthetic Demo authenticates and keeps its warning visible", async ({ page }) => {
  if (!username || !password) {
    throw new Error(
      "Set AYUVAHAK_E2E_USERNAME and AYUVAHAK_E2E_PASSWORD to the local synthetic test identity.",
    );
  }

  page.on("requestfailed", (request) => {
    const requestUrl = new URL(request.url());
    console.log(
      `request-failed ${requestUrl.pathname} ${request.failure()?.errorText ?? "unknown"}`,
    );
  });
  page.on("console", (message) => {
    if (message.type() === "error") {
      const safeMessage = message.text().replace(/https?:\/\/[^?\s]+\?[^\s]+/g, "[URL]");
      console.log(`browser-error ${safeMessage}`);
    }
  });
  page.on("response", (response) => {
    const responseUrl = new URL(response.url());
    if (
      responseUrl.pathname.includes("openid-connect") ||
      responseUrl.pathname.startsWith("/api/")
    ) {
      console.log(`response ${response.status()} ${responseUrl.pathname}`);
    }
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue to sign in" }).click();

  const usernameField = page.getByLabel("Username or email");
  await usernameField.waitFor({ state: "visible", timeout: 15_000 });
  if (await usernameField.isVisible()) {
    await usernameField.fill(username);
    await page.locator("input#password").fill(password);
    await page.getByRole("button", { name: "Sign In" }).click();
  }

  const emailField = page.getByLabel("Email");
  const profileRequired = await emailField
    .waitFor({ state: "visible", timeout: 5_000 })
    .then(() => true)
    .catch(() => false);
  if (profileRequired) {
    await emailField.fill("synthetic.operator@example.invalid");
    await page.locator("input#firstName").fill("Synthetic");
    await page.locator("input#lastName").fill("Operator");
    await page.getByRole("button", { name: "Submit" }).click();
  }

  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(
    page.getByText("SYNTHETIC DEMONSTRATION ENVIRONMENT", { exact: false }),
  ).toBeVisible();
  await expect(page.getByText("NO REAL PATIENT DATA", { exact: false })).toBeVisible();
  await expect(page.getByText("NOT FOR CLINICAL USE", { exact: false })).toBeVisible();
  await expect(page.getByRole("link", { name: "Access management", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Directory", exact: true })).toHaveCount(0);
  await expect(page.getByText("Session information is unavailable")).toHaveCount(0);
});
