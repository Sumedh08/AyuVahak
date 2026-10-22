import { expect, test } from "@playwright/test";

const username = process.env.AYUVAHAK_E2E_USERNAME;
const password = process.env.AYUVAHAK_E2E_PASSWORD;

test("local Empty deployment authenticates with only the bootstrap grant", async ({ page }) => {
  if (!username || !password) {
    throw new Error(
      "Set AYUVAHAK_E2E_USERNAME and AYUVAHAK_E2E_PASSWORD to the local synthetic test identity.",
    );
  }

  let sessionAuthorization: string | undefined;
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/api/v1/session") {
      sessionAuthorization = request.headers().authorization;
    }
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue to sign in" }).click();

  await page.getByLabel("Username or email").fill(username);
  await page.locator("input#password").fill(password);
  await page.getByRole("button", { name: "Sign In" }).click();

  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(page.getByText("Empty deployment", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Access management", exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Directory", exact: true })).toHaveCount(0);
  await expect(page.getByText("SYNTHETIC DEMONSTRATION ENVIRONMENT", { exact: false })).toHaveCount(
    0,
  );
  await expect(page.getByText("Session information is unavailable")).toHaveCount(0);

  expect(sessionAuthorization).toMatch(/^Bearer /);
  const unauthorizedDirectoryRead = await page.request.get(
    `${process.env.AYUVAHAK_E2E_BASE_URL ?? "http://localhost:8080"}/api/v1/directory/facilities?page=0&size=25`,
    { headers: { authorization: sessionAuthorization } },
  );
  expect(unauthorizedDirectoryRead.status()).toBe(403);

  await page.evaluate(() => {
    window.history.pushState(null, "", "/directory");
    window.dispatchEvent(new PopStateEvent("popstate"));
  });
  await expect(
    page.getByRole("heading", { name: "Workspace access is not listed for this session" }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Facilities in your Directory scope" }),
  ).toHaveCount(0);
});
