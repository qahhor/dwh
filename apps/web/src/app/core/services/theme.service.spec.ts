import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

/** Minimal stand-in that records what a tab announces and can deliver a remote message. */
class FakeBroadcastChannel {
  static last: FakeBroadcastChannel | null = null;

  readonly posted: unknown[] = [];
  closed = false;
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;

  constructor(readonly name: string) {
    FakeBroadcastChannel.last = this;
  }

  postMessage(data: unknown): void {
    this.posted.push(data);
  }

  close(): void {
    this.closed = true;
  }

  /** Simulate another tab announcing a choice. */
  deliver(data: unknown): void {
    this.onmessage?.({ data } as MessageEvent<unknown>);
  }
}

type Globals = Omit<typeof globalThis, 'BroadcastChannel'> & { BroadcastChannel?: unknown };

describe('ThemeService cross-tab synchronization', () => {
  const globals = globalThis as unknown as Globals;
  let original: unknown;

  beforeEach(() => {
    original = globals.BroadcastChannel;
    FakeBroadcastChannel.last = null;
    localStorage.removeItem('dwh_theme');
    globals.BroadcastChannel = FakeBroadcastChannel;
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    globals.BroadcastChannel = original;
    localStorage.removeItem('dwh_theme');
  });

  function create(): { service: ThemeService; channel: FakeBroadcastChannel } {
    const service = TestBed.inject(ThemeService);
    const channel = FakeBroadcastChannel.last;
    expect(channel).not.toBeNull();
    return { service, channel: channel as FakeBroadcastChannel };
  }

  it('announces a locally chosen theme to other tabs', () => {
    const { service, channel } = create();

    service.setTheme('dark');

    expect(service.themePreference()).toBe('dark');
    expect(channel.posted).toEqual(['dark']);
  });

  it('announces the theme a toggle resolves to', () => {
    const { service, channel } = create();

    service.setTheme('light');
    channel.posted.length = 0;
    service.toggleTheme();

    expect(service.themePreference()).toBe('dark');
    expect(channel.posted).toEqual(['dark']);
  });

  it('applies a theme announced by another tab', () => {
    const { service, channel } = create();

    channel.deliver('dark');

    expect(service.themePreference()).toBe('dark');
  });

  it('does not re-announce a theme received from another tab', () => {
    const { service, channel } = create();

    channel.deliver('dark');

    expect(service.themePreference()).toBe('dark');
    expect(channel.posted).toEqual([]);
  });

  it('ignores an unknown payload', () => {
    const { service, channel } = create();
    service.setTheme('light');

    for (const payload of ['sepia', null, undefined, 42, { theme: 'dark' }]) {
      channel.deliver(payload);
    }

    expect(service.themePreference()).toBe('light');
  });

  it('keeps working when the platform has no BroadcastChannel', () => {
    globals.BroadcastChannel = undefined;

    const service = TestBed.inject(ThemeService);
    expect(() => service.setTheme('dark')).not.toThrow();
    expect(service.themePreference()).toBe('dark');
  });

  it('closes the channel when the injector is destroyed', () => {
    const { channel } = create();

    TestBed.resetTestingModule();

    expect(channel.closed).toBe(true);
  });
});
