import { defineConfig, devices } from "@playwright/test";

/**
 * Layout tests (mobile and desktop) against the production build in browser-only demo mode.
 *   npm run build && npm run test:e2e
 * Screenshots of every page land in test-results/ for review.
 */
const PORT = Number(process.env.E2E_PORT ?? 3100);

export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  workers: process.env.CI ? 2 : 4,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  // Tracing slows Chromium down badly on some Windows machines; keep it for CI failures.
  use: { baseURL: `http://localhost:${PORT}`, trace: process.env.CI ? "retain-on-failure" : "off" },
  projects: [
    { name: "mobile", use: { ...devices["Pixel 7"] } },
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } },
  ],
  webServer: {
    command: `npm run start -- -p ${PORT}`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    // Browser-only demo: no backend, so the layout is tested without network dependencies.
    env: { NEXT_PUBLIC_API_URL: "" },
  },
});
