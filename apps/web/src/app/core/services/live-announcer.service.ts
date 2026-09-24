import { DOCUMENT } from '@angular/common';
import { Injectable, OnDestroy, inject } from '@angular/core';

/** How long the region stays empty before the new text, so a repeat of the same text is read again. */
export const ANNOUNCE_DELAY_MS = 100;

/**
 * A polite live region for screen reader announcements. Screen readers read
 * changes inside a live region that is already in the page; a region added
 * together with its text is often skipped. So the region is created once,
 * up front, and each announcement empties it and then writes the text.
 *
 * The same technique as CDK LiveAnnouncer, without pulling all of
 * `@angular/cdk/a11y` (about 27 kB) into the initial bundle.
 */
@Injectable({ providedIn: 'root' })
export class LiveAnnouncerService implements OnDestroy {
  private readonly document = inject(DOCUMENT);
  private readonly region: HTMLElement;
  private pending: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    const region = this.document.createElement('div');
    region.className = 'app-live-announcer';
    region.setAttribute('aria-live', 'polite');
    region.setAttribute('aria-atomic', 'true');
    Object.assign(region.style, {
      position: 'absolute',
      width: '1px',
      height: '1px',
      margin: '-1px',
      padding: '0',
      overflow: 'hidden',
      clip: 'rect(0 0 0 0)',
      whiteSpace: 'nowrap',
      border: '0',
    });
    this.document.body.appendChild(region);
    this.region = region;
  }

  announce(message: string): void {
    if (this.pending) clearTimeout(this.pending);
    this.region.textContent = '';
    this.pending = setTimeout(() => {
      this.pending = null;
      this.region.textContent = message;
    }, ANNOUNCE_DELAY_MS);
  }

  ngOnDestroy(): void {
    if (this.pending) clearTimeout(this.pending);
    this.region.remove();
  }
}
