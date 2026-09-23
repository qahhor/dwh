import { defineConfig } from '@playwright/test';

import { secureBrowserUse, secureReporter } from './support/playwright-security.js';

// The accessibility gate: the production web build against a mocked API, so
// it runs in the frontend job without a backend or database. The full-stack
// suite in playwright.config.ts keeps its own axe checks on live screens.
const port = Number(process.env.A11Y_PORT ?? 4310);

export default defineConfig({
  testDir: './tests/a11y',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 40_000,
  expect: { timeout: 8_000 },
  outputDir: 'test-results/a11y',
  reporter: secureReporter,
  use: {
    ...secureBrowserUse,
    baseURL: `http://127.0.0.1:${port}`,
    // A preinstalled Chromium (as in the cloud sandbox) can be used instead of a downloaded one.
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE }
      : {},
  },
  webServer: {
    command: 'node support/a11y-server.mjs',
    url: `http://127.0.0.1:${port}/`,
    reuseExistingServer: !process.env.CI,
    timeout: 20_000,
  },
  projects: [{ name: 'a11y' }],
});
