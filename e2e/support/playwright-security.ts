import { devices, type PlaywrightTestConfig } from '@playwright/test';

export const secureReporter: NonNullable<PlaywrightTestConfig['reporter']> = [['line']];

export const secureBrowserUse = {
  ...devices['Desktop Chrome'],
  locale: 'ru-RU',
  timezoneId: 'Asia/Tashkent',
  screenshot: 'only-on-failure' as const,
  trace: 'off' as const,
  video: 'off' as const,
};
