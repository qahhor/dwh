import { defineConfig } from '@playwright/test';

import { secureBrowserUse, secureReporter } from './support/playwright-security.js';

export default defineConfig({
  testDir: './tests/security',
  // A focused test (test.only) left in a commit would silently drop the rest of the suite in CI.
  forbidOnly: !!process.env.CI,
  workers: 1,
  retries: 0,
  reporter: secureReporter,
  outputDir: 'artifact-security-results',
  use: {
    ...secureBrowserUse,
    baseURL: 'http://artifact-security.invalid',
  },
});
