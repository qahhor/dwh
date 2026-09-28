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
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import type {
  FormFieldMeta,
  FormMeta,
  FormProblems,
  FormSectionMeta,
  FormValues,
} from '../../core/models/form-meta.models';
import { fieldLabel, optionLabel } from '../../core/services/form-meta.service';
import { I18nService } from '../../core/services/i18n.service';
import { LookupSources } from '../lookups/lookup-sources';
import { RefLookups } from '../lookups/ref-lookup';
import { UiMarkdownEditorComponent } from '../ui/ui-markdown-editor.component';
import { SMTControlComponent } from '../ui-kit/components/forms/control/control.component';
import {
  SMTDynamicFieldComponent,
  type SMTDynamicFieldDef,
  type SMTDynamicFieldType,
} from '../ui-kit/components/forms/dynamic-field';
import type { SMTLookupKey, SMTLookupSource } from '../ui-kit/components/forms/data-select/lookup-source';

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
 * in their own section like any other. The value is the record's values by field key (`recordValues`); the
 * screen saves them (`recordPayload`) and hands back the server's problems.
 */
@Component({
  selector: 'smt-entity-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgTemplateOutlet, SMTControlComponent, SMTDynamicFieldComponent, UiMarkdownEditorComponent],
  host: { class: 'smt-entity-form' },
  template: `
    @for (drawn of drawnSections(); track drawn.section.key) {
      <fieldset class="entity-section" [attr.data-section]="drawn.section.key">
        @if (drawnSections().length > 1) {
          <legend class="entity-section-title">{{ drawn.title }}</legend>
        } @else {
          <legend class="sr-only">{{ drawn.title }}</legend>
        }
        <div class="entity-section-grid">
          @for (field of drawn.fields; track field.meta.key) {
            <div
              class="entity-field"
              [class.entity-field--wide]="isWide(field.meta)"
              [attr.data-field]="field.meta.key"
            >
              @if (replacement(field.meta.key); as custom) {
                <ng-container *ngTemplateOutlet="custom; context: contextOf(field.meta)" />
              } @else if (field.meta.type === 'markdown') {
                <smt-control
                  [smtLabel]="field.label"
                  [required]="field.meta.required"
                  [smtError]="problemOf(field.meta.key)"
                >
                  <ui-markdown-editor
                    [value]="textOf(field.meta.key)"
                    [ariaLabel]="field.label"
                    [rows]="6"
                    (valueChange)="set(field.meta.key, $event)"
                  />
                </smt-control>
              } @else {
                <smt-dynamic-field
                  [field]="field.def"
                  [userSource]="field.source"
                  [disabled]="disabled()"
                  [error]="problemOf(field.meta.key)"
                  [value]="value()[field.meta.key] ?? null"
                  (valueChange)="set(field.meta.key, $event)"
                />
              }
            </div>
          }
        </div>
      </fieldset>
    }
  `,
  styles: [
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 16px;
      }
      .entity-section {
        border: 0;
        margin: 0;
        padding: 0;
        min-width: 0;
      }
      .entity-section-title {
        font-weight: 600;
        font-size: 0.875rem;
        margin-bottom: 8px;
        padding: 0;
      }
      .entity-section-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
        gap: 12px;
      }
      .entity-field {
        min-width: 0;
      }
      .entity-field--wide {
        grid-column: 1 / -1;
      }
    `,
  ],
})
export class SMTEntityFormComponent {
  private readonly i18n = inject(I18nService);

  private readonly refLookups = inject(RefLookups);

  private readonly lookups = inject(LookupSources);

  readonly meta = input.required<FormMeta>();

  /** Problems by field key, from `formProblems` or the server's answer. */
  readonly problems = input<FormProblems>({});

  readonly disabled = input(false, { transform: booleanAttribute });

  /** The sections to draw, by key; every section when empty. */
  readonly sections = input<readonly string[]>([]);

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
    return field.type === 'markdown' || field.type === 'textarea';
  }

  replacement(key: string): TemplateRef<SMTEntityFieldContext> | null {
    return this.replacements().find((directive) => directive.key() === key)?.template ?? null;
  }

  contextOf(field: FormFieldMeta): SMTEntityFieldContext {
    return {
      $implicit: field,
      value: this.value()[field.key] ?? null,
      problem: this.problemOf(field.key),
      set: (value) => this.set(field.key, value),
    };
  }

  private draw(field: FormFieldMeta, translate: (key: string) => string): DrawnField {
    const label = fieldLabel(field, translate);
    const def: SMTDynamicFieldDef = {
      code: field.key,
      label,
      type: TYPES[field.type],
      required: field.required,
      maxLength: field.maxLength ?? null,
      options:
        field.type === 'select'
          ? (field.options ?? []).map((option) => ({ id: option, label: optionLabel(field, option, translate) }))
          : undefined,
    };
    let source: SMTLookupSource<unknown, SMTLookupKey> | null = null;
    if (field.ref) {
      source = field.ref.path === '/iam/users' ? this.lookups.activeUsers : this.refLookups.source(field.ref);
    }
    return { meta: field, def, label, source };
  }
}

const TYPES: Record<FormFieldMeta['type'], SMTDynamicFieldType> = {
  text: 'string',
  textarea: 'text',
  markdown: 'text',
  number: 'number',
  date: 'date',
  boolean: 'boolean',
  select: 'select',
  ref: 'user_ref',
};
