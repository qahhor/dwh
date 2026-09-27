import { readFileSync } from 'node:fs';

import { defineConfig } from '@playwright/test';

import { loadE2eEnv } from './support/env.mjs';
import { secureBrowserUse, secureReporter } from './support/playwright-security.js';

const environment = loadE2eEnv();

// A retry hides an unstable test (plan 10/10, item 1.8): there is none, and a known unstable test is listed in
// quarantine.json instead. The suite skips it; E2E_QUARANTINE=only runs just the quarantined ones.
const quarantined: string[] = JSON.parse(readFileSync(new URL('./quarantine.json', import.meta.url), 'utf8')).tests.map(
  (entry: { title: string }) => entry.title,
);
const quarantine = quarantined.length
  ? new RegExp(quarantined.map((title) => title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'))
  : undefined;
const onlyQuarantine = process.env.E2E_QUARANTINE === 'only';

export default defineConfig({
  testDir: './tests/browser',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  ...(onlyQuarantine ? { grep: quarantine ?? /^$/ } : quarantine ? { grepInvert: quarantine } : {}),
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
