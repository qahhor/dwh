import { defineConfig } from '@playwright/test';

import { secureBrowserUse, secureReporter } from './support/playwright-security.js';

export default defineConfig({
  testDir: './tests/security',
  workers: 1,
  retries: 0,
  reporter: secureReporter,
  outputDir: 'artifact-security-results',
  use: {
    ...secureBrowserUse,
    baseURL: 'http://artifact-security.invalid',
  },
});
