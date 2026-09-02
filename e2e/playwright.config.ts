import { defineConfig } from '@playwright/test';

import { loadE2eEnv } from './support/env.mjs';
import { secureBrowserUse, secureReporter } from './support/playwright-security.js';

const environment = loadE2eEnv();

export default defineConfig({
  testDir: './tests/browser',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 40_000,
  expect: { timeout: 8_000 },
  outputDir: 'test-results',
  reporter: secureReporter,
  use: {
    ...secureBrowserUse,
  },
  projects: [
    {
      name: 'instance',
      testMatch: /(?:instance|system|announcements)\/.*\.spec\.ts/,
      use: { baseURL: environment.instance.baseURL },
    },
  ],
});
