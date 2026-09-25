import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { IdleLockService } from './idle-lock.service';
import { TabSyncMessage, TabSyncService } from './tab-sync.service';

describe('IdleLockService', () => {
  const signedIn = signal(false);
  const auth = { isAuthenticated: signedIn.asReadonly(), logout: vi.fn() };
  const messages = new Subject<TabSyncMessage>();
  const tabs = { messages: messages.asObservable(), publish: vi.fn() };
  let minutes: number | 'error' = 2;
  const api = {
    get: vi.fn(() => (minutes === 'error' ? throwError(() => new Error('down')) : of({ idleLockMinutes: minutes })))
  };
  let idle: IdleLockService;

  beforeEach(() => {
    vi.useFakeTimers();
    signedIn.set(false);
    minutes = 2;
    auth.logout.mockClear();
    tabs.publish.mockClear();
    api.get.mockClear();
    TestBed.configureTestingModule({ providers: [
      { provide: AuthService, useValue: auth },
      { provide: TabSyncService, useValue: tabs },
      { provide: ApiService, useValue: api }
    ] });
    idle = TestBed.inject(IdleLockService);
  });

  afterEach(() => {
    signedIn.set(false);
    TestBed.tick();
    vi.useRealTimers();
  });

  function signIn(): void {
    signedIn.set(true);
    TestBed.tick();
  }

  it('does nothing until someone signs in', () => {
    TestBed.tick();
    vi.advanceTimersByTime(10 * 60_000);
    expect(api.get).not.toHaveBeenCalled();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('warns a minute before the end and signs out as idle when nobody answers', () => {
    signIn();
    expect(api.get).toHaveBeenCalledWith('/settings/session', undefined, { notifyError: false });

    vi.advanceTimersByTime(59_000);
    expect(idle.warningSeconds()).toBeNull();
    vi.advanceTimersByTime(2_000);
    expect(idle.warningSeconds()).toBe(59);

    vi.advanceTimersByTime(59_000);
    expect(auth.logout).toHaveBeenCalledWith('idle');
    expect(idle.warningSeconds()).toBeNull();
    vi.advanceTimersByTime(5 * 60_000);
    expect(auth.logout).toHaveBeenCalledTimes(1);
  });

  it('starts over on a key press here and tells the other tabs, but not on every event', () => {
    signIn();
    vi.advanceTimersByTime(90_000);
    expect(idle.warningSeconds()).not.toBeNull();

    document.dispatchEvent(new KeyboardEvent('keydown'));
    expect(idle.warningSeconds()).toBeNull();
    expect(tabs.publish).toHaveBeenCalledWith({ kind: 'activity' });
    document.dispatchEvent(new PointerEvent('pointerdown'));
    expect(tabs.publish).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(90_000);
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('keeps the session while another tab is in use, without echoing it back', () => {
    signIn();
    vi.advanceTimersByTime(100_000);
    messages.next({ kind: 'activity' });
    expect(idle.warningSeconds()).toBeNull();
    vi.advanceTimersByTime(100_000);
    expect(auth.logout).not.toHaveBeenCalled();
    expect(tabs.publish).not.toHaveBeenCalled();
  });

  it('"Go on" closes the warning', () => {
    signIn();
    vi.advanceTimersByTime(80_000);
    idle.keepWorking();
    expect(idle.warningSeconds()).toBeNull();
    vi.advanceTimersByTime(80_000);
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it.each([0, 'error'] as const)('stays off when the instance says %s', value => {
    minutes = value;
    signIn();
    vi.advanceTimersByTime(24 * 60 * 60_000);
    expect(auth.logout).not.toHaveBeenCalled();
    expect(idle.warningSeconds()).toBeNull();
  });

  it('stops watching after sign-out and starts afresh on the next sign-in', () => {
    signIn();
    vi.advanceTimersByTime(90_000);
    signedIn.set(false);
    TestBed.tick();
    expect(idle.warningSeconds()).toBeNull();
    vi.advanceTimersByTime(5 * 60_000);
    expect(auth.logout).not.toHaveBeenCalled();

    signIn();
    expect(api.get).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(61_000);
    expect(idle.warningSeconds()).toBe(59);
  });
});
