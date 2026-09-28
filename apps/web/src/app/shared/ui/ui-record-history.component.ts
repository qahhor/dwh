import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription } from 'rxjs';

import { HistoryChange, HistoryEntry, RecordHistoryApi } from './record-history.api';

export type { HistoryChange, HistoryEntry };
import { I18nService, TranslatePipe } from '@core/services/i18n.service';

const PAGE_SIZE = 20;
const EVENT_KEYS: Record<HistoryEntry['event'], string> = {
  I: 'ui.history.event.created',
  U: 'ui.history.event.changed',
  D: 'ui.history.event.deleted',
};

let nextHistoryId = 0;

/**
 * The "History" section of a record card: who changed what and when, newest
 * first, from the audit log. It loads only when opened, pages with the
 * server's cursor ("Show more") and starts over when the card shows another
 * record. The server decides who may see it (the record's own right and data
 * scope); a refusal shows as an error with a retry, like any failed load.
 */
@Component({
  selector: 'ui-record-history',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, TranslatePipe],
  template: `
    <section class="record-history" [attr.aria-labelledby]="headingId">
      <h4 class="record-history__heading" [id]="headingId">
        <button
          type="button"
          class="record-history__toggle"
          data-testid="record-history-toggle"
          [attr.aria-expanded]="open()"
          [attr.aria-controls]="panelId"
          (click)="toggle()"
        >
          <span class="material-symbols-outlined" aria-hidden="true">{{ open() ? 'expand_less' : 'history' }}</span>
          {{ 'ui.history.title' | t }}
        </button>
      </h4>

      <div class="record-history__panel" [id]="panelId" [hidden]="!open()">
        @if (entries().length > 0) {
          <ol class="record-history__list" data-testid="record-history-list">
            @for (entry of entries(); track entry.id) {
              <li class="record-history__entry">
                <p class="record-history__meta">
                  <span class="record-history__event">{{ eventLabel(entry) }}</span>
                  <span>{{ authorOf(entry) }}</span>
                  <time [attr.datetime]="entry.changedAt">{{ entry.changedAt | date: 'dd.MM.yyyy HH:mm' }}</time>
                  @if (entry.isApi) {
                    <span class="record-history__api">{{ 'ui.history.via_api' | t }}</span>
                  }
                </p>
                @if (entry.changes.length > 0) {
                  <dl class="record-history__changes">
                    @for (change of entry.changes; track change.field) {
                      <div class="record-history__change">
                        <dt>{{ fieldLabel(change) }}</dt>
                        <dd>
                          @if (entry.event === 'U') {
                            <span class="record-history__old">{{ show(change.oldValue) }}</span>
                            <span aria-hidden="true"> → </span>
                            <span class="sr-only">{{ 'ui.history.became' | t }}</span>
                            <span>{{ show(change.newValue) }}</span>
                          } @else {
                            {{ show(entry.event === 'D' ? change.oldValue : change.newValue) }}
                          }
                        </dd>
                      </div>
                    }
                  </dl>
                }
              </li>
            }
          </ol>
        }

        @if (loading()) {
          <p class="record-history__note" role="status">{{ 'ui.history.loading' | t }}</p>
        } @else if (failed()) {
          <p class="record-history__note record-history__note--error" role="alert" data-testid="record-history-error">
            {{ 'ui.history.load_error' | t }}
            <button type="button" class="record-history__link" (click)="load()">{{ 'common.retry' | t }}</button>
          </p>
        } @else if (loaded() && entries().length === 0) {
          <p class="record-history__note" data-testid="record-history-empty">{{ 'ui.history.empty' | t }}</p>
        } @else if (nextCursor()) {
          <button type="button" class="record-history__link" data-testid="record-history-more" (click)="load()">
            {{ 'ui.history.more' | t }}
          </button>
        }
      </div>
    </section>
  `,
  styleUrl: './ui-record-history.component.css',
})
export class UiRecordHistoryComponent {
  private readonly history = inject(RecordHistoryApi);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  /** The kind of record, as the server names it: `tasks`, `projects`, `users`. */
  readonly kind = input.required<string>();
  readonly recordId = input.required<number | string>();

  readonly open = signal(false);
  readonly entries = signal<HistoryEntry[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly loaded = signal(false);
  readonly nextCursor = signal<string | null>(null);

  readonly headingId = `record-history-heading-${nextHistoryId}`;
  readonly panelId = `record-history-panel-${nextHistoryId++}`;
  private request?: Subscription;

  constructor() {
    // Another record in the same card starts the history over; an open section loads it at once.
    effect(() => {
      this.kind();
      this.recordId();
      untracked(() => {
        this.request?.unsubscribe();
        this.entries.set([]);
        this.nextCursor.set(null);
        this.loaded.set(false);
        this.failed.set(false);
        this.loading.set(false);
        if (this.open()) this.load();
      });
    });
  }

  toggle(): void {
    this.open.update((open) => !open);
    if (this.open() && !this.loaded() && !this.loading()) this.load();
  }

  /** The first page, a retry after a failure, or the next page after the cursor. */
  load(): void {
    const cursor = this.nextCursor();
    this.loading.set(true);
    this.failed.set(false);
    this.request?.unsubscribe();
    this.request = this.history
      .page(this.kind(), this.recordId(), cursor, PAGE_SIZE)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.entries.update((list) => [...list, ...(page.items ?? [])]);
          this.nextCursor.set(page.hasMore ? (page.nextCursor ?? null) : null);
          this.loaded.set(true);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.failed.set(true);
        },
      });
  }

  eventLabel(entry: HistoryEntry): string {
    return this.i18n.translate(EVENT_KEYS[entry.event] ?? EVENT_KEYS.U);
  }

  authorOf(entry: HistoryEntry): string {
    if (!entry.changedByName) return this.i18n.translate('ui.history.system');
    return entry.changedByLogin ? `${entry.changedByName} (@${entry.changedByLogin})` : entry.changedByName;
  }

  fieldLabel(change: HistoryChange): string {
    return change.labelKey ? this.i18n.translate(change.labelKey) : change.field;
  }

  /** A value as a person reads it: empty as a dash, yes/no for flags, structures as compact JSON. */
  show(value: unknown): string {
    if (value === null || value === undefined || value === '') return '—';
    if (typeof value === 'boolean') return this.i18n.translate(value ? 'common.yes' : 'common.no');
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  }
}
