import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  TemplateRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { rxResource, takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, tap } from 'rxjs';
import type { ListQuery, QueryCondition, QueryListMeta } from '@core/models/query-meta.models';
import { canDo, hasCapability } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { QueryMetaService, parseSort } from '@core/services/query-meta.service';
import { RefLookups } from '@shared/lookups/ref-lookup';
import { ListViewState, ListViewsApi } from '@shared/list-views/list-views';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { registryTableConfig, sortFromHeader } from '@shared/ui/registry-table-config';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { UiServerTableComponent } from '@shared/ui/ui-server-table.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTRadioGroupComponent, type SMTRadioOption } from '@shared/ui-kit/components/forms/radio-group';
import type { ColumnContentType, OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';
import { TableColumnStateStore } from '@shared/ui-kit/services/table-column-state.store';
import { EntitiesApi, EntityRecord } from '../entities.api';
import { SMTEntityImportComponent } from '../smt-entity-import.component';
import { SMTEntityReportBuilderComponent } from '../report/smt-entity-report-builder.component';
import { SMTEntityToolbarComponent } from '../smt-entity-toolbar.component';
import { SMTEntityPageStateComponent } from './smt-entity-page-state.component';
import { EntityPageContext } from './smt-entity-page.component';

/**
 * The list of a declared entity, `/e/:code` (ADR-0032 7.1), drawn from its metadata alone: the columns, their order,
 * sorting, the filter and the export from `query-meta`; saved views, the archive switch, bulk archive and delete, the
 * import from a file (ADR-0032 10.1) and the "Create" button from `form-meta` — each only when the entity declares it
 * and the viewer holds its right. The first column opens the record; a cell of the entity's own comes from
 * `provideEntityOverrides`. The "Report" tab groups and totals the same list without code (ADR-0032 10.2):
 * `smt-entity-report-builder` under the same filter.
 */
@Component({
  selector: 'smt-entity-list-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    SMTButtonComponent,
    SMTEntityImportComponent,
    SMTEntityPageStateComponent,
    SMTEntityReportBuilderComponent,
    SMTEntityToolbarComponent,
    SMTInputComponent,
    SMTRadioGroupComponent,
    TranslatePipe,
    UiPageHeaderComponent,
    UiServerTableComponent,
  ],
  host: { class: 'smt-entity-list-page' },
  template: `
    <ui-page-header [title]="context.title()" [count]="pager.total()" countTestId="entity-count">
      @if (listMeta()) {
        <smt-radio-group
          smtAppearance="segmented"
          smtOrientation="horizontal"
          data-testid="entity-mode"
          [options]="modeOptions()"
          [value]="mode()"
          [smtAriaLabel]="'ui.report.mode' | t"
          (valueChange)="mode.set($event ?? 'list')"
        />
      }
      @if (searchable() && mode() === 'list') {
        <smt-input
          class="entity-search"
          type="search"
          smtIcon="search"
          clearable
          smtSize="sm"
          [placeholder]="'ui.entity_page.search' | t"
          [smtAriaLabel]="'ui.entity_page.search' | t"
          [value]="search()"
          (valueChange)="search.set($any($event) ?? '')"
        />
      }
      <smt-entity-toolbar [meta]="meta()" [views]="views" [listTools]="false" (bulkDone)="pager.reload()" />
      <smt-entity-import [meta]="meta()" (imported)="pager.reload()" />
      @if (canCreate()) {
        <a smt-button smtVariant="primary" smtIcon="add" routerLink="new" data-testid="entity-create">
          {{ 'common.create' | t }}
        </a>
      }
    </ui-page-header>

    @if (listFailed()) {
      <smt-entity-page-state kind="failed" (retry)="load()" />
    } @else if (mode() === 'report' && listMeta(); as meta) {
      <smt-entity-report-builder
        [code]="context.code()"
        [meta]="meta"
        [views]="views"
        [title]="context.title()"
        [canSave]="savedViews()"
      />
    } @else if (config(); as config) {
      <ui-server-table
        [pager]="pager"
        [config]="config"
        [views]="views"
        [savedViews]="savedViews()"
        [filterMeta]="listMeta()"
        [exportable]="exportable()"
        [exportSearch]="search()"
        [selectable]="selectable()"
        [lockedColumns]="lockedColumns()"
        [(selected)]="selectedRows"
        [loadingLabel]="'ui.entity_page.list_loading' | t"
        [errorLabel]="'ui.entity_page.list_failed' | t"
        [emptyTemplate]="empty"
        (sortChange)="sort($event)"
        (rowClick)="open($event)"
      >
        <smt-entity-toolbar
          bulkActions
          [meta]="meta()"
          [selected]="selectedIds()"
          [clearable]="false"
          (selectedChange)="$event.length === 0 && selectedRows.set([])"
          (bulkDone)="pager.reload()"
        />
      </ui-server-table>
    }

    <ng-template #openCell let-row>
      <a class="entity-open" [routerLink]="[row.id]" (click)="$event.stopPropagation()">{{ openText(row) }}</a>
    </ng-template>
    <ng-template #empty>
      <div class="entity-empty" data-testid="entity-empty">
        <span class="material-symbols-outlined entity-empty-icon" aria-hidden="true">inbox</span>
        <h3>{{ 'ui.entity_page.empty' | t }}</h3>
        <p>{{ (canCreate() ? 'ui.entity_page.empty_hint' : 'ui.entity_page.empty_hint_view') | t }}</p>
      </div>
    </ng-template>
  `,
  styles: [
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 16px;
        min-width: 0;
      }
      .entity-search {
        width: 220px;
      }
      .entity-open {
        color: var(--primary-text);
        font-weight: 500;
        text-decoration: none;
      }
      .entity-open:hover,
      .entity-open:focus-visible {
        text-decoration: underline;
      }
      .entity-empty {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 8px;
        padding: 32px;
        text-align: center;
      }
      .entity-empty h3,
      .entity-empty p {
        margin: 0;
      }
      .entity-empty-icon {
        font-size: 40px;
        color: var(--text-muted);
      }
    `,
  ],
})
export class SMTEntityListPageComponent {
  readonly context = inject(EntityPageContext);
  private readonly entities = inject(EntitiesApi);
  private readonly queryMeta = inject(QueryMetaService);
  private readonly refLookups = inject(RefLookups);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  private readonly openCell = viewChild.required<TemplateRef<unknown>>('openCell');

  readonly search = signal('');
  /** The list itself, or its report (ADR-0032 10.2). */
  readonly mode = signal<'list' | 'report'>('list');
  readonly selectedRows = signal<EntityRecord[]>([]);

  /** Raised by a retry: the list's metadata is asked for again. */
  private readonly metaRevision = signal(0);
  /** The default view has been applied: the list may be asked for. */
  private readonly viewsReady = signal(false);

  readonly listMeta = computed<QueryListMeta | null>(() => (this.metaRead.hasValue() ? this.metaRead.value() : null));
  readonly listFailed = computed(() => this.metaRead.status() === 'error');

  readonly meta = computed(() => this.context.formMeta());
  readonly selectedIds = computed(() => this.selectedRows().map((row) => row.id));

  readonly canCreate = computed(() => canDo(this.meta(), 'create'));
  readonly savedViews = computed(() => hasCapability(this.meta(), 'saved_views'));
  readonly exportable = computed(() => hasCapability(this.meta(), 'export'));
  /** Rows can be chosen when the entity has a bulk action the viewer may take. */
  readonly selectable = computed(
    () =>
      hasCapability(this.meta(), 'bulk') &&
      (canDo(this.meta(), 'delete') || (hasCapability(this.meta(), 'archive') && canDo(this.meta(), 'archive'))),
  );
  readonly modeOptions = computed<SMTRadioOption<'list' | 'report'>[]>(() => {
    this.i18n.currentLang();
    return [
      { value: 'list', label: this.i18n.translate('ui.report.mode_list'), icon: 'list' },
      { value: 'report', label: this.i18n.translate('ui.report.mode_report'), icon: 'bar_chart' },
    ];
  });
  readonly searchable = computed(() => (this.listMeta()?.fields ?? []).some((field) => field.searchable));

  readonly lockedColumns = computed(() => {
    const key = this.openKey();
    return key ? [key] : [];
  });

  readonly config = computed<TableConfig<EntityRecord> | null>(() => {
    const meta = this.listMeta();
    if (!meta) return null;
    this.i18n.currentLang();
    const cells: Record<string, ColumnContentType<EntityRecord>> = {};
    for (const [key, component] of Object.entries(this.context.overrides().cells ?? {})) {
      const field = meta.fields.find((candidate) => candidate.key === key);
      if (field) cells[key] = { type: 'component', value: { component, inputs: (row) => ({ row, field }) } };
    }
    const openKey = this.openKey();
    if (openKey && !cells[openKey]) cells[openKey] = { type: 'templateRef', value: this.openCell };
    return registryTableConfig<EntityRecord>(meta, {
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, row) => row.id,
      ariaLabel: this.context.title(),
      sort: this.views.sort(),
      cells,
      refName: (ref, key) => this.refLookups.name(ref, key),
    });
  });

  /** The column that names the row: the first shown, which also opens the record. */
  private readonly openKey = computed(
    () => this.listMeta()?.fields.find((field) => field.defaultVisible !== false)?.key ?? null,
  );

  /** The platform's own cells, for the text of the link that opens a record. */
  private readonly plain = computed<TableConfig<EntityRecord> | null>(() => {
    const meta = this.listMeta();
    if (!meta) return null;
    this.i18n.currentLang();
    return registryTableConfig<EntityRecord>(meta, {
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, row) => row.id,
      ariaLabel: '',
      sort: null,
      refName: (ref, key) => this.refLookups.name(ref, key),
    });
  });

  /** What the list asks for: the search, the sort and the filter of the view on screen. */
  private readonly query = computed<ListQuery>(() => ({
    search: this.searched().trim(),
    sort: this.views.sort(),
    conditions: this.views.filter(),
    match: this.views.match(),
  }));

  private readonly metaRead = rxResource({
    params: () => ({ code: this.meta().listCode ?? this.context.code(), revision: this.metaRevision() }),
    stream: ({ params }) => this.queryMeta.get(params.code),
  });

  /**
   * The saved views of the list. What the list asks for follows their sort and filter by itself (`query`), whoever
   * changes them — a view, the filter bar, the archive switch, a header click — so applying a view asks for nothing.
   */
  readonly views = new ListViewState(this.context.code(), inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.listMeta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => undefined,
    columnsStore: inject(TableColumnStateStore),
  });

  /** The search box, once the person stops typing. */
  private readonly searched = toSignal(toObservable(this.search).pipe(debounceTime(300), distinctUntilChanged()), {
    initialValue: '',
  });

  readonly pager = new KeysetPager<EntityRecord>(
    (cursor, limit) =>
      this.entities.page(this.context.code(), this.query(), cursor, limit).pipe(
        tap((page) => {
          const meta = this.listMeta();
          if (meta) {
            for (const item of page.items) {
              this.refLookups.seedRecord(item, meta.fields);
            }
          }
        }),
      ),
    { pageSize: 25, destroyRef: this.destroyRef },
  );

  constructor() {
    // Once the list's metadata is read: the person's default view, then the first page.
    effect(() => {
      const meta = this.listMeta();
      if (meta) untracked(() => this.applyViews(meta));
    });
    // A new query starts from the first page, once the default view is applied.
    effect(() => {
      this.query();
      if (this.viewsReady()) untracked(() => this.pager.first());
    });
  }

  /** Reads the list's metadata again after a failure; the views and the first page follow it. */
  load(): void {
    this.metaRevision.update((revision) => revision + 1);
  }

  /** A header click sorts the whole list on the server. */
  sort(event: { column: string; sortBy: OrderBy } | undefined): void {
    this.views.setSort(sortFromHeader(event));
  }

  open(row: EntityRecord): void {
    void this.router.navigate([this.context.listLink(), row.id]);
  }

  /** The text of the link that opens the record: the first column's value, or the record's number. */
  openText(row: EntityRecord): string {
    const key = this.openKey();
    const content = key ? this.plain()?.columns[key]?.content : undefined;
    const text = content?.type === 'primitive' ? content.value(row) : null;
    return text === null || text === undefined || text === '' || text === '—'
      ? this.i18n.translate('ui.entity_page.record', { id: row.id })
      : String(text);
  }

  private applyViews(meta: QueryListMeta): void {
    this.views
      .load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.applyLinkedFilter(meta);
        if (this.viewsReady()) this.pager.first();
        this.viewsReady.set(true);
      });
  }

  /**
   * A link to the list with a filter — `?filter=` with the conditions of the DSL (ADR-0016), as another screen links
   * to "the users of this role" — starts the list with it over the default view. Conditions on fields the list does
   * not have are dropped; the server checks the rest as for any filter.
   */
  private applyLinkedFilter(meta: QueryListMeta): void {
    const text = this.route.snapshot.queryParamMap.get('filter');
    if (!text) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      return;
    }
    if (!Array.isArray(parsed)) return;
    const keys = new Set(meta.fields.map((field) => field.key));
    const conditions = parsed.filter(
      (item): item is QueryCondition =>
        typeof item === 'object' &&
        item !== null &&
        typeof (item as QueryCondition).field === 'string' &&
        typeof (item as QueryCondition).op === 'string' &&
        keys.has((item as QueryCondition).field),
    );
    if (conditions.length > 0) this.views.setFilter(conditions, 'all');
  }
}
