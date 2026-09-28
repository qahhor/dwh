import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, input, output } from '@angular/core';
import { QueryCondition, QueryListMeta, QueryMatch } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { describeCondition, filterableFields } from '../list-views/filter-conditions';
import { SMTDrawerService } from '../ui-kit/components/drawer';
import { FilterPanelData, FilterPanelResult, UiFilterPanelComponent } from './ui-filter-panel.component';

/**
 * The filter of a list above its table: a "Filter" button with the number of
 * conditions, which opens the builder in a side drawer, and one chip per
 * active condition, each with its own remove button, plus "Clear all". The
 * list itself is reloaded by whoever owns the conditions.
 */
@Component({
  selector: 'ui-filter-bar',
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
        @if (match() === 'any' && conditions().length > 1) {
          <li class="filter-match" data-testid="filter-match-badge">{{ 'ui.filter.match_any' | t }}</li>
        }
        @for (chip of chips(); track $index; let i = $index) {
          <li class="filter-chip" data-testid="filter-chip">
            <span class="filter-chip-text">{{ chip }}</span>
            <button
              type="button"
              class="filter-chip-remove"
              [attr.aria-label]="'ui.filter.remove_chip' | t: { condition: chip }"
              (click)="removeAt(i)"
            >
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
  styleUrl: './ui-filter-bar.component.css',
})
export class UiFilterBarComponent {
  private readonly i18n = inject(I18nService);
  private readonly drawer = inject(SMTDrawerService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly meta = input.required<QueryListMeta>();

  readonly conditions = input<QueryCondition[]>([]);

  /** How the conditions combine (roadmap item 53). */
  readonly match = input<QueryMatch>('all');

  readonly conditionsChange = output<QueryCondition[]>();

  /** "Apply" in the builder: the conditions and how they combine, together. */
  readonly filterChange = output<FilterPanelResult>();

  readonly hasFields = computed(() => filterableFields(this.meta()).length > 0);
  readonly chips = computed(() =>
    this.conditions().map((condition) => describeCondition(condition, this.meta(), (key) => this.i18n.translate(key))),
  );

  open(): void {
    const ref = this.drawer.open<FilterPanelResult, FilterPanelData>(UiFilterPanelComponent, {
      title: this.i18n.translate('ui.filter.title'),
      width: '560px',
      closeOnBackdropClick: true,
      data: { meta: this.meta(), conditions: this.conditions(), match: this.match() },
    });
    ref.afterClosed().subscribe((result) => {
      if (result) this.filterChange.emit(result);
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
