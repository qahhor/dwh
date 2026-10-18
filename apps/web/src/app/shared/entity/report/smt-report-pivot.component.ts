import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { fieldLabel, type QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { RefLookups } from '@shared/lookups/ref-lookup';
import { drillFilter, measureLabel, type ReportResult, type ReportRow, type ReportTotal } from './entity-reports';
import { ReportWords } from './report-words';

/** One cell of the matrix: the figures of its measures, the records behind it when the list can say them exactly. */
interface PivotCell {
  texts: string[];
  drill: string | null;
  name: string;
}

interface PivotLine {
  key: string;
  label: string;
  cells: PivotCell[];
  total: PivotCell;
}

let nextPivotId = 0;

/**
 * A pivot on screen (ADR-0044): the first grouping down, the second across, the measures under each column, the rows'
 * totals at the right and the columns' totals and the grand total at the bottom — one line per currency of money. The
 * headers are table headers with their scope, so a screen reader names a cell by its row and column; the region scrolls
 * with the keyboard. A figure opens its records (drill-down) when the list can say its row and column exactly — never
 * more records than the figure counts. On a narrow screen each row becomes a card of its columns.
 */
@Component({
  selector: 'smt-report-pivot',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslatePipe],
  host: { class: 'smt-report-pivot' },
  templateUrl: './smt-report-pivot.component.html',
  styleUrl: './smt-report-pivot.component.css',
})
export class SMTReportPivotComponent {
  private readonly i18n = inject(I18nService);

  readonly result = input.required<ReportResult>();
  readonly meta = input.required<QueryListMeta | null>();
  readonly caption = input.required<string>();

  /** The entity whose records a figure opens; none — the figures open nothing. */
  readonly drillCode = input<string | null>(null);
  readonly drillBase = input<readonly unknown[]>([]);

  readonly rowHeader = computed(() => this.groupHeader(this.places().row));
  readonly columnHeader = computed(() => this.groupHeader(this.places().column));

  readonly measures = computed(() => {
    this.i18n.currentLang();
    return this.result().measures.map((measure) =>
      measureLabel(measure, this.meta(), (key) => this.i18n.translate(key)),
    );
  });

  /** The columns in the server's order, each once whatever its currency. */
  readonly columns = computed(() => {
    const result = this.result();
    const at = this.places().column;
    const seen = new Set<string>();
    const columns: { key: string; label: string }[] = [];
    for (const total of this.totals('column')) {
      const key = JSON.stringify(total.groups[at] ?? null);
      if (seen.has(key)) continue;
      seen.add(key);
      columns.push({ key, label: this.words.group(result, this.meta(), total.groups[at], at, total.labels?.[at]) });
    }
    return columns;
  });

  readonly rows = computed<PivotLine[]>(() => {
    const result = this.result();
    const { row, column } = this.places();
    const cells = new Map(result.rows.map((cell) => [this.rowKey(cell) + '|' + this.cellColumn(cell, column), cell]));
    return this.totals('row').map((line) => {
      const key = this.rowKey(line);
      const label = this.lineLabel(line, row);
      return {
        key,
        label,
        cells: this.columns().map((col) => this.cell(cells.get(key + '|' + col.key) ?? null, label, col.label)),
        total: this.cell(line, label, this.i18n.translate('ui.report.total')),
      };
    });
  });

  /** The columns' totals and the grand total, one line per currency of money. */
  readonly footers = computed<PivotLine[]>(() => {
    const { column, implicit } = this.places();
    const columnTotals = this.totals('column');
    const total = this.i18n.translate('ui.report.total');
    return this.totals('grand').map((grand) => {
      const currency = implicit.map((index) => JSON.stringify(grand.groups[index] ?? null)).join('|');
      const label = [total, ...implicit.map((index) => String(grand.groups[index] ?? ''))].filter(Boolean).join(' · ');
      return {
        key: currency,
        label,
        cells: this.columns().map((col) => {
          const match = columnTotals.find(
            (candidate) =>
              JSON.stringify(candidate.groups[column] ?? null) === col.key &&
              implicit.map((index) => JSON.stringify(candidate.groups[index] ?? null)).join('|') === currency,
          );
          return this.cell(match ?? null, label, col.label);
        }),
        total: this.cell(grand, label, total),
      };
    });
  });

  /** Where the row grouping, the column grouping and the currency of money stand in a row. */
  private readonly places = computed(() => {
    const groups = this.result().groups;
    const explicit = groups.map((group, index) => (group.implicit ? -1 : index)).filter((index) => index >= 0);
    const implicit = groups.map((group, index) => (group.implicit ? index : -1)).filter((index) => index >= 0);
    return { row: explicit[0] ?? 0, column: explicit[1] ?? 1, implicit };
  });

  private readonly words = new ReportWords(this.i18n, inject(RefLookups));

  readonly ids = `report-pivot-${nextPivotId++}`;

  private totals(kind: ReportTotal['kind']): ReportTotal[] {
    return (this.result().totals ?? []).filter((total) => total.kind === kind);
  }

  private groupHeader(index: number): string {
    const group = this.result().groups[index];
    const field = group ? this.words.field(this.meta(), group.field) : undefined;
    return field ? fieldLabel(field, (key) => this.i18n.translate(key)) : (group?.field ?? '');
  }

  private rowKey(row: ReportRow): string {
    const { row: at, implicit } = this.places();
    return [at, ...implicit].map((index) => JSON.stringify(row.groups[index] ?? null)).join('|');
  }

  private cellColumn(cell: ReportRow, column: number): string {
    return JSON.stringify(cell.groups[column] ?? null);
  }

  /** A row's words with the currency of money. */
  private lineLabel(line: ReportRow, at: number): string {
    const result = this.result();
    const parts = [this.words.group(result, this.meta(), line.groups[at], at, line.labels?.[at])];
    for (const index of this.places().implicit) parts.push(String(line.groups[index] ?? ''));
    return parts.filter(Boolean).join(' · ');
  }

  private cell(row: ReportRow | null, rowLabel: string, columnLabel: string): PivotCell {
    const result = this.result();
    const texts = result.measures.map((measure, index) => {
      if (!row) return '';
      const value = row.values[index] ?? null;
      return measure.share ? this.words.percent(value) : this.words.number(value);
    });
    const meta = this.meta();
    const filter = row && meta && this.drillCode() ? drillFilter(result, row, meta, this.drillBase()) : null;
    return { texts, drill: filter ? JSON.stringify(filter) : null, name: `${rowLabel} · ${columnLabel}` };
  }
}
