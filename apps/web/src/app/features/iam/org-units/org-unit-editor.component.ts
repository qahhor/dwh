import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  OnInit,
  input,
  output,
  signal,
} from '@angular/core';
import { disabled, form, FormField, readonly, required, validate } from '@angular/forms/signals';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { safeNumericRecordId } from '@core/services/search-target';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { orderedTree, orgUnitKindKeys, orgUnitTreeOptions, parentCandidates } from './org-unit-tree';
import { SMTControlComponent } from '@shared/ui-kit/components/forms/control';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTTreeOption, SMTTreeSelectComponent } from '@shared/ui-kit/components/forms/tree-select';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { markSMTFormFieldsTouched } from '@shared/ui-kit/forms/form-control-validation';
import { focusFirstInvalid, UiFocusFirstInvalidDirective } from '@shared/ui/focus-first-invalid';
import { problemFieldErrors } from '@shared/ui/problem-fields';
import { UiFormActionsComponent } from '@shared/ui/ui-form-actions.component';
import { OrgUnit, OrgUnitCreate, OrgUnitPatch } from './org-units.models';
import { ProblemDetail } from '@core/models/common.models';

export type OrgUnitSubmission =
  { mode: 'create'; body: OrgUnitCreate } | { mode: 'edit'; id: number; patch: OrgUnitPatch };

type OrgUnitDraftValues = OrgUnitCreate & { state: 'A' | 'P' };

const INT_MIN = -2147483648;
const INT_MAX = 2147483647;
const FIELDS = ['code', 'name', 'kind', 'parentId', 'state', 'orderNo'];

/**
 * Creates or edits one organisational unit (docs/guidelines/forms-ux-standard.md). A Signal Form: code (on create),
 * name and kind are required, the order is a whole number, every error shows under its field on blur and on save,
 * focus goes to the first invalid field, and the server's field messages from `error` go under the fields. Moving a
 * unit or changing its state asks first, because it changes who sees what.
 */
@Component({
  selector: 'app-org-unit-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    FormField,
    SMTAlertComponent,
    SMTControlComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTSelectComponent,
    SMTTreeSelectComponent,
    SMTInputComponent,
    UiFocusFirstInvalidDirective,
    UiFormActionsComponent,
  ],
  templateUrl: './org-unit-editor.component.html',
  styleUrl: './org-unit-editor.component.css',
})
export class OrgUnitEditorComponent implements OnInit {
  private readonly i18n = inject(I18nService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly initial = input.required<OrgUnit | OrgUnitCreate>();

  readonly units = input<OrgUnit[]>([]);
  readonly pending = input(false);

  readonly error = input<ProblemDetail | null>(null);

  readonly save = output<OrgUnitSubmission>();
  readonly cancelEdit = output<void>();

  readonly draft = signal<OrgUnitDraftValues>({
    parentId: null,
    code: '',
    name: '',
    kind: 'company',
    orderNo: 0,
    state: 'A',
  });
  private static nextId = 0;
  readonly formId = `org-unit-form-${OrgUnitEditorComponent.nextId++}`;
  readonly kindKeys = orgUnitKindKeys;
  readonly kinds = Object.keys(orgUnitKindKeys);
  original!: OrgUnit | OrgUnitCreate;
  private readonly editingState = signal(false);
  private parentTreeCache: {
    units: OrgUnit[];
    original: OrgUnit | OrgUnitCreate;
    tree: SMTTreeOption<number>[];
  } | null = null;
  readonly impactOpen = signal(false);
  private readonly kindMemo = optionsMemo<SMTSelectOption<string>[]>();
  private readonly stateMemo = optionsMemo<SMTSelectOption<'A' | 'P'>[]>();

  private readonly required = (key: string) => ({ message: () => this.i18n.translate(key) });

  readonly unitForm = form(this.draft, (path) => {
    disabled(path, () => this.pending() || this.impactOpen());
    readonly(path.code, () => this.editingState());
    required(path.code, this.required('iam.org_units.code_required'));
    validate(path.code, ({ value }) => this.blank(value(), 'iam.org_units.code_required'));
    required(path.name, this.required('iam.org_units.name_required'));
    validate(path.name, ({ value }) => this.blank(value(), 'iam.org_units.name_required'));
    required(path.kind, this.required('iam.org_units.kind_required'));
    validate(path.orderNo, ({ value }) => {
      const order = value();
      return Number.isInteger(order) && order >= INT_MIN && order <= INT_MAX
        ? null
        : { kind: 'order', message: this.i18n.translate('iam.org_units.order_invalid') };
    });
    validate(path.parentId, ({ value }) =>
      !this.original || value() === this.original.parentId || this.parents.some((unit) => unit.id === value())
        ? null
        : { kind: 'parent', message: this.i18n.translate('iam.org_units.parent_invalid') },
    );
  });

  /** The server's field messages of the last refused save. */
  readonly serverErrors = computed(() => problemFieldErrors(this.error(), { known: FIELDS }));

  /** A refusal of no field: its text above the fields. */
  readonly formError = computed(() => {
    const error = this.error();
    if (!error) return '';
    const { fields, other } = this.serverErrors();
    if (Object.keys(fields).length > 0) return other.join(' ');
    return error.detail || this.i18n.translate('iam.org_units.save_error');
  });

  constructor() {
    // A refused save marks fields invalid from the server's answer: focus goes to the first of them.
    effect(() => {
      if (Object.keys(this.serverErrors().fields).length > 0) {
        focusFirstInvalid(this.host.nativeElement, this.injector);
      }
    });
  }

  ngOnInit(): void {
    this.original = { ...this.initial() };
    this.editingState.set('id' in this.original);
    this.draft.set({ ...this.original, state: 'state' in this.original ? this.original.state : 'A' });
  }

  /** The known kinds, translated; an unknown kind of the edited unit stays first, as its raw code. */
  kindOptions(): SMTSelectOption<string>[] {
    return this.kindMemo([this.i18n.currentLang(), this.original], () => [
      ...(this.kindKeys[this.original.kind] ? [] : [{ id: this.original.kind, label: this.original.kind }]),
      ...this.kinds.map((kind) => ({ id: kind, label: this.i18n.translate(this.kindKeys[kind]) })),
    ]);
  }
  stateOptions(): SMTSelectOption<'A' | 'P'>[] {
    return this.stateMemo([this.i18n.currentLang()], () => [
      { id: 'A', label: this.i18n.translate('iam.org_units.active') },
      { id: 'P', label: this.i18n.translate('iam.org_units.passive') },
    ]);
  }
  serverError(field: string): string {
    return this.serverErrors().fields[field] ?? '';
  }
  get editing(): boolean {
    return 'id' in this.original;
  }
  get dirty(): boolean {
    const draft = this.draft();
    return (
      (draft.name || '').trim() !== (this.original.name || '').trim() ||
      draft.kind !== this.original.kind ||
      draft.orderNo !== this.original.orderNo ||
      draft.parentId !== this.original.parentId ||
      (!this.editing && (draft.code || '').trim() !== (this.original.code || '').trim()) ||
      ('state' in this.original && draft.state !== this.original.state)
    );
  }
  get parents(): OrgUnit[] {
    return this.editing ? parentCandidates(this.units(), this.original as OrgUnit) : [];
  }
  /** The allowed parents as a tree, rebuilt only when the units or the edited unit change. */
  get parentTree(): SMTTreeOption<number>[] {
    const cache = this.parentTreeCache;
    const units = this.units();
    if (cache && cache.units === units && cache.original === this.original) return cache.tree;
    const tree = orgUnitTreeOptions(orderedTree(this.parents));
    this.parentTreeCache = { units: units, original: this.original, tree };
    return tree;
  }
  get valid(): boolean {
    return this.unitForm().valid();
  }
  submit(): void {
    if (this.pending() || this.impactOpen()) return;
    markSMTFormFieldsTouched(this.unitForm);
    if (!this.valid || !this.dirty) return;
    const draft = this.draft();
    if (
      'state' in this.original &&
      (draft.state !== this.original.state || draft.parentId !== this.original.parentId)
    ) {
      this.impactOpen.set(true);
      return;
    }
    this.emitSave();
  }
  confirmImpact(): void {
    if (!this.impactOpen() || this.pending()) return;
    this.impactOpen.set(false);
    if (this.valid && this.dirty) this.emitSave();
  }
  private blank(value: string, key: string) {
    return !value || value.trim() ? null : { kind: 'required', message: this.i18n.translate(key) };
  }
  private emitSave(): void {
    const draft = this.draft();
    if ('id' in this.original) {
      if (!safeNumericRecordId(this.original.id)) return;
      const patch: OrgUnitPatch = {};
      if (draft.name.trim() !== this.original.name) patch.name = draft.name.trim();
      if (draft.parentId !== this.original.parentId) patch.parentId = draft.parentId;
      if (draft.kind !== this.original.kind) patch.kind = draft.kind;
      if (draft.state !== this.original.state) patch.state = draft.state;
      if (draft.orderNo !== this.original.orderNo) patch.orderNo = draft.orderNo;
      if (Object.keys(patch).length) this.save.emit({ mode: 'edit', id: this.original.id, patch });
    } else {
      this.save.emit({
        mode: 'create',
        body: {
          parentId: this.original.parentId,
          code: draft.code.trim(),
          name: draft.name.trim(),
          kind: draft.kind,
          orderNo: draft.orderNo,
        },
      });
    }
  }
}
