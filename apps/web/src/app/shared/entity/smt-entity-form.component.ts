import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  contentChildren,
  Directive,
  inject,
  input,
  model,
  TemplateRef,
  type Type,
} from '@angular/core';
import { NgComponentOutlet, NgTemplateOutlet } from '@angular/common';
import type {
  FormFieldMeta,
  FormFieldType,
  FormMeta,
  FormProblems,
  FormSectionMeta,
  FormValues,
} from '@core/models/form-meta.models';
import { fieldLabel, fieldReadonly, fieldVisible, optionLabel } from '@core/services/form-meta.service';
import type { FileValue, MoneyValue } from '@core/services/field-values';
import { I18nService } from '@core/services/i18n.service';
import { RefLookups } from '../lookups/ref-lookup';
import { UiMarkdownEditorComponent } from '../ui/ui-markdown-editor.component';
import { UiMarkdownViewComponent } from '../ui/ui-markdown-view.component';
import { SMTControlComponent } from '../ui-kit/components/forms/control/control.component';
import { SMTMultiDataSelectComponent } from '../ui-kit/components/forms/data-select/multi-data-select.component';
import { SMTTextareaComponent } from '../ui-kit/components/forms/textarea/textarea.component';
import {
  SMTDynamicFieldComponent,
  type SMTDynamicFieldDef,
  type SMTDynamicFieldType,
} from '../ui-kit/components/forms/dynamic-field';
import type { SMTLookupKey, SMTLookupSource } from '../ui-kit/components/forms/data-select/lookup-source';
import { SMTFileFieldComponent } from './smt-file-field.component';
import { SMTMoneyFieldComponent } from './smt-money-field.component';

/**
 * The control each field type is drawn with (ADR-0032 4.1, plan 10/10, item 5.2): a kit field of the dynamic field,
 * or a control of its own — the markdown editor, money, several references, a file and JSON. A `Record` over every
 * type, so a type without its control fails the typecheck (`field-type-matrix.spec.ts`).
 */
export type SMTEntityControl = SMTDynamicFieldType | 'markdown' | 'money' | 'multi_ref' | 'file' | 'json';

export const ENTITY_CONTROLS: Record<FormFieldType, SMTEntityControl> = {
  text: 'string',
  textarea: 'text',
  markdown: 'markdown',
  number: 'number',
  date: 'date',
  datetime: 'datetime',
  time: 'time',
  boolean: 'boolean',
  select: 'select',
  ref: 'ref',
  email: 'email',
  phone: 'phone',
  url: 'url',
  money: 'money',
  enum: 'select',
  multi_ref: 'multi_ref',
  file: 'file',
  image: 'file',
  json: 'json',
};

/** The context a replaced field's template gets: the field, its value and a way to change it. */
export interface SMTEntityFieldContext {
  $implicit: FormFieldMeta;
  value: unknown;
  problem: string;
  set: (value: unknown) => void;
}

/**
 * A screen's own control for one field (ADR-0019 2.5): `<ng-template smtEntityField="color" let-field let-value="value"
 * let-set="set">`. The rest of the form stays the platform's.
 */
@Directive({ selector: 'ng-template[smtEntityField]' })
export class SMTEntityFieldDirective {
  readonly template = inject<TemplateRef<SMTEntityFieldContext>>(TemplateRef);

  readonly key = input.required<string>({ alias: 'smtEntityField' });
}

interface DrawnField {
  meta: FormFieldMeta;
  control: SMTEntityControl;
  def: SMTDynamicFieldDef;
  label: string;
  source: SMTLookupSource<unknown, SMTLookupKey> | null;
}

interface DrawnSection {
  section: FormSectionMeta;
  title: string;
  fields: DrawnField[];
}

/**
 * One form for every declared entity (ADR-0019 2.5, roadmap item 55), drawn from `form-meta`: its sections in
 * order, each field with the kit control for its type, its label, required mark and problem. Custom fields come
 * in their own section like any other. A field the viewer may not change is shown but not editable (ADR-0032 5.2);
 * one they may not see the server leaves out. The value is the record's values by field key (`recordValues`); the
 * screen saves them (`recordPayload`) and hands back the server's problems.
 */
@Component({
  selector: 'smt-entity-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgComponentOutlet,
    NgTemplateOutlet,
    SMTControlComponent,
    SMTDynamicFieldComponent,
    SMTFileFieldComponent,
    SMTMoneyFieldComponent,
    SMTMultiDataSelectComponent,
    SMTTextareaComponent,
    UiMarkdownEditorComponent,
    UiMarkdownViewComponent,
  ],
  host: { class: 'smt-entity-form' },
  templateUrl: './smt-entity-form.component.html',
  styleUrl: './smt-entity-form.component.css',
})
export class SMTEntityFormComponent {
  private readonly i18n = inject(I18nService);

  private readonly refLookups = inject(RefLookups);

  readonly meta = input.required<FormMeta>();

  /** Problems by field key, from `formProblems` or the server's answer. */
  readonly problems = input<FormProblems>({});

  readonly disabled = input(false, { transform: booleanAttribute });

  /** The record being edited, or null while it is new: fields read-only on update follow it (ADR-0032 4.4). */
  readonly recordId = input<number | null>(null);

  /** The sections to draw, by key; every section when empty. */
  readonly sections = input<readonly string[]>([]);

  /**
   * Controls of the entity's own by field key (`provideEntityOverrides`, ADR-0032 7.2): a component that gets the
   * inputs `field`, `value`, `problem`, `disabled` and `set`. A template given with `smtEntityField` comes first.
   */
  readonly controls = input<Readonly<Record<string, Type<unknown>>>>({});

  /** The record's values by field key. */
  readonly value = model<FormValues>({});

  private readonly replacements = contentChildren(SMTEntityFieldDirective);

  /** Sections and their fields, rebuilt when the form or the language changes — not on every keystroke. */
  readonly drawnSections = computed<DrawnSection[]>(() => {
    this.i18n.currentLang();
    const meta = this.meta();
    const only = this.sections();
    const byKey = new Map(meta.fields.map((field) => [field.key, field]));
    const translate = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    return meta.layout
      .filter((section) => only.length === 0 || only.includes(section.key))
      .map((section) => ({
        section,
        title: translate(section.labelKey),
        fields: section.fields
          .map((key) => byKey.get(key))
          .filter((field): field is FormFieldMeta => !!field)
          .map((field) => this.draw(field, translate)),
      }))
      .filter((section) => section.fields.length > 0);
  });

  /** One setter per field, so the control's input keeps its identity between checks. */
  private readonly setters = new Map<string, (value: unknown) => void>();

  set(key: string, value: unknown): void {
    this.value.update((values) => ({ ...values, [key]: value }));
  }

  problemOf(key: string): string {
    return this.problems()[key] ?? '';
  }

  textOf(key: string): string {
    const value = this.value()[key];
    return value === null || value === undefined ? '' : String(value);
  }

  isWide(field: FormFieldMeta): boolean {
    return field.type === 'markdown' || field.type === 'textarea' || field.type === 'json';
  }

  /** Whether the field is shown over the values on screen: its condition holds (ADR-0032 4.4). */
  shown(field: FormFieldMeta): boolean {
    return fieldVisible(field, this.value());
  }

  /**
   * Whether the field is read-only now: by its declaration or because the viewer lacks its right (ADR-0032 4.4, 5.2).
   * A read-only markdown field is shown rendered, not as a locked editor.
   */
  readonlyNow(field: FormFieldMeta): boolean {
    return fieldReadonly(field, this.value(), this.recordId() === null);
  }

  /** Whether the person cannot change the field: the form is disabled, or the field is read-only now. */
  locked(field: FormFieldMeta): boolean {
    return this.disabled() || this.readonlyNow(field);
  }

  moneyOf(key: string): MoneyValue | null {
    return (this.value()[key] as MoneyValue | null | undefined) ?? null;
  }

  keysOf(key: string): readonly SMTLookupKey[] {
    const value = this.value()[key];
    return Array.isArray(value) ? (value as SMTLookupKey[]) : [];
  }

  fileOf(key: string): FileValue | string | null {
    return (this.value()[key] as FileValue | string | null | undefined) ?? null;
  }

  replacement(key: string): TemplateRef<SMTEntityFieldContext> | null {
    return this.replacements().find((directive) => directive.key() === key)?.template ?? null;
  }

  controlOf(key: string): Type<unknown> | null {
    return this.controls()[key] ?? null;
  }

  /** The inputs of a field's own control: the field, its value and problem, whether it is locked, and the setter. */
  controlInputs(field: FormFieldMeta): Record<string, unknown> {
    return {
      field,
      value: this.value()[field.key] ?? null,
      problem: this.problemOf(field.key),
      disabled: this.locked(field),
      set: this.setterOf(field.key),
    };
  }

  contextOf(field: FormFieldMeta): SMTEntityFieldContext {
    return {
      $implicit: field,
      value: this.value()[field.key] ?? null,
      problem: this.problemOf(field.key),
      set: (value) => this.set(field.key, value),
    };
  }

  private setterOf(key: string): (value: unknown) => void {
    let setter = this.setters.get(key);
    if (!setter) {
      setter = (value) => this.set(key, value);
      this.setters.set(key, setter);
    }
    return setter;
  }

  private draw(field: FormFieldMeta, translate: (key: string) => string): DrawnField {
    const label = fieldLabel(field, translate);
    const control = ENTITY_CONTROLS[field.type];
    const def: SMTDynamicFieldDef = {
      code: field.key,
      label,
      type: isDynamic(control) ? control : 'string',
      required: field.required,
      maxLength: field.maxLength ?? null,
      options:
        field.type === 'select' || field.type === 'enum'
          ? (field.options ?? []).map((option) => ({
              id: option,
              label: field.optionLabels?.[option] ?? optionLabel(field, option, translate),
            }))
          : undefined,
    };
    // A reference is picked from its own target (plan 10/10, item 5.0): a person, a project, a task, any list.
    const source = field.ref ? this.refLookups.source(field.ref) : null;
    return { meta: field, control, def, label, source };
  }
}

function isDynamic(control: SMTEntityControl): control is SMTDynamicFieldType {
  return (
    control !== 'markdown' && control !== 'money' && control !== 'multi_ref' && control !== 'file' && control !== 'json'
  );
}
