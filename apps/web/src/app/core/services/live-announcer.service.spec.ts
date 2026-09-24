import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ANNOUNCE_DELAY_MS, LiveAnnouncerService } from './live-announcer.service';

describe('LiveAnnouncerService', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  const region = () => document.querySelector('.app-live-announcer') as HTMLElement;

  it('puts one polite, atomic, visually hidden region in the page before anything is announced', () => {
    TestBed.inject(LiveAnnouncerService);

    expect(document.querySelectorAll('.app-live-announcer')).toHaveLength(1);
    expect(region().getAttribute('aria-live')).toBe('polite');
    expect(region().getAttribute('aria-atomic')).toBe('true');
    expect(region().style.clip).toMatch(/^rect\(0/);
    expect(region().style.overflow).toBe('hidden');
    expect(region().textContent).toBe('');
  });

  it('empties the region first, so the same text is read again when repeated', () => {
    const announcer = TestBed.inject(LiveAnnouncerService);

    announcer.announce('Сохранено');
    vi.advanceTimersByTime(ANNOUNCE_DELAY_MS);
    expect(region().textContent).toBe('Сохранено');

    announcer.announce('Сохранено');
    expect(region().textContent).toBe('');
    vi.advanceTimersByTime(ANNOUNCE_DELAY_MS);
    expect(region().textContent).toBe('Сохранено');
  });

  it('keeps only the latest of quick successive messages', () => {
    const announcer = TestBed.inject(LiveAnnouncerService);

    announcer.announce('Первое');
    announcer.announce('Второе');
    vi.advanceTimersByTime(ANNOUNCE_DELAY_MS);

    expect(region().textContent).toBe('Второе');
  });

  it('removes its region when the application is torn down', () => {
    TestBed.inject(LiveAnnouncerService);
    TestBed.resetTestingModule();

    expect(document.querySelector('.app-live-announcer')).toBeNull();
  });
});
