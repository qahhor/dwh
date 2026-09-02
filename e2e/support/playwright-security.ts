import { devices, type PlaywrightTestConfig } from '@playwright/test';

export const secureReporter: NonNullable<PlaywrightTestConfig['reporter']> = [['line']];

export const secureBrowserUse = {
  ...devices['Desktop Chrome'],
  locale: 'ru-RU',
  timezoneId: 'Asia/Tashkent',
  reducedMotion: 'reduce' as const,
  screenshot: 'only-on-failure' as const,
  trace: 'off' as const,
  video: 'off' as const,
};
