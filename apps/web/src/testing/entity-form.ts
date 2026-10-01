import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import type {
  FormFieldMeta,
  FormFieldType,
  FormMeta,
  FormProblems,
  FormValues,
} from '../app/core/models/form-meta.models';
import { ApiService } from '../app/core/services/api.service';
import { SMTEntityFormComponent } from '../app/shared/entity/smt-entity-form.component';

/**
 * Test helpers for screens built on `smt-entity-form` (ADR-0032 11.5, plan 10/10, item 6.2): a `form-meta` of an
 * entity from its fields, the form rendered from it, and a harness that reads and fills a field by its key the way a
 * person does — typing into a text control, toggling a switch — and reads the problem shown under it.
 */

/** The `form-meta` of entity `code` with these fields in one section and every action allowed. */
export function formMetaFixture(code: string, fields: FormFieldMeta[], extra: Partial<FormMeta> = {}): FormMeta {
  return {
    code,
    listCode: code,
    fields,
    layout: [{ key: 'main', labelKey: 'entity.section.main', fields: fields.map((field) => field.key) }],
    actions: ['create', 'update', 'delete'],
    capabilities: [],
    ...extra,
  };
}

/** The types a person fills by typing or toggling; the others are set through the host's values. */
const TYPED: Partial<Record<FormFieldType, string>> = {
  text: 'input',
  email: 'input',
  url: 'input',
  phone: 'input',
  number: 'input',
  textarea: 'textarea',
  json: 'textarea',
  markdown: 'ui-markdown-editor textarea',
};

/** Where the form is drawn: the fixture's element, or `inScreen(...)` of it when the form is in a dialog. */
export interface EntityFormRoot {
  querySelector(selector: string): Element | null;
  querySelectorAll(selector: string): ArrayLike<Element>;
}

/** A rendered entity form, read and filled by field key. */
export class EntityFormHarness {
  constructor(
    private readonly root: EntityFormRoot,
    private readonly meta: () => FormMeta,
    private readonly detectChanges: () => void,
  ) {}

  /** The keys of the fields drawn, in their order on the form. */
  keys(): string[] {
    return Array.from(this.root.querySelectorAll('[data-field]')).map((field) => field.getAttribute('data-field')!);
  }

  /** The drawn field of `key`; fails when the form does not draw it. */
  field(key: string): HTMLElement {
    const field = this.root.querySelector(`[data-field="${key}"]`) as HTMLElement | null;
    if (!field) throw new Error(`The form draws no field ${key}; it draws ${this.keys().join(', ')}`);
    return field;
  }

  /** Types `value` into a text control, or turns a switch on or off, as the field's type asks. */
  fill(key: string, value: string | boolean): void {
    const type = this.type(key);
    if (type === 'boolean') {
      const toggle = this.field(key).querySelector<HTMLElement>('[role="switch"]');
      if (!toggle) throw new Error(`The field ${key} draws no switch`);
      if ((toggle.getAttribute('aria-checked') === 'true') !== value) toggle.click();
    } else {
      const selector = TYPED[type];
      if (!selector) throw new Error(`A ${type} field is not typed into: set ${key} through the host's values`);
      const control = this.field(key).querySelector<HTMLInputElement | HTMLTextAreaElement>(selector);
      if (!control) throw new Error(`The field ${key} draws no ${selector}`);
      control.value = String(value);
      control.dispatchEvent(new Event('input'));
    }
    this.detectChanges();
  }

  /** The problem shown under the field, or an empty text. */
  problem(key: string): string {
    return this.field(key).querySelector('.smt-control__error')?.textContent?.trim() ?? '';
  }

  /** Whether the field is shown but cannot be changed (ADR-0032 4.4, 5.2). */
  readonly(key: string): boolean {
    const field = this.field(key);
    if (field.querySelector('ui-markdown-view')) return true;
    const control = field.querySelector<HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement>(
      'input, textarea, button[role="switch"]',
    );
    return !!control && (control.disabled || ('readOnly' in control && control.readOnly));
  }

  private type(key: string): FormFieldType {
    const field = this.meta().fields.find((candidate) => candidate.key === key);
    if (!field) throw new Error(`form-meta has no field ${key}`);
    return field.type;
  }
}

@Component({
  imports: [SMTEntityFormComponent],
  template: `<smt-entity-form [meta]="meta()" [(value)]="values" [problems]="problems()" />`,
})
export class EntityFormHostComponent {
  readonly meta = signal<FormMeta>(formMetaFixture('test.entity', []));
  readonly values = signal<FormValues>({});
  readonly problems = signal<FormProblems>({});
}

/** The form rendered from `meta` with these values and problems, and its harness. */
export async function renderEntityForm(
  meta: FormMeta,
  values: FormValues = {},
  problems: FormProblems = {},
): Promise<{
  fixture: ComponentFixture<EntityFormHostComponent>;
  host: EntityFormHostComponent;
  form: EntityFormHarness;
}> {
  const api = { get: vi.fn(() => of({ items: [], nextCursor: null, hasMore: false })), post: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [EntityFormHostComponent],
    providers: [{ provide: ApiService, useValue: api }],
  }).compileComponents();
  const fixture = TestBed.createComponent(EntityFormHostComponent);
  const host = fixture.componentInstance;
  host.meta.set(meta);
  host.values.set(values);
  host.problems.set(problems);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  const form = new EntityFormHarness(fixture.nativeElement as HTMLElement, host.meta, () => fixture.detectChanges());
  return { fixture, host, form };
}
