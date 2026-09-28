import { ChangeDetectionStrategy, Component, inject, OnInit, input, output } from '@angular/core';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { safeNumericRecordId } from '@core/services/search-target';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { orderedTree, orgUnitKindKeys, orgUnitTreeOptions, parentCandidates } from './org-unit-tree';
import { SMTSelectComponent, SMTSelectOption } from '@shared/ui-kit/components/forms/select';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';
import { SMTTreeOption, SMTTreeSelectComponent } from '@shared/ui-kit/components/forms/tree-select';
import { SMTInputComponent } from '@shared/ui-kit/components/forms/input';
import { OrgUnit, OrgUnitCreate, OrgUnitPatch } from './org-units.models';
import { ProblemDetail } from '@core/models/common.models';
export type OrgUnitSubmission =
  { mode: 'create'; body: OrgUnitCreate } | { mode: 'edit'; id: number; patch: OrgUnitPatch };
@Component({
  selector: 'app-org-unit-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslatePipe,
    SMTButtonComponent,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTSelectComponent,
    SMTTreeSelectComponent,
    SMTInputComponent,
  ],
  templateUrl: './org-unit-editor.component.html',
  styleUrl: './org-unit-editor.component.css',
})
export class OrgUnitEditorComponent implements OnInit {
  private readonly i18n = inject(I18nService);

  readonly initial = input.required<OrgUnit | OrgUnitCreate>();

  readonly units = input<OrgUnit[]>([]);
  readonly pending = input(false);

  readonly error = input<ProblemDetail | null>(null);

  readonly save = output<OrgUnitSubmission>();
  readonly cancel = output<void>();

  draft: OrgUnitCreate & { state: 'A' | 'P' } = {
    parentId: null,
    code: '',
    name: '',
    kind: 'company',
    orderNo: 0,
    state: 'A',
  };
  private static nextId = 0;
  readonly formId = `org-unit-form-${OrgUnitEditorComponent.nextId++}`;
  readonly kindKeys = orgUnitKindKeys;
  readonly kinds = Object.keys(orgUnitKindKeys);
  original!: OrgUnit | OrgUnitCreate;
  attempted = false;
  private parentTreeCache: {
    units: OrgUnit[];
    original: OrgUnit | OrgUnitCreate;
    tree: SMTTreeOption<number>[];
  } | null = null;
  impactOpen = false;
  private readonly kindMemo = optionsMemo<SMTSelectOption<string>[]>();
  private readonly stateMemo = optionsMemo<SMTSelectOption<'A' | 'P'>[]>();

  ngOnInit(): void {
    this.original = { ...this.initial() };
    this.draft = { ...this.original, state: 'state' in this.original ? this.original.state : 'A' };
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
  get editing(): boolean {
    return 'id' in this.original;
  }
  get dirty(): boolean {
    return (
      (this.draft.name || '').trim() !== (this.original.name || '').trim() ||
      this.draft.kind !== this.original.kind ||
      this.draft.orderNo !== this.original.orderNo ||
      this.draft.parentId !== this.original.parentId ||
      (!this.editing && (this.draft.code || '').trim() !== (this.original.code || '').trim()) ||
      ('state' in this.original && this.draft.state !== this.original.state)
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
    return (
      !!this.draft.name.trim() &&
      !!this.draft.code.trim() &&
      !!this.draft.kind.trim() &&
      Number.isInteger(this.draft.orderNo) &&
      this.draft.orderNo >= -2147483648 &&
      this.draft.orderNo <= 2147483647 &&
      (this.draft.parentId === this.original.parentId || this.parents.some((unit) => unit.id === this.draft.parentId))
    );
  }
  fieldError(field: string): string | null {
    const error = this.error();
    return (
      error?.invalid_params?.find((item) => item.name === field)?.reason ??
      error?.errors?.find((item) => item.field === field)?.message ??
      null
    );
  }
  submit(): void {
    if (this.pending() || this.impactOpen) return;
    this.attempted = true;
    if (!this.valid || !this.dirty) return;
    if (
      'state' in this.original &&
      (this.draft.state !== this.original.state || this.draft.parentId !== this.original.parentId)
    ) {
      this.impactOpen = true;
      return;
    }
    this.emitSave();
  }
  confirmImpact(): void {
    if (!this.impactOpen || this.pending()) return;
    this.impactOpen = false;
    if (this.valid && this.dirty) this.emitSave();
  }
  private emitSave(): void {
    if ('id' in this.original) {
      if (!safeNumericRecordId(this.original.id)) return;
      const patch: OrgUnitPatch = {};
      if (this.draft.name.trim() !== this.original.name) patch.name = this.draft.name.trim();
      if (this.draft.parentId !== this.original.parentId) patch.parentId = this.draft.parentId;
      if (this.draft.kind !== this.original.kind) patch.kind = this.draft.kind;
      if (this.draft.state !== this.original.state) patch.state = this.draft.state;
      if (this.draft.orderNo !== this.original.orderNo) patch.orderNo = this.draft.orderNo;
      if (Object.keys(patch).length) this.save.emit({ mode: 'edit', id: this.original.id, patch });
    } else {
      this.save.emit({
        mode: 'create',
        body: {
          parentId: this.original.parentId,
          code: this.draft.code.trim(),
          name: this.draft.name.trim(),
          kind: this.draft.kind,
          orderNo: this.draft.orderNo,
        },
      });
    }
  }
}
