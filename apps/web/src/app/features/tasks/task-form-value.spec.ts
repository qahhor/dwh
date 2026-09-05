import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { toLocalDateTime, toTaskInstant } from './task-form-value';

describe('task date form mapping', () => {
  beforeEach(() => {
    vi.stubEnv('TZ', 'Asia/Tashkent');
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('renders an instant as browser-local datetime minutes in fixed UTC+5', () => {
    expect(toLocalDateTime('2026-09-05T12:00:37.123Z')).toBe('2026-09-05T17:00');
  });

  it('preserves the exact original instant when its visible value is unchanged', () => {
    expect(toTaskInstant('2026-09-05T17:00', '2026-09-05T12:00:37.123Z'))
      .toBe('2026-09-05T12:00:37.123Z');
  });

  it('converts an edited browser-local minute back to an instant', () => {
    expect(toTaskInstant('2026-09-05T17:01', '2026-09-05T12:00:37.123Z'))
      .toBe('2026-09-05T12:01:00.000Z');
  });

  it('maps a cleared datetime to explicit null', () => {
    expect(toTaskInstant('', '2026-09-05T12:00:00Z')).toBeNull();
  });
});
