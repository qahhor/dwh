import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, TemplateRef, computed, inject, signal, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ProblemDetail } from '../../../core/models/common.models';
import { QueryListMeta } from '../../../core/models/query-meta.models';
import { QueryMetaService, parseSort } from '../../../core/services/query-meta.service';
import { KeysetPager } from '../../../shared/paging/keyset-pager';
import { ListViewState, ListViewsApi } from '../../../shared/list-views/list-views';
import { TableColumnStateStore } from '../../../shared/ui-kit/services/table-column-state.store';
import { I18nService, TranslatePipe } from '../../../core/services/i18n.service';
import { SMTControlComponent } from '../../../shared/ui-kit/components/forms/control';
import { PermissionService } from '../../../core/services/permission.service';
import { ToastService } from '../../../core/services/toast.service';
import { UiBadgeComponent } from '../../../shared/ui/ui-badge.component';
import { SMTButtonComponent } from '../../../shared/ui-kit/components/button';
import { UiModalComponent } from '../../../shared/ui/ui-modal.component';
import { UiServerTableComponent } from '../../../shared/ui/ui-server-table.component';
import { registryTableConfig, sortFromHeader } from '../../../shared/ui/registry-table-config';
import { OrderBy, TableConfig } from '../../../shared/ui-kit/components/table/table.types';
import {
  UPL_PERIODICITIES,
  UPL_STRICTNESSES,
  UplApiService,
  UplPeriodicity,
  UplSourceItem,
  UplSourceRequest,
  UplStrictness
} from '../upl-api';
import { parseUplProblem, uplFieldErrorText } from '../formats/upl-format-errors';
import { UPL_PERIODICITY_KEY, UPL_STRICTNESS_KEY, uplProblemText } from '../upl-labels';
import { SMTAlertComponent } from '../../../shared/ui-kit/components/alert';
import { SMTInputComponent, SMTInputValueAccessor } from '../../../shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption, SMTSelectValueAccessor } from '../../../shared/ui-kit/components/forms/select';
import { optionsMemo } from '../../../shared/ui-kit/components/forms/radio-group/radio-options';

/** Модель окна «Новый источник»: обычный объект, чтобы работал `[(ngModel)]`. */
interface SourceCreateForm {
  code: string;
  name: string;
  ownerOrg: string;
  ownerContact: string;
  periodicity: UplPeriodicity;
  slaDays: number | null;
  reconciliationStrictness: UplStrictness;
}

const PAGE_SIZE = 50;
const CODE_PATTERN = /^[a-z][a-z0-9._-]{1,62}$/;
const NAME_MAX_LENGTH = 200;
const SLA_MIN = 0;
const SLA_MAX = 366;

function emptyForm(): SourceCreateForm {
  return {
    code: '',
    name: '',
    ownerOrg: '',
    ownerContact: '',
    periodicity: 'month',
    slaDays: 0,
    reconciliationStrictness: 'error'
  };
}

@Component({
  selector: 'app-upl-sources-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTInputComponent, SMTInputValueAccessor, SMTSelectComponent, SMTSelectValueAccessor,
    SMTAlertComponent, SMTControlComponent,
    CommonModule,
    FormsModule,
    RouterLink,
    TranslatePipe,
    SMTButtonComponent,
    UiModalComponent,
    UiBadgeComponent,
    UiServerTableComponent
  ],
  template: `
    <div class="upl-page">
      <div class="toolbar upl-toolbar">
        <h1 class="upl-title">
          {{ 'upl.list.title' | t }}
          @if (meta() && !isLoading() && !loadError()) {
            <span class="upl-count" data-testid="upl-count">{{ pager.total() }}</span>
          }
        </h1>
        @if (canCreate()) {
          <button smt-button type="button" smtVariant="primary" smtIcon="add" data-testid="upl-new-source" (click)="openCreate()">
            {{ 'upl.list.new' | t }}
          </button>
        }
      </div>

      @if (metaError()) {
        <smt-alert smtTone="danger" class="upl-alert" data-testid="upl-load-error">
          <span>{{ 'upl.list.load_error' | t }}</span>
          <button smt-button type="button" smtVariant="secondary" data-testid="upl-retry" (click)="load()">
            {{ 'upl.common.retry' | t }}
          </button>
        </smt-alert>
      } @else if (tableConfig(); as config) {
        <ui-server-table
          [pager]="pager"
          [config]="config"
          [views]="views"
          [filterMeta]="meta()"
          [exportable]="true"
          [lockedColumns]="['name']"
          [loadingLabel]="'upl.common.loading' | t"
          [errorLabel]="'upl.list.load_error' | t"
          [emptyTemplate]="emptyState"
          (sortChange)="onSort($event)" />
      } @else {
        <p class="upl-muted" role="status" data-testid="upl-meta-loading">{{ 'upl.common.loading' | t }}</p>
      }
    </div>

    <ng-template #emptyState>
      <div class="upl-empty" data-testid="upl-empty">
        <span class="material-symbols-outlined upl-empty-icon" aria-hidden="true">table_view</span>
        <p class="upl-empty-text">{{ 'upl.list.empty' | t }}</p>
        @if (canCreate()) {
          <button smt-button type="button" smtVariant="primary" data-testid="upl-empty-new" (click)="openCreate()">
            {{ 'upl.list.new' | t }}
          </button>
        }
      </div>
    </ng-template>
    <ng-template #codeCell let-item><code class="upl-code" data-testid="upl-source-row">{{ item.code }}</code></ng-template>
    <ng-template #nameCell let-item><a class="upl-link" [routerLink]="['/upl/sources', item.id]">{{ item.name }}</a></ng-template>
    <ng-template #draftCell let-item>
      @if (item.hasDraft) {
        <ui-badge variant="info">{{ 'upl.list.has_draft' | t }}</ui-badge>
      }
    </ng-template>

    <ui-modal
      [isOpen]="isCreateOpen()"
      [title]="'upl.source.new_title' | t"
      size="md"
      [hasFooter]="true"
      (close)="closeCreate()"
    >
      <form body id="upl-source-create" class="upl-form" (ngSubmit)="submitCreate()" novalidate>
        @if (createError()) {
          <smt-alert smtTone="danger" class="upl-alert" data-testid="upl-create-error">
            {{ createError()! | t }}
          </smt-alert>
        }

        <smt-control class="form-group" [smtLabel]="'upl.source.field.code' | t" [smtHint]="'upl.source.hint.code' | t" [smtError]="fieldErrorText('code')">
          <smt-input
            smtFieldId="upl-source-code"
            name="code"
            [maxLength]="63"
            [(ngModel)]="form.code" />
        </smt-control>

        <smt-control class="form-group" [smtLabel]="'upl.source.field.name' | t" [smtHint]="'upl.source.hint.name' | t" [smtError]="fieldErrorText('name')">
          <smt-input
            smtFieldId="upl-source-name"
            name="name"
            [maxLength]="200"
            [(ngModel)]="form.name" />
        </smt-control>

        <smt-control class="form-group" [smtLabel]="'upl.source.field.owner_org' | t" [smtHint]="'upl.source.hint.owner_org' | t" [smtError]="fieldErrorText('ownerOrg')">
          <smt-input
            smtFieldId="upl-source-owner-org"
            name="ownerOrg"
            [maxLength]="200"
            [(ngModel)]="form.ownerOrg" />
        </smt-control>

        <smt-control class="form-group" [smtLabel]="'upl.source.field.owner_contact' | t" [smtHint]="'upl.source.hint.owner_contact' | t" [smtError]="fieldErrorText('ownerContact')">
          <smt-input
            smtFieldId="upl-source-owner-contact"
            name="ownerContact"
            [maxLength]="200"
            [(ngModel)]="form.ownerContact" />
        </smt-control>

        <smt-control class="form-group" [smtLabel]="'upl.source.field.periodicity' | t" [smtHint]="'upl.source.hint.periodicity' | t">
          <smt-select
            smtTriggerId="upl-source-periodicity"
            name="periodicity"
            [options]="periodicityOptions()"
            [allowClear]="false"
            [(ngModel)]="form.periodicity"
          ></smt-select>
        </smt-control>

        <smt-control class="form-group" [smtLabel]="'upl.source.field.sla_days' | t" [smtHint]="'upl.source.hint.sla_days' | t" [smtError]="fieldErrorText('slaDays')">
          <smt-input
            smtFieldId="upl-source-sla-days"
            name="slaDays"
            type="number"
            [smtMin]="0"
            [smtMax]="366"
            [smtStep]="1"
            [(ngModel)]="form.slaDays" />
        </smt-control>

        <smt-control class="form-group" [smtLabel]="'upl.source.field.strictness' | t" [smtHint]="'upl.source.hint.strictness' | t">
          <smt-select
            smtTriggerId="upl-source-strictness"
            name="reconciliationStrictness"
            [options]="strictnessOptions()"
            [allowClear]="false"
            [(ngModel)]="form.reconciliationStrictness"
          ></smt-select>
        </smt-control>
      </form>

      <div footer class="upl-modal-footer">
        <button smt-button type="button" smtVariant="secondary" data-testid="upl-create-cancel" (click)="closeCreate()">
          {{ 'upl.common.cancel' | t }}
        </button>
        <button smt-button
          smtVariant="primary"
          type="submit"
          form="upl-source-create"
          [smtLoading]="isSaving()"
          data-testid="upl-create-submit"
        >{{ 'upl.source.create' | t }}</button>
      </div>
    </ui-modal>
  `,
  styles: [`
    .upl-page {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .upl-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
    }

    .upl-title {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: 0;
      font-family: var(--font-family);
      font-size: 1.25rem;
      color: var(--text-main);
    }

    .upl-count {
      padding: 0.125rem 0.5rem;
      border-radius: var(--radius-sm);
      background: var(--bg-hover);
      color: var(--text-muted);
      font-size: 0.875rem;
    }

    .upl-alert {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
    }

    .upl-code {
      font-family: monospace;
      color: var(--text-main);
    }

    .upl-link {
      color: var(--primary);
      text-decoration: none;
    }

    .upl-link:hover {
      text-decoration: underline;
    }

    .upl-empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.75rem;
      padding: 3rem 1rem;
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      background: var(--bg-surface);
    }

    .upl-empty-icon {
      font-size: 2.5rem;
      color: var(--text-light);
    }

    .upl-empty-text {
      margin: 0;
      color: var(--text-muted);
    }

    .upl-muted {
      color: var(--text-muted);
    }

    .upl-form {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .upl-hint {
      display: block;
      margin-top: 0.25rem;
      color: var(--text-muted);
      font-size: 0.8125rem;
    }

    .upl-field-error {
      display: block;
      margin-top: 0.25rem;
      color: var(--danger);
      font-size: 0.8125rem;
    }

    .upl-modal-footer {
      display: flex;
      justify-content: flex-end;
      gap: 0.5rem;
    }
  `]
})
export class SourcesListComponent implements OnInit {
  private readonly api = inject(UplApiService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /* A reload while "load more" is pending cancels it, so the old page is
     never appended to the refreshed list. */
  private readonly queryMeta = inject(QueryMetaService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly codeCell = viewChild.required<TemplateRef<unknown>>('codeCell');
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly draftCell = viewChild.required<TemplateRef<unknown>>('draftCell');

  /** Field metadata of the list (`query-meta/upl.sources`): columns, headers, what sorts. */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  readonly isCreateOpen = signal(false);
  readonly isSaving = signal(false);
  /** Значение — ключ i18n либо готовый текст сервера; в шаблоне всё равно идёт через `| t`. */
  readonly fieldErrors = signal<Record<string, string>>({});
  readonly createError = signal<string | null>(null);

  readonly tableConfig = computed<TableConfig<UplSourceItem> | null>(() => {
    const meta = this.meta();
    if (!meta) return null;
    return registryTableConfig<UplSourceItem>(meta, {
      translate: key => this.i18n.translate(key),
      trackBy: (_index, item) => item.id,
      ariaLabel: this.i18n.translate('upl.list.title'),
      sort: this.sort(),
      cells: {
        code: { type: 'templateRef', value: this.codeCell },
        name: { type: 'templateRef', value: this.nameCell },
        hasDraft: { type: 'templateRef', value: this.draftCell }
      }
    });
  });

  /** Where to go back after creating from another form's field; only known places, never a URL from the address bar. */
  private returnTo: 'packages' | null = null;

  /** Saved views own the columns and the sort; the list opens with the person's default view. */
  readonly views = new ListViewState('upl.sources', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.meta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.pager.first(),
    columnsStore: inject(TableColumnStateStore)
  });
  readonly sort = this.views.sort;

  /** The sort goes with every page, so a cursor always continues the query that issued it. */
  readonly pager = new KeysetPager<UplSourceItem>(
    (cursor, limit) => this.api.listSources(limit, cursor, { sort: this.sort(), conditions: this.views.filter() }),
    { pageSize: PAGE_SIZE, destroyRef: this.destroyRef }
  );
  readonly items = this.pager.items;
  readonly isLoading = this.pager.loading;
  readonly loadError = this.pager.failed;

  private readonly periodicityMemo = optionsMemo<SMTSelectOption<UplPeriodicity>[]>();
  private readonly strictnessMemo = optionsMemo<SMTSelectOption<UplStrictness>[]>();

  form: SourceCreateForm = emptyForm();

  /** Periodicities of a source; translated again when the language changes. */
  periodicityOptions(): SMTSelectOption<UplPeriodicity>[] {
    return this.periodicityMemo([this.i18n.currentLang()], () =>
      UPL_PERIODICITIES.map(option => ({ id: option, label: this.i18n.translate(UPL_PERIODICITY_KEY[option]) })));
  }

  /** Reconciliation strictness levels; translated again when the language changes. */
  strictnessOptions(): SMTSelectOption<UplStrictness>[] {
    return this.strictnessMemo([this.i18n.currentLang()], () =>
      UPL_STRICTNESSES.map(option => ({ id: option, label: this.i18n.translate(UPL_STRICTNESS_KEY[option]) })));
  }

  ngOnInit(): void {
    this.load();
    const params = this.route.snapshot.queryParamMap;
    const name = params.get('create');
    if (name !== null && this.canCreate()) {
      this.returnTo = params.get('returnTo') === 'packages' ? 'packages' : null;
      this.openCreate();
      this.form.name = name.trim().slice(0, NAME_MAX_LENGTH);
    }
  }

  canCreate(): boolean {
    return this.permissions.hasPermission('upl.sources', 'create');
  }

  /** The list's metadata first, then its first page; a retry repeats whichever failed. */
  load(): void {
    if (this.meta()) {
      this.pager.first();
      return;
    }
    this.metaError.set(false);
    this.queryMeta.get('upl.sources').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: meta => {
        this.meta.set(meta);
        this.views.load().subscribe(() => this.pager.first());
      },
      error: () => this.metaError.set(true)
    });
  }

  /** A header click sorts the whole list on the server; switching sorting off returns to the default order. */
  onSort(event: { column: string; sortBy: OrderBy } | undefined): void {
    if (!this.meta()) return;
    this.views.setSort(sortFromHeader(event));
    this.pager.first();
  }

  openCreate(): void {
    this.form = emptyForm();
    this.fieldErrors.set({});
    this.createError.set(null);
    this.isSaving.set(false);
    this.isCreateOpen.set(true);
  }

  closeCreate(): void {
    this.isCreateOpen.set(false);
    this.returnTo = null;
  }

  submitCreate(): void {
    if (this.isSaving()) {
      return;
    }
    const errors = this.validateForm();
    this.fieldErrors.set(errors);
    this.createError.set(null);
    if (Object.keys(errors).length > 0) {
      return;
    }
    const contact = this.form.ownerContact.trim();
    const body: UplSourceRequest = {
      code: this.form.code.trim(),
      name: this.form.name.trim(),
      ownerOrg: this.form.ownerOrg.trim(),
      ownerContact: contact.length > 0 ? contact : null,
      periodicity: this.form.periodicity,
      slaDays: Number(this.form.slaDays),
      sourceType: 'file',
      reconciliationStrictness: this.form.reconciliationStrictness,
      lockVersion: null
    };
    this.isSaving.set(true);
    this.api.createSource(body).subscribe({
      next: created => {
        this.isSaving.set(false);
        this.isCreateOpen.set(false);
        this.toast.success(this.i18n.translate('upl.source.created'));
        if (this.returnTo === 'packages') {
          void this.router.navigate(['/upl/packages'], { queryParams: { source: created.id } });
        } else {
          void this.router.navigate(['/upl/sources', created.id]);
        }
      },
      error: (problem: ProblemDetail) => {
        this.isSaving.set(false);
        this.handleCreateError(problem);
      }
    });
  }

  /** The translated message for a field's error code, or nothing; smt-control links it to the field. */
  fieldErrorText(key: string): string {
    const code = this.fieldErrors()[key];
    return code ? this.i18n.translate(code) : '';
  }

  private validateForm(): Record<string, string> {
    const errors: Record<string, string> = {};
    if (!CODE_PATTERN.test(this.form.code.trim())) {
      errors['code'] = 'upl.source.err.code_format';
    }
    const name = this.form.name.trim();
    if (name.length === 0) {
      errors['name'] = 'upl.source.err.required';
    } else if (name.length > NAME_MAX_LENGTH) {
      errors['name'] = 'upl.source.err.name_length';
    }
    if (this.form.ownerOrg.trim().length === 0) {
      errors['ownerOrg'] = 'upl.source.err.required';
    }
    const slaDays = Number(this.form.slaDays);
    if (this.form.slaDays === null || !Number.isInteger(slaDays) || slaDays < SLA_MIN || slaDays > SLA_MAX) {
      errors['slaDays'] = 'upl.source.err.sla_range';
    }
    return errors;
  }

  private handleCreateError(problem: ProblemDetail): void {
    if (problem?.status === 422) {
      const errors: Record<string, string> = {};
      for (const item of parseUplProblem(problem)) {
        errors[item.field] = uplFieldErrorText(item, key => this.i18n.translate(key));
      }
      this.fieldErrors.set(errors);
      this.createError.set('upl.err.VALIDATION_FAILED');
      return;
    }
    if ((problem?.code ?? '').toLowerCase() === 'code_already_exists' || problem?.detail === 'UPL_SOURCE_CODE_TAKEN') {
      this.fieldErrors.set({ code: 'upl.err.UPL_SOURCE_CODE_TAKEN' });
      return;
    }
    this.createError.set(this.problemText(problem));
  }

  /** Неизвестный код ошибки не прячем: показываем подкод и код каркаса. */
  private problemText(problem: ProblemDetail): string {
    return uplProblemText(problem, key => this.i18n.translate(key));
  }
}
