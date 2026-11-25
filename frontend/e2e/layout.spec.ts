import { expect, test, type Page } from "@playwright/test";

/**
 * Mobile and desktop layouts: every page renders its heading, has no horizontal scroll, no
 * console errors, and the right navigation for the screen size. A full-page screenshot of each is
 * attached to the report.
 */

const PUBLIC_PAGES = ["/", "/pricing", "/login", "/register", "/privacy", "/terms"];
const APP_PAGES = ["/dashboard", "/properties", "/properties/new", "/properties/sample-pagrati", "/compare", "/settings"];

async function visit(page: Page, path: string) {
  const errors: string[] = [];
  page.on("console", (m) => {
    if (m.type() === "error") errors.push(m.text());
  });
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(path, { waitUntil: "domcontentloaded" });
  await page.locator("h1").first().waitFor();
  // Client components hydrate after load (stores read localStorage); give them a moment.
  await page.waitForTimeout(400);
  return errors;
}

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => {
    const doc = document.documentElement;
    const wide = [...document.querySelectorAll<HTMLElement>("body *")]
      .filter((el) => {
        const r = el.getBoundingClientRect();
        // Elements inside a scroll container (tables) may be wider: that is intended.
        let p = el.parentElement;
        while (p) {
          const o = getComputedStyle(p).overflowX;
          if (o === "auto" || o === "scroll" || o === "hidden") return false;
          p = p.parentElement;
        }
        return r.width > 0 && r.right > doc.clientWidth + 1;
      })
      .slice(0, 3)
      .map((el) => `${el.tagName.toLowerCase()}.${el.className.toString().slice(0, 60)}`);
    return { scroll: doc.scrollWidth - doc.clientWidth, wide };
  });
  expect(overflow.scroll, `page scrolls sideways; widest: ${overflow.wide.join(", ")}`).toBeLessThanOrEqual(1);
}

for (const path of [...PUBLIC_PAGES, ...APP_PAGES]) {
  test(`${path} fits the screen`, async ({ page }, info) => {
    const errors = await visit(page, path);
    await expect(page.locator("h1").first()).toBeVisible();
    await expectNoHorizontalScroll(page);
    expect(errors, errors.join("\n")).toEqual([]);
    await info.attach(`${info.project.name}${path.replaceAll("/", "_") || "_home"}.png`, {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
}

test("navigation matches the screen size", async ({ page, isMobile }) => {
  await visit(page, "/dashboard");
  const openMenu = page.getByRole("button", { name: "Open menu" });
  if (isMobile) {
    await expect(openMenu).toBeVisible();
    await openMenu.click();
    await expect(page.getByRole("navigation", { name: "Main" }).last()).toBeVisible();
  } else {
    await expect(openMenu).toBeHidden();
    await expect(page.getByRole("navigation", { name: "Main" }).first()).toBeVisible();
  }
});

test("registration requires the terms and privacy notice", async ({ page }) => {
  await visit(page, "/register");
  await page.getByLabel("Full name").fill("Layout Tester");
  await page.getByLabel("Email").fill("layout@example.test");
  await page.getByLabel("Password").fill("correct horse battery");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByText("Accept the terms and privacy notice to continue.")).toBeVisible();
  await expect(page).toHaveURL(/\/register$/);

  await page.getByRole("checkbox", { name: /I accept the terms/ }).check();
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
});

test("legal pages state what estimates and the document checker are not", async ({ page }) => {
  await visit(page, "/terms");
  await expect(page.getByRole("heading", { name: "Valuations are automated estimates, not professional appraisals" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "The Document Checker is an assistance tool, not a legal certification" })).toBeVisible();
  await visit(page, "/privacy");
  await expect(page.getByRole("heading", { name: "How long we keep it" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "180 days after upload, unless you delete them or the account earlier" })).toBeVisible();
});

test("the analysis page labels the value as an automated estimate", async ({ page }) => {
  await visit(page, "/properties/sample-pagrati");
  await expect(page.getByText("automated estimate", { exact: false }).first()).toBeVisible();
});
