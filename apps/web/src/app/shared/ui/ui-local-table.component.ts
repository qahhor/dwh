import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal, TemplateRef } from '@angular/core';
import { I18nService } from '../../core/services/i18n.service';
import { SMTTableComponent } from '../ui-kit/components/table/table.component';
import { TableConfig } from '../ui-kit/components/table/table.types';
import { LocalSort, LocalSortValue, sortRows } from './local-sort';

/**
 * The kit table over a list that is loaded whole (settings, a card's rows): a
 * header click sorts every row, not a page, by the column's own value reader.
 * Only columns with a reader in \`sortValues\` offer sorting; switching sorting
 * off returns to the order the rows came in. Loading shows skeleton rows and
 * an empty list shows the screen's own empty state.
 */
@Component({
  selector: 'ui-local-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTTableComponent],
  template: `
    <smt-table
      [smtData]="sortedRows()"
      [smtConfig]="shownConfig()"
      [smtIsLoading]="loading()"
      [smtSkeletonRowCount]="5"
      [smtEmptyTemplate]="emptyTemplate()"
      [smtColumnResizeEnabled]="false"
      (smtSortChange)="sort.set($event ?? null)"
      (smtRowClick)="rowClick.emit($event)" />
  `,
  styles: [`:host { display: block; min-width: 0; }`],
})
export class UiLocalTableComponent<T> {
  private readonly i18n = inject(I18nService);

  readonly rows = input.required<readonly T[]>();
  readonly config = input.required<TableConfig<T>>();

  /** How each sortable column reads a row; a column without a reader does not sort. */
  readonly sortValues = input<Readonly<Record<string, (row: T) => LocalSortValue>>>({});
  readonly loading = input(false);
  readonly emptyTemplate = input<TemplateRef<unknown> | null>(null);

  readonly rowClick = output<T>();

  protected readonly sort = signal<LocalSort | null>(null);

  protected readonly sortedRows = computed(() =>
    sortRows(this.rows(), this.sort(), this.sortValues(), this.locale()));

  /** The config with sorting offered where a reader exists and the current direction shown. */
  protected readonly shownConfig = computed<TableConfig<T>>(() => {
    const config = this.config();
    const readers = this.sortValues();
    const sort = this.sort();
    const columns = Object.fromEntries(Object.entries(config.columns).map(([key, column]) => {
      const sortKey = column.key ?? key;
      return [key, {
        ...column,
        hasSorting: sortKey in readers,
        sortedBy: sort && sort.column === sortKey ? sort.sortBy : undefined,
      }];
    }));
    return { ...config, columns };
  });

  /** The reader's language for comparing text; a stand-in service without it falls back to Russian. */
  private locale(): string {
    const current = (this.i18n as Partial<I18nService>).currentLang;
    return typeof current === 'function' ? current() : 'ru';
  }
}
