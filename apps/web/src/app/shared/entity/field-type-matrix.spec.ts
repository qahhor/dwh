import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import {
  FORM_FIELD_TYPES,
  type FormFieldMeta,
  type FormFieldType,
  type FormMeta,
  type FormValues,
} from '@core/models/form-meta.models';
import type { QueryFieldType, QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { FIELD_VALUE_RULES } from '@core/services/field-values';
import { formField } from '@testing/form-meta';
import { metaField } from '@testing/registry-meta';
import { registryTableConfig } from '../ui/registry-table-config';
import { FIELD_TEXT } from './entity-values';
import { ENTITY_CONTROLS, SMTEntityFormComponent } from './smt-entity-form.component';

/**
 * Plan 10/10, item 5.2 (ADR-0032 4.8): every field type has its web parts — a form control, the value rules the server
 * checks too, the words of the card and history, and a list cell. Parameterized by `FORM_FIELD_TYPES`, which the
 * server's `FieldTypeMatrixTest` compares with its `FieldType`: a type missing a part fails one of the two builds.
 */

const FILE_ID = '6f1c2a52-6b0e-4d3e-9a51-1f2d3c4b5a69';
const REF = { path: '/entities/test-units', labelField: 'name', keyField: 'id', paged: true };

interface Case {
  /** The field's extra parameters as form-meta gives them. */
  extra: Partial<FormFieldMeta>;
  /** A valid value as the form holds it, and an invalid one; null when only the server can tell (a record, a file). */
  valid: unknown;
  invalid: unknown;
  /** The selector of the control the form draws for it, inside the field. */
  control: string;
  /** The list type and format the server's list field has (`FieldType.listType`, `formatted`). */
  list: QueryFieldType;
  format: string | null;
  /** A value as a list row holds it and its words in a cell. */
  row: unknown;
  cell: string;
  /** Its words on the card and in the history, when they differ from the cell (a date in the viewer's format). */
  words?: string;
}

const CASES: Record<FormFieldType, Case> = {
  text: {
    extra: { maxLength: 5 },
    valid: 'Shelf',
    invalid: 'Too long',
    control: 'input[type="text"]',
    list: 'text',
    format: null,
    row: 'Shelf',
    cell: 'Shelf',
  },
  textarea: {
    extra: { maxLength: 5 },
    valid: 'Two',
    invalid: 'Too long',
    control: 'smt-textarea',
    list: 'text',
    format: null,
    row: 'Two',
    cell: 'Two',
  },
  markdown: {
    extra: { maxLength: 5 },
    valid: '**b**',
    invalid: 'Too long',
    control: 'ui-markdown-editor',
    list: 'text',
    format: null,
    row: '**b**',
    cell: '**b**',
  },
  number: {
    extra: { scale: 2 },
    valid: '12.5',
    invalid: '12.555',
    control: 'input[type="number"]',
    list: 'number',
    format: null,
    row: 12.5,
    cell: '12.5',
  },
  date: {
    extra: {},
    valid: '2026-10-01',
    invalid: '01.10.2026',
    control: 'smt-date-picker',
    list: 'date',
    format: null,
    row: '2026-10-01',
    cell: '2026-10-01',
    words: '01.10.2026',
  },
  datetime: {
    extra: {},
    valid: '2026-10-01T09:30',
    invalid: 'soon',
    control: 'smt-date-picker',
    list: 'instant',
    format: null,
    row: '2026-10-01T09:30:00Z',
    cell: '2026-10-01T09:30:00Z',
    words: '.10.2026',
  },
  time: {
    extra: {},
    valid: '09:30',
    invalid: '25:00',
    control: 'smt-time-picker',
    list: 'time',
    format: null,
    row: '09:30',
    cell: '09:30',
  },
  boolean: {
    extra: {},
    valid: true,
    invalid: null,
    control: '[role="switch"]',
    list: 'boolean',
    format: null,
    row: true,
    cell: 'common.yes',
  },
  select: {
    extra: { options: ['retail', 'wholesale'] },
    valid: 'retail',
    invalid: 'other',
    control: 'smt-select',
    list: 'enum',
    format: null,
    row: 'retail',
    cell: 'retail',
  },
  ref: {
    extra: { ref: REF },
    valid: 5,
    invalid: null,
    control: 'smt-data-select',
    list: 'number',
    format: null,
    row: 5,
    cell: '5',
  },
  email: {
    extra: {},
    valid: 'Ann@Example.com',
    invalid: 'ann@',
    control: 'input[type="email"]',
    list: 'text',
    format: 'email',
    row: 'ann@example.com',
    cell: 'ann@example.com',
  },
  phone: {
    extra: {},
    valid: '+998 (90) 123-45-67',
    invalid: '8901234567',
    control: 'input[type="tel"]',
    list: 'text',
    format: 'phone',
    row: '+998901234567',
    cell: '+998901234567',
  },
  url: {
    extra: {},
    valid: 'https://example.com',
    invalid: 'ftp://example.com',
    control: 'input[type="url"]',
    list: 'text',
    format: 'url',
    row: 'https://example.com',
    cell: 'https://example.com',
  },
  money: {
    extra: { currencies: ['UZS', 'USD'] },
    valid: { amount: '1250.50', currency: 'UZS' },
    invalid: { amount: '10', currency: 'EUR' },
    control: 'smt-money-field input[inputmode="decimal"]',
    list: 'number',
    format: 'money',
    row: { amount: '1250.50', currency: 'UZS' },
    cell: 'UZS',
  },
  enum: {
    extra: { options: ['kg', 'pc'], optionLabels: { kg: 'Kilogram', pc: 'Piece' } },
    valid: 'kg',
    invalid: 'zz',
    control: 'smt-select',
    list: 'enum',
    format: 'enum',
    row: 'kg',
    cell: 'Kilogram',
  },
  multi_ref: {
    extra: { ref: REF, maxItems: 2 },
    valid: [1, 2],
    invalid: [1, 2, 3],
    control: 'smt-multi-data-select',
    list: 'ref_set',
    format: 'multi_ref',
    row: [3, 9],
    cell: '3, 9',
  },
  file: {
    extra: {},
    valid: FILE_ID,
    invalid: null,
    control: 'smt-file-field smt-dropzone',
    list: 'object',
    format: 'file',
    row: { id: FILE_ID, name: 'act.pdf' },
    cell: 'act.pdf',
  },
  image: {
    extra: { contentTypes: ['image/png'] },
    valid: FILE_ID,
    invalid: null,
    control: 'smt-file-field smt-dropzone',
    list: 'object',
    format: 'image',
    row: { id: FILE_ID, name: 'shelf.png' },
    cell: 'shelf.png',
  },
  json: {
    extra: { jsonRoot: 'object' },
    valid: '{"a": 1}',
    invalid: '[1, 2]',
    control: 'smt-textarea',
    list: 'object',
    format: 'json',
    row: { a: 1 },
    cell: '{"a":1}',
  },
};

@Component({
  imports: [SMTEntityFormComponent],
  template: `<smt-entity-form [meta]="meta()" [(value)]="values" />`,
})
class HostComponent {
  readonly meta = signal<FormMeta>({ code: 't', fields: [], layout: [], actions: [], capabilities: [] });
  readonly values = signal<FormValues>({});
}

async function render(type: FormFieldType): Promise<HTMLElement> {
  const api = { get: vi.fn(() => of({ items: [], nextCursor: null, hasMore: false })), post: vi.fn() };
  await TestBed.configureTestingModule({
    imports: [HostComponent],
    providers: [{ provide: ApiService, useValue: api }],
  }).compileComponents();
  const fixture = TestBed.createComponent(HostComponent);
  const field = formField('value', type, CASES[type].extra);
  fixture.componentInstance.meta.set({
    code: 't',
    fields: [field],
    layout: [{ key: 'main', labelKey: 'entity.section.main', fields: ['value'] }],
    actions: [],
    capabilities: [],
  });
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('field type matrix', () => {
  it('covers every type the server declares, each once', () => {
    expect(Object.keys(CASES).sort()).toEqual([...FORM_FIELD_TYPES].sort());
    expect(Object.keys(ENTITY_CONTROLS).sort()).toEqual([...FORM_FIELD_TYPES].sort());
    expect(Object.keys(FIELD_VALUE_RULES).sort()).toEqual([...FORM_FIELD_TYPES].sort());
    expect(Object.keys(FIELD_TEXT).sort()).toEqual([...FORM_FIELD_TYPES].sort());
  });

  describe.each(FORM_FIELD_TYPES)('%s', (type) => {
    const field = formField('value', type, CASES[type].extra);

    it('has a form control', async () => {
      const root = await render(type);
      expect(root.querySelector(`[data-field="value"] ${CASES[type].control}`)).not.toBeNull();
    });

    it('checks a value as the server does', () => {
      const rules = FIELD_VALUE_RULES[type];
      expect(rules.problem(field, CASES[type].valid)).toBeNull();
      if (CASES[type].invalid !== null) expect(rules.problem(field, CASES[type].invalid)).not.toBeNull();
      expect(rules.empty(null)).toBe(true);
      expect(rules.empty(CASES[type].valid)).toBe(false);
    });

    it('shows a value in words and in a list cell', () => {
      const words = FIELD_TEXT[type](field, CASES[type].row, (key) => key, { name: () => null });
      expect(words).toContain(CASES[type].words ?? CASES[type].cell);

      const meta = {
        code: 't',
        defaultSort: 'value',
        defaultLimit: 50,
        maxLimit: 200,
        maxConditions: 20,
        maxInValues: 100,
        fields: [
          metaField('value', 'test.value', CASES[type].list, {
            format: CASES[type].format,
            ref: type === 'ref' || type === 'multi_ref' ? REF : null,
            enumValues: type === 'select' ? ['retail', 'wholesale'] : type === 'enum' ? ['kg', 'pc'] : [],
            enumLabels: type === 'enum' ? { kg: 'Kilogram', pc: 'Piece' } : null,
          }),
        ],
      } as QueryListMeta;
      const config = registryTableConfig<Record<string, unknown>>(meta, {
        translate: (key) => key,
        trackBy: (_index, row) => row,
        ariaLabel: 'matrix',
        sort: null,
      });
      const content = config.columns['value'].content;
      const shown = content.type === 'primitive' ? content.value({ value: CASES[type].row }) : CASES[type].row;
      expect(String(shown)).toContain(CASES[type].cell);
    });
  });
});
