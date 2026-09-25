import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark';
export type ThemePreference = 'light' | 'dark' | 'system';

/** Same-origin channel that keeps the chosen theme identical across open tabs. */
const THEME_CHANNEL = 'dwh_theme';

function isThemePreference(value: unknown): value is ThemePreference {
  return value === 'light' || value === 'dark' || value === 'system';
}

@Injectable({
  providedIn: 'root'
})
export class ThemeService {
  private readonly destroyRef = inject(DestroyRef);

  readonly themePreference = signal<ThemePreference>(this.getInitialPreference());
  readonly currentTheme = signal<ThemeMode>(this.resolveEffectiveTheme(this.getInitialPreference()));

  private readonly channel = this.openChannel();

  constructor() {
    this.destroyRef.onDestroy(() => this.channel?.close());

    effect(() => {
      const pref = this.themePreference();
      const resolved = this.resolveEffectiveTheme(pref);
      this.currentTheme.set(resolved);
      if (typeof document !== 'undefined') {
        document.documentElement.setAttribute('data-theme', resolved);
      }
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem('dwh_theme', pref);
      }
    });

    if (typeof window !== 'undefined' && window.matchMedia) {
      const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
      const listener = () => {
        if (this.themePreference() === 'system') {
          const resolved = mediaQuery.matches ? 'dark' : 'light';
          this.currentTheme.set(resolved);
          if (typeof document !== 'undefined') {
            document.documentElement.setAttribute('data-theme', resolved);
          }
        }
      };
      if (mediaQuery.addEventListener) {
        mediaQuery.addEventListener('change', listener);
      } else if ((mediaQuery as any).addListener) {
        (mediaQuery as any).addListener(listener);
      }
    }
  }

  toggleTheme() {
    const next: ThemeMode = this.currentTheme() === 'light' ? 'dark' : 'light';
    this.setTheme(next);
  }

  setTheme(theme: ThemePreference | ThemeMode) {
    this.themePreference.set(theme);
    this.publish(theme);
  }

  /**
   * Opens the cross-tab channel, or returns null where the platform has none
   * (server rendering, older browsers, some test environments). A missing
   * channel only costs the sync; the theme still applies in this tab.
   */
  private openChannel(): BroadcastChannel | null {
    if (typeof BroadcastChannel === 'undefined') return null;
    try {
      const channel = new BroadcastChannel(THEME_CHANNEL);
      channel.onmessage = (event: MessageEvent<unknown>) => this.receive(event.data);
      return channel;
    } catch {
      return null;
    }
  }

  /** A tab only announces its own choice; applying a remote one never re-announces. */
  private publish(theme: ThemePreference | ThemeMode): void {
    try {
      this.channel?.postMessage(theme);
    } catch {
      // A closed or unavailable channel must not break the local theme change.
    }
  }

  private receive(data: unknown): void {
    if (!isThemePreference(data) || data === this.themePreference()) return;
    this.themePreference.set(data);
  }

  private resolveEffectiveTheme(pref: ThemePreference): ThemeMode {
    if (pref === 'dark') return 'dark';
    if (pref === 'light') return 'light';
    if (typeof window !== 'undefined' && window.matchMedia) {
      return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }
    return 'light';
  }

  private getInitialPreference(): ThemePreference {
    if (typeof localStorage === 'undefined') return 'light';
    const saved = localStorage.getItem('dwh_theme');
    if (saved === 'dark' || saved === 'light' || saved === 'system') {
      return saved;
    }
    return 'light';
  }
}
