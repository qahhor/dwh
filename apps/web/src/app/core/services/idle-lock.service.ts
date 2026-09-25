import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { Subscription, interval } from 'rxjs';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { TabSyncService } from './tab-sync.service';

/** How long before the end a person is warned, in seconds. */
export const IDLE_WARNING_SECONDS = 60;
/** Activity is told to the other tabs at most this often, so they do not close a session in use. */
const ANNOUNCE_EVERY_MS = 30_000;
const ACTIVITY_EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;

/**
 * Closes a session nobody uses (roadmap item 28, an idea from kernel): after
 * the instance's `security.idle_lock_minutes` without a click, key or scroll
 * in any tab of the browser, the person is signed out. A minute before, a
 * warning counts down and offers to go on; any activity counts as going on.
 * The server session is ended too, so a forgotten screen does not stay open
 * to whoever sits down next. 0 minutes switches the lock off.
 */
@Injectable({ providedIn: 'root' })
export class IdleLockService {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly tabs = inject(TabSyncService);

  /** Seconds left while the warning is up; null otherwise. */
  readonly warningSeconds = signal<number | null>(null);

  private limitMs = 0;
  private lastActivity = Date.now();
  private lastAnnounced = 0;
  private readonly subscriptions: Subscription[] = [];
  private readonly onActivity = () => this.touch(true);
  private running = false;

  constructor() {
    effect(() => {
      const signedIn = this.auth.isAuthenticated();
      untracked(() => (signedIn ? this.start() : this.stop()));
    });
  }

  /** "Go on": the person answered the warning. */
  keepWorking(): void {
    this.touch(true);
  }

  private start(): void {
    if (this.running) return;
    this.running = true;
    this.api.get<{ idleLockMinutes: number }>('/settings/session', undefined, { notifyError: false }).subscribe({
      next: settings => {
        if (!this.running || !(settings.idleLockMinutes > 0)) return;
        this.limitMs = settings.idleLockMinutes * 60_000;
        this.watch();
      },
      error: () => undefined
    });
  }

  private watch(): void {
    this.lastActivity = Date.now();
    if (typeof document !== 'undefined') {
      for (const name of ACTIVITY_EVENTS) document.addEventListener(name, this.onActivity, { passive: true, capture: true });
    }
    this.subscriptions.push(
      this.tabs.messages.subscribe(message => {
        if (message.kind === 'activity') this.touch(false);
      }),
      interval(1000).subscribe(() => this.tick())
    );
  }

  private stop(): void {
    this.running = false;
    this.limitMs = 0;
    this.warningSeconds.set(null);
    if (typeof document !== 'undefined') {
      for (const name of ACTIVITY_EVENTS) document.removeEventListener(name, this.onActivity, { capture: true });
    }
    this.subscriptions.splice(0).forEach(subscription => subscription.unsubscribe());
  }

  /** Activity here (announced to the other tabs now and then) or in another tab (not announced again). */
  private touch(here: boolean): void {
    const now = Date.now();
    this.lastActivity = now;
    if (this.warningSeconds() !== null) this.warningSeconds.set(null);
    if (here && now - this.lastAnnounced >= ANNOUNCE_EVERY_MS) {
      this.lastAnnounced = now;
      this.tabs.publish({ kind: 'activity' });
    }
  }

  private tick(): void {
    if (!this.limitMs) return;
    const leftMs = this.limitMs - (Date.now() - this.lastActivity);
    if (leftMs <= 0) {
      this.stop();
      this.auth.logout('idle');
      return;
    }
    const seconds = Math.ceil(leftMs / 1000);
    this.warningSeconds.set(seconds <= IDLE_WARNING_SECONDS ? seconds : null);
  }
}
