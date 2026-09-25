import { DestroyRef, Injectable, inject } from '@angular/core';
import { Observable, Subject } from 'rxjs';

/** What one tab tells the others (roadmap item 27). */
export type TabSyncMessage =
  | { kind: 'signed-out' }
  | { kind: 'signed-in'; userId: number }
  | { kind: 'language'; code: string }
  /** Somebody is working in that tab: an idle lock elsewhere waits (roadmap item 28). */
  | { kind: 'activity' };

const CHANNEL = 'dwh-session';

/**
 * Keeps the open tabs of one browser in step (roadmap item 27, an idea from
 * kernel): signing out in one tab signs out the others, signing in wakes the
 * tabs still on the sign-in page, and a language change follows everywhere.
 * The theme has its own channel in ThemeService. A tab only announces its own
 * actions; what it receives it applies without announcing again, so messages
 * never bounce. Where the platform has no BroadcastChannel the tabs simply do
 * not sync — nothing else breaks.
 */
@Injectable({ providedIn: 'root' })
export class TabSyncService {
  private readonly incoming = new Subject<TabSyncMessage>();
  private readonly channel = this.open();

  /** Messages from the other tabs. */
  readonly messages: Observable<TabSyncMessage> = this.incoming.asObservable();

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.channel?.close();
      this.incoming.complete();
    });
  }

  publish(message: TabSyncMessage): void {
    try {
      this.channel?.postMessage(message);
    } catch {
      // A closed channel must not break what this tab is doing.
    }
  }

  private open(): BroadcastChannel | null {
    if (typeof BroadcastChannel === 'undefined') return null;
    try {
      const channel = new BroadcastChannel(CHANNEL);
      channel.onmessage = (event: MessageEvent<unknown>) => {
        if (isMessage(event.data)) this.incoming.next(event.data);
      };
      return channel;
    } catch {
      return null;
    }
  }
}

/** Only well-formed messages get through; anything else on the channel is ignored. */
function isMessage(data: unknown): data is TabSyncMessage {
  if (typeof data !== 'object' || data === null) return false;
  const message = data as { kind?: unknown; userId?: unknown; code?: unknown };
  switch (message.kind) {
    case 'signed-out':
    case 'activity':
      return true;
    case 'signed-in':
      return typeof message.userId === 'number';
    case 'language':
      return typeof message.code === 'string' && /^[a-z]{2,3}(-[a-zA-Z0-9]{2,8})*$/.test(message.code);
    default:
      return false;
  }
}
