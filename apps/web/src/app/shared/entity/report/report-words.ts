import type { QueryFieldMeta, QueryListMeta } from '@core/models/query-meta.models';
import type { I18nService } from '@core/services/i18n.service';
import type { RefLookups } from '@shared/lookups/ref-lookup';
import { groupText, type ReportResult, type ReportRow } from './entity-reports';

/**
 * The words of a report's answer, shared by its table, its chart and its pivot (ADR-0032 10.2; ADR-0044): a group value
 * by the server's label, else a choice by its label, a reference by its name, a date bucket by its period; a figure in
 * the language's format, an empty one as a dash.
 */
export class ReportWords {
  constructor(
    private readonly i18n: I18nService,
    private readonly refLookups: RefLookups,
  ) {}

  field(meta: QueryListMeta | null, key: string): QueryFieldMeta | undefined {
    return meta?.fields.find((field) => field.key === key);
  }

  group(
    result: ReportResult | null,
    meta: QueryListMeta | null,
    value: string | number | boolean | null | undefined,
    index: number,
    label?: string | null,
  ): string {
    const group = result?.groups[index];
    if (!group) return '';
    this.i18n.currentLang();
    return groupText(
      value ?? null,
      this.field(meta, group.field),
      group.trunc,
      {
        translate: (key) => this.i18n.translate(key),
        date: (iso, options) => this.i18n.formatDate(iso, options),
        refName: (field, key) => (field.ref ? this.refLookups.name(field.ref, key) : null),
      },
      label,
    );
  }

  /** A row's groups in words, the currency of money too. */
  row(result: ReportResult | null, meta: QueryListMeta | null, row: ReportRow): string {
    return row.groups.map((value, index) => this.group(result, meta, value, index, row.labels?.[index])).join(' · ');
  }

  number(value: number | null | undefined): string {
    return value === null || value === undefined ? '—' : this.i18n.formatNumber(Number(value));
  }

  /** A share or a change in percent: "12,5 %". */
  percent(value: number | null | undefined): string {
    return value === null || value === undefined ? '—' : `${this.i18n.formatNumber(Number(value))} %`;
  }
}
