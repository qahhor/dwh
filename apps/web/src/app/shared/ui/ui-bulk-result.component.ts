import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslatePipe } from '../../core/services/i18n.service';
import { BulkResult, failedItems } from '../bulk/bulk';
import { UiButtonComponent } from './ui-button.component';
import { UiModalComponent } from './ui-modal.component';

/**
 * What a bulk action did when some records failed: how many changed, and for
 * each record that did not, its name and the reason the single operation gave.
 * A fully successful action needs only a toast, so this opens for failures.
 */
@Component({
  selector: 'ui-bulk-result',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, UiModalComponent, UiButtonComponent],
  template: `
    <ui-modal [isOpen]="!!result()" [title]="'ui.bulk.result_title' | t" size="md" (close)="closed.emit()">
      <div body class="bulk-result">
        @if (result(); as result) {
          <p class="bulk-result-summary" data-testid="bulk-result-summary">
            {{ 'ui.bulk.result_summary' | t: { succeeded: result.succeeded, failed: result.failed } }}
          </p>
          <ul class="bulk-result-list" [attr.aria-label]="'ui.bulk.failed_list' | t">
            @for (item of failures(); track item.id) {
              <li data-testid="bulk-result-failure">
                <span class="bulk-result-name">{{ itemLabel()(item.id) }}</span><span class="sr-only">: </span>
                <span class="bulk-result-reason">{{ item.message || item.code }}</span>
              </li>
            }
          </ul>
        }
      </div>
      <div footer class="bulk-result-footer">
        <ui-button variant="primary" (onClick)="closed.emit()">{{ 'common.close' | t }}</ui-button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .bulk-result { display: flex; flex-direction: column; gap: 10px; }
    .bulk-result-summary { margin: 0; color: var(--text-main); }
    .bulk-result-list { display: flex; flex-direction: column; gap: 6px; max-height: 50vh; margin: 0; padding: 0; overflow-y: auto; list-style: none; }
    .bulk-result-list li {
      display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; border: 1px solid var(--danger-border);
      border-radius: var(--radius-sm); background: var(--danger-bg); color: var(--danger-text);
    }
    .bulk-result-name { font-weight: 600; }
    .bulk-result-reason { font-size: 13px; }
    .bulk-result-footer { display: flex; justify-content: flex-end; }
  `],
})
export class UiBulkResultComponent {
  readonly result = input<BulkResult | null>(null);
  /** How a record is named in the list, e.g. "#12 Fix the report". */
  readonly itemLabel = input<(id: number) => string>(id => `#${id}`);
  readonly closed = output<void>();

  readonly failures = computed(() => {
    const result = this.result();
    return result ? failedItems(result) : [];
  });
}
