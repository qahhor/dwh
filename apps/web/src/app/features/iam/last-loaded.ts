import { WritableSignal, linkedSignal } from '@angular/core';

/**
 * What a read resource last delivered. Its stream yields null for a failed request, and the
 * resource has no value while it loads anew; both keep what the screen already shows, as the
 * hand-made subscriptions did. Writable, so a screen may edit the answer as a draft until the
 * next one arrives.
 */
export function lastLoaded<T>(value: () => T | null | undefined, initial: T): WritableSignal<T> {
  return linkedSignal<T | null | undefined, T>({
    source: value,
    computation: (next, previous) => next ?? previous?.value ?? initial,
  });
}
