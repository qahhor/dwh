import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, output } from '@angular/core';
import { QueryCondition, QueryListMeta } from '../../core/models/query-meta.models';
import { I18nService, TranslatePipe } from '../../core/services/i18n.service';
import { describeCondition, filterableFields } from '../list-views/filter-conditions';
import { SMTDrawerService } from '../ui-kit/components/drawer';
import { FilterPanelData, UiFilterPanelComponent } from './ui-filter-panel.component';

/**
 * The filter of a list above its table: a "Filter" button with the number of
 * conditions, which opens the builder in a side drawer, and one chip per
 * active condition, each with its own remove button, plus "Clear all". The
 * list itself is reloaded by whoever owns the conditions.
 */
@Component({
  selector: 'ui-filter-bar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  template: `
    @if (hasFields()) {
      <button type="button" class="filter-trigger" data-testid="filter-trigger" aria-haspopup="dialog" (click)="open()">
        <span class="material-symbols-outlined" aria-hidden="true">filter_list</span>
        <span>{{ 'ui.filter.button' | t }}</span>
        @if (conditions().length > 0) {
          <span class="filter-count" data-testid="filter-count">
            <span aria-hidden="true">{{ conditions().length }}</span>
            <span class="sr-only">{{ 'ui.filter.active_count' | t: { count: conditions().length } }}</span>
          </span>
        }
      </button>
    }
    @if (conditions().length > 0) {
      <ul class="filter-chips" [attr.aria-label]="'ui.filter.active' | t">
        @for (chip of chips(); track $index; let i = $index) {
          <li class="filter-chip" data-testid="filter-chip">
            <span class="filter-chip-text">{{ chip }}</span>
            <button type="button" class="filter-chip-remove" [attr.aria-label]="'ui.filter.remove_chip' | t: { condition: chip }" (click)="removeAt(i)">
              <span class="material-symbols-outlined" aria-hidden="true">close</span>
            </button>
          </li>
        }
        <li>
          <button type="button" class="filter-clear" data-testid="filter-clear-all" (click)="conditionsChange.emit([])">
            {{ 'ui.filter.clear' | t }}
          </button>
        </li>
      </ul>
    }
  `,
  styles: [`
    :host { display: flex; flex: 1; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; }
    .filter-trigger {
      display: inline-flex; align-items: center; gap: 6px; min-height: var(--control-height); padding: 0 12px;
      border: 1px solid var(--border-color); border-radius: var(--radius-md); background: var(--bg-surface);
      color: var(--text-main); font-size: 13px; cursor: pointer;
    }
    .filter-trigger:hover { background: var(--bg-hover); color: var(--text-main); }
    .filter-trigger:focus-visible, .filter-chip-remove:focus-visible, .filter-clear:focus-visible {
      outline: 2px solid var(--focus-ring); outline-offset: 2px;
    }
    .filter-trigger .material-symbols-outlined { font-size: 18px; }
    .filter-count {
      display: inline-flex; align-items: center; justify-content: center; min-width: 20px; height: 20px; padding: 0 6px;
      border-radius: 999px; background: var(--primary); color: var(--on-primary); font-size: 12px; font-weight: 600;
    }
    .filter-chips { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 0; padding: 0; list-style: none; }
    .filter-chip {
      display: inline-flex; align-items: center; gap: 2px; max-width: 320px; padding: 2px 4px 2px 10px;
      border: 1px solid var(--primary-border); border-radius: 999px; background: var(--primary-subtle);
      color: var(--primary-text); font-size: 12px;
    }
    .filter-chip-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .filter-chip-remove {
      display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border: none;
      border-radius: 50%; background: none; color: var(--primary-text); cursor: pointer;
    }
    .filter-chip-remove .material-symbols-outlined { font-size: 16px; }
    .filter-clear { padding: 2px 6px; border: none; background: none; color: var(--primary-text); font-size: 12px; cursor: pointer; }
  `],
})
export class UiFilterBarComponent {
  private readonly i18n = inject(I18nService);
  private readonly drawer = inject(SMTDrawerService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly meta = input.required<QueryListMeta>();

  readonly conditions = input<QueryCondition[]>([]);

  readonly conditionsChange = output<QueryCondition[]>();

  readonly hasFields = computed(() => filterableFields(this.meta()).length > 0);
  readonly chips = computed(() =>
    this.conditions().map(condition => describeCondition(condition, this.meta(), key => this.i18n.translate(key))));

  open(): void {
    const ref = this.drawer.open<QueryCondition[], FilterPanelData>(UiFilterPanelComponent, {
      title: this.i18n.translate('ui.filter.title'),
      width: '560px',
      closeOnBackdropClick: true,
      data: { meta: this.meta(), conditions: this.conditions() },
    });
    ref.afterClosed().subscribe(conditions => {
      if (conditions) this.conditionsChange.emit(conditions);
    });
  }

  /** The removed chip takes its button along, so focus moves to the next chip, or to the Filter button. */
  removeAt(index: number): void {
    this.conditionsChange.emit(this.conditions().filter((_, i) => i !== index));
    setTimeout(() => {
      const removes = this.host.nativeElement.querySelectorAll<HTMLElement>('.filter-chip-remove');
      const next = removes[Math.min(index, removes.length - 1)];
      (next ?? this.host.nativeElement.querySelector<HTMLElement>('[data-testid="filter-trigger"]'))?.focus();
    });
  }
}
