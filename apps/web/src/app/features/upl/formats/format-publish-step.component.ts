import { Component, inject, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { UplFormatDraftRequest, UplFormatVersion } from '../upl-api';
import { UPL_FILE_KIND_KEY, UPL_VERSION_STATUS_KEY } from '../upl-labels';

/**
 * Шаг «Публикация» анкеты: сводка того, что будет опубликовано, и готовность.
 * Кнопка и диалог публикации — у редактора, шаг только объясняет, что произойдёт.
 */
@Component({
  selector: 'app-upl-format-publish-step',
  standalone: true,
  // Не OnPush: сводка читает изменяемую модель, которую правят соседние шаги.
  imports: [DatePipe, TranslatePipe],
  template: `
    <h2 class="upl-block-title">{{ 'upl.format.step.publish' | t }}</h2>
    <dl class="upl-review" data-testid="upl-review">
      <div>
        <dt>{{ 'upl.version.col.status' | t }}</dt>
        <dd>{{ statusKey() | t }}</dd>
      </div>
      <div>
        <dt>{{ 'upl.format.field.file_kind' | t }}</dt>
        <dd>{{ fileKindKey[model().fileKind ?? 'xlsx'] | t }}</dd>
      </div>
      <div>
        <dt>{{ 'upl.format.review.sheets' | t }}</dt>
        <dd data-testid="upl-review-sheets">{{ model().sheets.length }}</dd>
      </div>
      <div>
        <dt>{{ 'upl.format.review.columns' | t }}</dt>
        <dd data-testid="upl-review-columns">{{ columnCount() }}</dd>
      </div>
      @if (version()?.validFrom; as validFrom) {
        <div>
          <dt>{{ 'upl.version.valid_from' | t }}</dt>
          <dd>{{ validFrom | date: 'dd.MM.yyyy' }}</dd>
        </div>
      }
    </dl>

    @if (version()?.status === 'draft') {
      @if (errorCount() > 0) {
        <p class="upl-review-note upl-review-note--error" data-testid="upl-review-state">{{ 'upl.format.review.draft_errors' | t }}</p>
      } @else if (!hasColumns()) {
        <p class="upl-review-note" data-testid="upl-review-state">{{ 'upl.format.review.draft_empty' | t }}</p>
      } @else {
        <p class="upl-review-note" data-testid="upl-review-state">{{ 'upl.format.review.draft_ready' | t }}</p>
      }
      @if (dirty()) {
        <p class="upl-review-note" data-testid="upl-review-unsaved">{{ 'upl.format.review.unsaved' | t }}</p>
      }
      @if (previousValidFrom(); as previous) {
        <p class="upl-review-note">{{ text('upl.version.prev_valid_from', { date: previous }) }}</p>
      }
    } @else {
      <p class="upl-review-note" data-testid="upl-review-state">{{ 'upl.format.review.not_draft' | t }}</p>
    }
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 0.75rem; }
    .upl-block-title { margin: 0; font-size: 1rem; color: var(--text-main); }
    .upl-review { display: grid; grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr)); gap: 0.75rem; margin: 0; }
    .upl-review dt { color: var(--text-muted); font-size: 0.75rem; }
    .upl-review dd { margin: 0; color: var(--text-main); font-weight: 600; }
    .upl-review-note { margin: 0; color: var(--text-main); }
    .upl-review-note--error { color: var(--danger-text); }
  `]
})
export class FormatPublishStepComponent {
  private readonly i18n = inject(I18nService);

  readonly model = input.required<UplFormatDraftRequest>();

  readonly version = input<UplFormatVersion | null>(null);
  readonly errorCount = input(0);
  readonly dirty = input(false);
  readonly previousValidFrom = input<string | null>(null);

  readonly fileKindKey = UPL_FILE_KIND_KEY;

  text(key: string, params?: Record<string, string>): string {
    return this.i18n.translate(key, params);
  }

  statusKey(): string {
    const status = this.version()?.status;
    return status ? UPL_VERSION_STATUS_KEY[status] : '';
  }

  columnCount(): number {
    return this.model().sheets.reduce((total, sheet) => total + sheet.columns.length, 0);
  }

  /** Каждый лист с хотя бы одной колонкой — иначе публиковать нечего. */
  hasColumns(): boolean {
    const sheets = this.model().sheets;
    return sheets.length > 0 && sheets.every(sheet => sheet.columns.length > 0);
  }
}
