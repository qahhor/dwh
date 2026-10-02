import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { forkJoin, map } from 'rxjs';
import type { QueryCondition, QueryListMeta } from '@core/models/query-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { RefLookups } from '../lookups/ref-lookup';
import { registryTableConfig } from '../ui/registry-table-config';
import type { ColumnContentType } from '../ui-kit/components/table/table.types';
import { EntitiesApi, type EntityRecord } from './entities.api';
import { momentText } from './entity-values';

/** The most related records the tab shows; the rest open as the related entity's list. */
const SHOWN = 20;

/** At most so many columns: the first of the related list's default columns. */
const COLUMNS = 4;

interface RelatedColumn {
  key: string;
  label: string;
  text: (row: EntityRecord) => string;
}

/**
 * A related list of a card (ADR-0032 9.3, plan 10/10, item 5.7): the records of another entity whose reference field
 * names this record, read through that entity's runtime list — its rights and scope decide what the viewer sees — with
 * the first columns of its list and a link to that list with the same filter. The server offers the tab only to a
 * viewer of that entity.
 */
@Component({
  selector: 'smt-entity-related',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, TranslatePipe],
  host: { class: 'smt-entity-related', '[attr.data-related]': 'entity()' },
  template: `
    @if (loaded.error()) {
      <p class="entity-related-note" role="alert">{{ 'ui.entity_page.related_failed' | t }}</p>
    } @else if (loaded.value(); as data) {
      @if (data.rows.length === 0) {
        <p class="entity-related-note">{{ 'ui.entity_page.related_empty' | t }}</p>
      } @else {
        <div class="entity-related-scroll">
          <table class="entity-related-table">
            <caption class="sr-only">
              {{
                caption()
              }}
            </caption>
            <thead>
              <tr>
                @for (column of data.columns; track column.key) {
                  <th scope="col">{{ column.label }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (row of data.rows; track row.id) {
                <tr [attr.data-record]="row.id">
                  @for (column of data.columns; track column.key; let first = $first) {
                    <td>
                      @if (first) {
                        <a [routerLink]="['/e', entity(), row.id]">{{ column.text(row) }}</a>
                      } @else {
                        {{ column.text(row) }}
                      }
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
      <a class="entity-related-all" [routerLink]="['/e', entity()]" [queryParams]="{ filter: filterText() }">
        {{ 'ui.entity_page.related_open_all' | t }}
      </a>
    } @else {
      <p class="sr-only" role="status">{{ 'common.loading' | t }}</p>
    }
  `,
  styles: [
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 8px;
        min-width: 0;
      }
      .entity-related-note {
        margin: 0;
        color: var(--text-secondary, inherit);
      }
      .entity-related-scroll {
        overflow-x: auto;
      }
      .entity-related-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 0.8125rem;
      }
      th,
      td {
        text-align: left;
        padding: 6px 8px;
        border-bottom: 1px solid var(--border-color, currentColor);
      }
      th {
        font-weight: 600;
      }
      a {
        color: var(--primary-text);
        text-decoration: underline;
      }
    `,
  ],
})
export class SMTEntityRelatedComponent {
  private readonly entities = inject(EntitiesApi);
  private readonly queryMeta = inject(QueryMetaService);
  private readonly i18n = inject(I18nService);
  private readonly refLookups = inject(RefLookups);

  /** The related entity, by code (`ms.tasks`). */
  readonly entity = input.required<string>();

  /** Its reference field that names this record (`projectId`). */
  readonly field = input.required<string>();

  /** The record of the card. */
  readonly recordId = input.required<number>();

  /** The tab's title: the table's caption. */
  readonly caption = input('');

  /** The condition of the related records: the reference field names this record. */
  readonly conditions = computed<QueryCondition[]>(() => [{ field: this.field(), op: 'eq', value: this.recordId() }]);

  /** The filter of the related entity's list that shows the same records. */
  readonly filterText = computed(() => JSON.stringify(this.conditions()));

  readonly loaded = rxResource({
    params: () => ({ entity: this.entity(), conditions: this.conditions() }),
    stream: ({ params }) =>
      forkJoin({
        meta: this.queryMeta.get(params.entity),
        page: this.entities.page(params.entity, { conditions: params.conditions }, null, SHOWN),
      }).pipe(map(({ meta, page }) => ({ columns: this.columns(meta), rows: page.items }))),
  });

  /** The first default columns of the related list, each value as the list shows it. */
  private columns(meta: QueryListMeta): RelatedColumn[] {
    const translate = (key: string) => this.i18n.translate(key);
    const config = registryTableConfig<EntityRecord>(meta, {
      translate,
      trackBy: (_index, row) => row.id,
      ariaLabel: this.caption(),
      sort: null,
      refName: (ref, key) => this.refLookups.name(ref, key),
    });
    return config.columnsOrder
      .filter((key) => key !== this.field())
      .slice(0, COLUMNS)
      .map((key) => {
        const column = config.columns[key];
        const header = column.header;
        return {
          key,
          label: header.type === 'primitive' ? String(header.value) : key,
          text: (row: EntityRecord) => cellText(column.content, row),
        };
      });
  }
}

/** A cell's value as text: a date or a moment as the card shows it, anything else as the list shows it. */
function cellText(content: ColumnContentType<EntityRecord>, row: EntityRecord): string {
  switch (content.type) {
    case 'primitive': {
      const value = content.value(row);
      return value === null || value === undefined || value === '' ? '—' : String(value);
    }
    case 'date':
    case 'date-time': {
      const value = content.value(row);
      return value ? momentText(value) : '—';
    }
    default:
      return '—';
  }
}
