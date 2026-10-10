import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Injector,
  OnInit,
  TemplateRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';

import { FormField, form, maxLength, validate } from '@angular/forms/signals';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { switchMap, tap } from 'rxjs';
import { ProblemDetail } from '@core/models/common.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { QueryMetaService, parseSort } from '@core/services/query-meta.service';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { ListViewState, ListViewsApi } from '@shared/list-views/list-views';
import { TableColumnStateStore } from '@shared/ui-kit/services/table-column-state.store';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { PermissionService } from '@core/services/permission.service';
import { ToastService } from '@core/services/toast.service';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { UiServerTableComponent } from '@shared/ui/ui-server-table.component';
import { registryTableConfig, sortFromHeader } from '@shared/ui/registry-table-config';
import { OrderBy, TableConfig } from '@shared/ui-kit/components/table/table.types';
import {
  UPL_PERIODICITIES,
  UPL_STRICTNESSES,
  UplApiService,
  UplPeriodicity,
  UplSourceItem,
  UplSourceRequest,
  UplStrictness,
} from '../upl.api';
import { parseUplProblem, uplFieldErrorText } from '../formats/upl-format-errors';
import { UPL_ERROR, UPL_PERIODICITY_KEY, UPL_STRICTNESS_KEY, uplProblemText } from '../upl-labels';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group/radio-options';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import {
  UPL_SOURCE_NAME_MAX_LENGTH,
  UplRuleMessage,
  uplRequiredText,
  uplSourceLengthLimits,
  uplSourceRequisiteRules,
} from './source-form-rules';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { UiFocusFirstInvalidDirective, focusFirstInvalid } from '@shared/ui/focus-first-invalid';
import { discardChangesQuestion } from '@shared/ui/discard-changes';

/** The model of the "new source" window, bound to its fields through Signal Forms. */
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
const CODE_MAX_LENGTH = 63;

function emptyForm(): SourceCreateForm {
  return {
    code: '',
    name: '',
    ownerOrg: '',
    ownerContact: '',
    periodicity: 'month',
    slaDays: 0,
    reconciliationStrictness: 'error',
  };
}

@Component({
  selector: 'app-upl-sources-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    UiPageHeaderComponent,
    SMTInputComponent,
    SMTSelectComponent,
    SMTAlertComponent,
    SMTControlComponent,
    FormField,
    RouterLink,
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTBadgeComponent,
    UiServerTableComponent,
    UiFormActionsComponent,
    UiFocusFirstInvalidDirective,
  ],
  templateUrl: './sources-list.component.html',
  styleUrl: './sources-list.component.css',
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
  private readonly injector = inject(Injector);
  private readonly askDiscard = discardChangesQuestion();

  private readonly codeCell = viewChild.required<TemplateRef<unknown>>('codeCell');
  private readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  private readonly draftCell = viewChild.required<TemplateRef<unknown>>('draftCell');

  /** Field metadata of the list (`query-meta/upl.sources`): columns, headers, what sorts. */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  readonly isCreateOpen = signal(false);
  readonly isSaving = signal(false);
  /** The value is an i18n key or a ready server text; the template passes it through `| t` anyway. */
  readonly fieldErrors = signal<Record<string, string>>({});
  readonly createError = signal<string | null>(null);

  readonly createModel = signal<SourceCreateForm>(emptyForm());
  /** The window as it was opened (a name may come prefilled): closing asks only when something changed since. */
  private openedWith = JSON.stringify(emptyForm());

  readonly tableConfig = computed<TableConfig<UplSourceItem> | null>(() => {
    const meta = this.meta();
    if (!meta) return null;
    return registryTableConfig<UplSourceItem>(meta, {
      translate: (key) => this.i18n.translate(key),
      trackBy: (_index, item) => item.id,
      ariaLabel: this.i18n.translate('upl.list.title'),
      sort: this.sort(),
      cells: {
        code: { type: 'templateRef', value: this.codeCell },
        name: { type: 'templateRef', value: this.nameCell },
        hasDraft: { type: 'templateRef', value: this.draftCell },
      },
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
    columnsStore: inject(TableColumnStateStore),
  });
  readonly sort = this.views.sort;

  /** The sort goes with every page, so a cursor always continues the query that issued it. */
  readonly pager = new KeysetPager<UplSourceItem>(
    (cursor, limit) =>
      this.api.listSources(limit, cursor, {
        sort: this.sort(),
        conditions: this.views.filter(),
        match: this.views.match(),
      }),
    { pageSize: PAGE_SIZE, destroyRef: this.destroyRef },
  );
  readonly items = this.pager.items;
  readonly isLoading = this.pager.loading;
  readonly loadError = this.pager.failed;

  private readonly periodicityMemo = optionsMemo<SMTSelectOption<UplPeriodicity>[]>();
  private readonly strictnessMemo = optionsMemo<SMTSelectOption<UplStrictness>[]>();
  private readonly ruleMessage: UplRuleMessage = (key) => ({ kind: key, message: this.i18n.translate(key) });
  /* Rules are always on; smt-control shows an error once the field is left or a save is tried (forms standard, 4). */
  readonly createForm = form(this.createModel, (path) => {
    maxLength(path.code, CODE_MAX_LENGTH);
    uplSourceLengthLimits(path);
    uplRequiredText(path.code, this.ruleMessage);
    validate(path.code, ({ value }) => {
      const code = value().trim();
      return code.length === 0 || CODE_PATTERN.test(code) ? null : this.ruleMessage('upl.source.err.code_format');
    });
    uplSourceRequisiteRules(path, this.ruleMessage);
  });

  /** Periodicities of a source; translated again when the language changes. */
  periodicityOptions(): SMTSelectOption<UplPeriodicity>[] {
    return this.periodicityMemo([this.i18n.currentLang()], () =>
      UPL_PERIODICITIES.map((option) => ({ id: option, label: this.i18n.translate(UPL_PERIODICITY_KEY[option]) })),
    );
  }

  /** Reconciliation strictness levels; translated again when the language changes. */
  strictnessOptions(): SMTSelectOption<UplStrictness>[] {
    return this.strictnessMemo([this.i18n.currentLang()], () =>
      UPL_STRICTNESSES.map((option) => ({ id: option, label: this.i18n.translate(UPL_STRICTNESS_KEY[option]) })),
    );
  }

  ngOnInit(): void {
    this.load();
    const params = this.route.snapshot.queryParamMap;
    const name = params.get('create');
    if (name !== null && this.canCreate()) {
      this.returnTo = params.get('returnTo') === 'packages' ? 'packages' : null;
      this.openCreate();
      this.createModel.update((model) => ({ ...model, name: name.trim().slice(0, UPL_SOURCE_NAME_MAX_LENGTH) }));
      this.openedWith = JSON.stringify(this.createModel());
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
    // The saved views apply their sort over the metadata, so they load only once it is here.
    this.queryMeta
      .get('upl.sources')
      .pipe(
        tap((meta) => this.meta.set(meta)),
        switchMap(() => this.views.load()),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.pager.first(),
        error: () => this.metaError.set(true),
      });
  }

  /** A header click sorts the whole list on the server; switching sorting off returns to the default order. */
  onSort(event: { column: string; sortBy: OrderBy } | undefined): void {
    if (!this.meta()) return;
    this.views.setSort(sortFromHeader(event));
    this.pager.first();
  }

  openCreate(): void {
    this.createForm().reset(emptyForm());
    this.openedWith = JSON.stringify(emptyForm());
    this.fieldErrors.set({});
    this.createError.set(null);
    this.isSaving.set(false);
    this.isCreateOpen.set(true);
  }

  /** Escape, the backdrop, the cross and "Cancel" only ask; a changed form asks before it is dropped. */
  closeCreate(): void {
    if (this.isSaving()) return;
    this.askDiscard(this.isCreateDirty()).subscribe((discard) => {
      if (!discard) return;
      this.isCreateOpen.set(false);
      this.returnTo = null;
    });
  }

  isCreateDirty(): boolean {
    return JSON.stringify(this.createModel()) !== this.openedWith;
  }

  submitCreate(): void {
    if (this.isSaving()) {
      return;
    }
    markSMTFormFieldsTouched(this.createForm);
    this.fieldErrors.set({});
    this.createError.set(null);
    if (!this.createForm().valid()) {
      return;
    }
    const model = this.createModel();
    const contact = model.ownerContact.trim();
    const body: UplSourceRequest = {
      code: model.code.trim(),
      name: model.name.trim(),
      ownerOrg: model.ownerOrg.trim(),
      ownerContact: contact.length > 0 ? contact : null,
      periodicity: model.periodicity,
      slaDays: Number(model.slaDays),
      sourceType: 'file',
      reconciliationStrictness: model.reconciliationStrictness,
      lockVersion: null,
    };
    this.isSaving.set(true);
    this.api.createSource(body).subscribe({
      next: (created) => {
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
      },
    });
  }

  /** The translated server error for a field, or nothing; smt-control shows it at once and links it to the field. */
  fieldErrorText(key: string): string {
    const code = this.fieldErrors()[key];
    return code ? this.i18n.translate(code) : '';
  }

  private handleCreateError(problem: ProblemDetail): void {
    if (problem?.status === 422) {
      const errors: Record<string, string> = {};
      for (const item of parseUplProblem(problem)) {
        errors[item.field] = uplFieldErrorText(item, (key) => this.i18n.translate(key));
      }
      this.fieldErrors.set(errors);
      this.createError.set('upl.err.VALIDATION_FAILED');
      this.focusServerError();
      return;
    }
    if (
      (problem?.code ?? '').toLowerCase() === 'code_already_exists' ||
      problem?.messageKey === UPL_ERROR.sourceCodeTaken
    ) {
      this.fieldErrors.set({ code: 'upl.err.UPL_SOURCE_CODE_TAKEN' });
      this.focusServerError();
      return;
    }
    this.createError.set(this.problemText(problem));
  }

  /** The server's field errors are drawn on the next render; focus goes to the first of them. */
  private focusServerError(): void {
    const formElement = document.getElementById('upl-source-create');
    if (formElement) focusFirstInvalid(formElement, this.injector);
  }

  /** An unknown error code is not hidden: the subcode and the framework code are shown. */
  private problemText(problem: ProblemDetail): string {
    return uplProblemText(problem, (key, params) => this.i18n.translate(key, params));
  }
}
