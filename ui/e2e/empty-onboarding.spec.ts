import { type Browser, expect, type Page, test } from "@playwright/test";

const baseUrl = process.env.AYUVAHAK_E2E_BASE_URL ?? "http://localhost:8080";
const initialUsername = process.env.AYUVAHAK_E2E_USERNAME;
const initialPassword = process.env.AYUVAHAK_E2E_PASSWORD;
const directoryUsername = process.env.AYUVAHAK_E2E_DIRECTORY_USERNAME;
const directoryPassword = process.env.AYUVAHAK_E2E_DIRECTORY_PASSWORD;
const directorySubject = process.env.AYUVAHAK_E2E_DIRECTORY_SUBJECT;

async function signIn(page: Page, username: string, password: string): Promise<void> {
  await page.goto(baseUrl);
  await page.getByRole("button", { name: "Continue to sign in" }).click();
  await page.getByLabel("Username or email").fill(username);
  await page.locator("input#password").fill(password);
  await page.getByRole("button", { name: "Sign In" }).click();
  await expect(page.getByRole("heading", { name: "Overview", level: 1 })).toBeVisible();
  await expect(page.getByText("Empty deployment", { exact: true })).toBeVisible();
}

test("Empty onboarding delegates Directory setup, then creates the first organization and facility", async ({
  browser,
}) => {
  const host = new URL(baseUrl).hostname;
  if (host !== "localhost" && host !== "127.0.0.1") {
    throw new Error("Empty deployment onboarding E2E may only use a loopback application URL.");
  }
  if (
    !initialUsername ||
    !initialPassword ||
    !directoryUsername ||
    !directoryPassword ||
    !directorySubject
  ) {
    throw new Error(
      "Provide two local synthetic IdP identities and the IdP-generated subject for the Directory setup operator.",
    );
  }

  const initial = await browser.newPage();
  let directoryContext: Awaited<ReturnType<Browser["newContext"]>> | undefined;
  try {
    await signIn(initial, initialUsername, initialPassword);
    await expect(initial.getByRole("link", { name: "Directory", exact: true })).toHaveCount(0);
    await initial.getByRole("link", { name: "Access management", exact: true }).click();
    await initial.getByLabel("Access preset").selectOption({
      label: "Platform Directory setup operator",
    });
    await initial.getByLabel("IdP subject").fill(directorySubject);
    await initial.getByRole("button", { name: "Apply template grants" }).click();
    await expect(
      initial.getByRole("status").filter({ hasText: "Created 2 separate grants" }),
    ).toBeVisible();

    directoryContext = await browser.newContext();
    const directoryOperator = await directoryContext.newPage();
    await signIn(directoryOperator, directoryUsername, directoryPassword);
    await expect(
      directoryOperator.getByRole("link", { name: "Directory", exact: true }),
    ).toBeVisible();
    await directoryOperator.getByRole("link", { name: "Directory", exact: true }).click();

    await directoryOperator.getByLabel("Organization name").fill("SYN-EMPTY-ORG-001");
    await directoryOperator.getByRole("button", { name: "Create organization" }).click();
    const organizationStatus = directoryOperator.getByRole("status").filter({
      hasText: "Organization created:",
    });
    await expect(organizationStatus).toBeVisible();
    const organizationText = (await organizationStatus.textContent()) ?? "";
    const organizationId = organizationText.match(/Organization created: ([0-9a-f-]{36})/iu)?.[1];
    if (!organizationId || !/^[0-9a-f-]{36}$/iu.test(organizationId)) {
      throw new Error("The Directory owner did not return the created synthetic organization ID.");
    }

    await directoryOperator.getByLabel("Managing organization ID").fill(organizationId);
    await directoryOperator.getByLabel("Facility name").fill("SYN-EMPTY-FACILITY-001");
    await directoryOperator.getByRole("button", { name: "Create facility" }).click();
    await expect(
      directoryOperator.getByRole("status").filter({ hasText: "Facility created:" }),
    ).toBeVisible();
    await expect(
      directoryOperator.getByText("SYN-EMPTY-FACILITY-001", { exact: true }),
    ).toBeVisible();
    await expect(
      directoryOperator.getByText("SYNTHETIC DEMONSTRATION ENVIRONMENT", { exact: false }),
    ).toHaveCount(0);
  } finally {
    await directoryContext?.close();
    await initial.close();
  }
});
