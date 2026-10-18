import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { I18nService } from '@core/services/i18n.service';
import { StudioFormulasApi } from '../studio-formulas.api';
import { documentChanges, incompatibleChanges } from '../forms/form-version.rules';
import { documentContext } from './new-form-document';
import { columnField, fieldColumn, NewFormLinesComponent } from './new-form-lines.component';
import { NewFormNumberingComponent, numberExample } from './new-form-numbering.component';
import { initialWorkflow, NewFormWorkflowComponent } from './new-form-workflow.component';
import {
  editorDeclaration,
  titleOf,
  usesFormatSix,
  withFormat,
  type SimpleFormDeclaration,
  type SimpleFormLines,
  type SimpleFormWorkflow,
} from './new-form.models';

const LABELS = { ru: 'Метка', uz: 'Yorliq', en: 'Label' };

function order(): SimpleFormDeclaration {
  return {
    formatVersion: 6,
    kind: 'simple-form',
    module: { code: 'studio', title: LABELS },
    entity: { name: 'orders', title: LABELS, numbering: { pattern: 'ZK-{YYYY}-{NNNNNN}', reset: 'year' } },
    fields: [
      { id: 'discount', type: 'number', labels: LABELS, required: false, listed: true, scale: 2 },
      {
        id: 'total',
        type: 'number',
        labels: LABELS,
        required: false,
        listed: true,
        scale: 2,
        formula: 'sum(lines.items.amount)',
      },
    ],
    lines: [
      {
        key: 'items',
        labels: LABELS,
        maxRows: 50,
        fields: [
          { id: 'qty', type: 'number', labels: LABELS, required: true, scale: 3 },
          { id: 'price', type: 'number', labels: LABELS, required: true, scale: 2 },
          { id: 'amount', type: 'number', labels: LABELS, required: false, scale: 2, formula: 'qty * price' },
          {
            id: 'product',
            type: 'select',
            labels: LABELS,
            required: false,
            options: [
              { code: 'tea', labels: LABELS },
              { code: 'coffee', labels: LABELS },
            ],
          },
        ],
      },
    ],
    workflow: initialWorkflow(() => LABELS),
  };
}

describe('documents of live forms in the editor (ADR-0043)', () => {
  it('says when a declaration is a document and writes it in format 6', () => {
    const declaration = order();
    expect(usesFormatSix(declaration)).toBe(true);
    const written = withFormat({ ...declaration, formatVersion: 1, lines: [], entity: { ...declaration.entity } }, 1);
    expect(written.formatVersion).toBe(6);
    expect(written.lines).toBeUndefined();
    // A new document is named by its number, not by the system name of earlier formats.
    expect(written.entity).not.toHaveProperty('titleField');
    const condition = withFormat(
      {
        ...declaration,
        workflow: {
          ...(declaration.workflow as SimpleFormWorkflow),
          transitions: [
            { code: 'go', labels: LABELS, from: ['draft'], to: 'pending', comment: 'none', condition: ' ' },
          ],
        },
      },
      1,
    );
    expect(condition.workflow?.transitions[0]).not.toHaveProperty('condition');
    expect(titleOf({ ...declaration, entity: { ...declaration.entity, titleField: undefined } })).toBe('docNumber');
    expect(editorDeclaration(declaration).entity.titleField).toBe('docNumber');
    expect(withFormat(editorDeclaration(declaration)).entity).not.toHaveProperty('titleField');
    expect(
      usesFormatSix({ ...declaration, lines: undefined, workflow: undefined, entity: { name: 'x', title: LABELS } }),
    ).toBe(false);
  });

  it('gives the formulas the lines, the number and the status they read', () => {
    const context = documentContext(order());
    expect(context?.numbered).toBe(true);
    expect(context?.statuses).toBe(true);
    expect(context?.lines[0].fields.map((column) => column.id)).toEqual(['qty', 'price', 'amount', 'product']);
    expect(
      documentContext({ ...order(), lines: undefined, workflow: undefined, entity: { name: 'x', title: LABELS } }),
    ).toBeNull();
  });

  it('finds the changes of a document the server refuses', () => {
    const active = order();
    expect(documentChanges(active, structuredClone(active))).toEqual([]);
    const draft = structuredClone(active);
    (draft.lines as SimpleFormLines[])[0].maxRows = 10;
    const columns = (draft.lines as SimpleFormLines[])[0].fields;
    columns[0] = { ...columns[0], type: 'text' };
    columns[1] = { ...columns[1], scale: 1, required: true };
    columns[3] = { ...columns[3], options: [{ code: 'tea', labels: LABELS }] };
    columns.splice(2, 1);
    columns.push({ id: 'note', type: 'text', labels: LABELS, required: true, maxLength: 10 });
    draft.entity.numbering = { pattern: 'X-{NNN}', reset: 'never' };
    (draft.workflow as SimpleFormWorkflow).states = (draft.workflow as SimpleFormWorkflow).states.slice(1, 3);
    expect(incompatibleChanges(active, draft).map((change) => `${change.field}:${change.change}`)).toEqual([
      'lines.items:max_rows',
      'lines.items.qty:type',
      'lines.items.price:scale',
      'lines.items.amount:removed',
      'lines.items.product:option_removed',
      'lines.items.note:required',
      'entity.numbering:numbering',
      'workflow.states.draft:initial',
      'workflow.states.draft:state_removed',
      'workflow.states.rejected:state_removed',
    ]);
    expect(documentChanges(active, { ...structuredClone(active), lines: undefined, workflow: undefined })).toEqual([
      { field: 'lines.items', change: 'lines_moved' },
      { field: 'workflow', change: 'workflow_removed' },
    ]);
    const computed = structuredClone(active);
    (computed.lines as SimpleFormLines[])[0].fields[0].formula = 'price';
    (computed.lines as SimpleFormLines[])[0].fields[0].maxLength = 1;
    expect(documentChanges(active, computed)).toEqual([{ field: 'lines.items.qty', change: 'computed' }]);
  });

  it('writes the first number of a pattern', () => {
    const day = new Date(2026, 2, 7);
    expect(numberExample('ZK-{YYYY}-{NNNNNN}', day)).toBe('ZK-2026-000001');
    expect(numberExample('{YY}{MM}/{N}', day)).toBe('2603/1');
  });

  it('keeps a column as the field editor edits it', () => {
    const column = (order().lines as SimpleFormLines[])[0].fields[2];
    expect(columnField(column).listed).toBe(true);
    expect(fieldColumn({ ...columnField(column), hidden: true, defaultValue: 'x' })).toEqual(column);
    expect(
      fieldColumn({
        id: 'ref',
        type: 'ref',
        labels: LABELS,
        required: true,
        listed: true,
        maxLength: 5,
        target: { kind: 'list', code: 'goods' },
      }),
    ).toEqual({
      id: 'ref',
      type: 'ref',
      labels: LABELS,
      required: true,
      maxLength: 5,
      target: { kind: 'list', code: 'goods' },
    });
  });
});

describe('NewFormLinesComponent', () => {
  async function render(lines?: SimpleFormLines[], stored?: SimpleFormLines[]) {
    await TestBed.configureTestingModule({
      imports: [NewFormLinesComponent],
      providers: [{ provide: StudioFormulasApi, useValue: { check: () => of({ valid: true }) } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(NewFormLinesComponent);
    fixture.componentRef.setInput('lines', lines);
    fixture.componentRef.setInput('stored', stored);
    fixture.componentRef.setInput('numbered', true);
    fixture.detectChanges();
    const emit = vi.spyOn(fixture.componentInstance.changed, 'emit');
    const last = () => emit.mock.lastCall?.[0] as SimpleFormLines[] | undefined;
    return { fixture, component: fixture.componentInstance, emit, last, root: fixture.nativeElement as HTMLElement };
  }

  it('adds, edits, moves and removes tables and columns', async () => {
    const { fixture, component, last, emit } = await render();
    component.addTable();
    const added = last() as SimpleFormLines[];
    expect(added[0].key).toBe('lines1');
    fixture.componentRef.setInput('lines', added);
    fixture.detectChanges();
    component.patch(0, { key: 'items' });
    expect(last()?.[0].key).toBe('items');
    component.setLabel(0, 'ru', 'Строки');
    expect(last()?.[0].labels.ru).toBe('Строки');
    component.setMaxRows(0, 20);
    expect(last()?.[0].maxRows).toBe(20);
    component.setMaxRows(0, 'x');
    component.addColumn(0);
    const twoColumns = last() as SimpleFormLines[];
    expect(twoColumns[0].fields.map((column) => column.id)).toEqual(['name', 'column2']);
    fixture.componentRef.setInput('lines', twoColumns);
    fixture.detectChanges();
    component.setColumn(0, 1, { ...columnField(twoColumns[0].fields[1]), formula: 'name' });
    expect(last()?.[0].fields[1].formula).toBe('name');
    component.moveColumn(0, 0, 1);
    expect(last()?.[0].fields.map((column) => column.id)).toEqual(['column2', 'name']);
    component.moveColumn(0, 0, -1);
    component.removeColumn(0, 1);
    expect(last()?.[0].fields.map((column) => column.id)).toEqual(['name']);
    expect(component.documentOf('lines1').line).toBe('lines1');
    expect(component.documentOf('lines1').numbered).toBe(true);
    const calls = emit.mock.calls.length;
    component.patch(7, { key: 'x' });
    component.setColumn(7, 0, columnField(twoColumns[0].fields[0]));
    component.addColumn(7);
    expect(emit.mock.calls.length).toBe(calls);
    component.removeTable(0);
    expect(last()).toBeUndefined();
    expect(component.text(null)).toBe('');
  });

  it('keeps a stored table, its rows and its columns', async () => {
    const stored = order().lines as SimpleFormLines[];
    const { component, emit } = await render(structuredClone(stored), stored);
    const table = component.tables()[0];
    expect(component.storedTable(table)).not.toBeNull();
    expect(component.minRows(table)).toBe(50);
    expect(component.storedColumn(table, table.fields[0])?.id).toBe('qty');
    component.removeTable(0);
    component.removeColumn(0, 0);
    expect(emit).not.toHaveBeenCalled();
  });
});

describe('NewFormNumberingComponent and NewFormWorkflowComponent', () => {
  it('numbers a document and gives the number on a status', async () => {
    await TestBed.configureTestingModule({ imports: [NewFormNumberingComponent] }).compileComponents();
    const fixture = TestBed.createComponent(NewFormNumberingComponent);
    fixture.componentRef.setInput('states', initialWorkflow(() => LABELS).states);
    fixture.detectChanges();
    const emit = vi.spyOn(fixture.componentInstance.changed, 'emit');
    const component = fixture.componentInstance;
    component.toggle(true);
    const numbering = emit.mock.lastCall?.[0];
    expect(numbering).toEqual({ pattern: 'DOC-{YYYY}-{NNNNNN}', reset: 'year' });
    fixture.componentRef.setInput('numbering', numbering);
    fixture.detectChanges();
    expect(component.example()).toMatch(/^DOC-\d{4}-000001$/);
    expect(component.assignOptions().map((option) => option.id)).toEqual([
      '',
      'draft',
      'pending',
      'approved',
      'rejected',
    ]);
    expect(component.resetOptions()).toHaveLength(3);
    component.setReset('month');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ reset: 'month' });
    component.setReset('week');
    component.setAssignOn('approved');
    expect(emit.mock.lastCall?.[0]).toMatchObject({ assignOn: 'approved' });
    component.setAssignOn('');
    expect(emit.mock.lastCall?.[0]).not.toHaveProperty('assignOn');
    component.patch({ pattern: 'A-{NN}' });
    expect(emit.mock.lastCall?.[0]).toMatchObject({ pattern: 'A-{NN}' });
    component.toggle(false);
    expect(emit.mock.lastCall?.[0]).toBeUndefined();
    fixture.componentRef.setInput('locked', true);
    const calls = emit.mock.calls.length;
    component.toggle(true);
    expect(emit.mock.calls.length).toBe(calls);
    expect(component.text(undefined)).toBe('');
  });

  it('edits statuses and transitions and keeps the stored ones', async () => {
    await TestBed.configureTestingModule({
      imports: [NewFormWorkflowComponent],
      providers: [{ provide: StudioFormulasApi, useValue: { check: () => of({ valid: true }) } }],
    }).compileComponents();
    const fixture = TestBed.createComponent(NewFormWorkflowComponent);
    const component = fixture.componentInstance;
    const emit = vi.spyOn(component.changed, 'emit');
    const last = () => emit.mock.lastCall?.[0] as SimpleFormWorkflow;
    component.toggle(true);
    const workflow = last();
    expect(workflow.states.map((state) => state.code)).toEqual(['draft', 'pending', 'approved', 'rejected']);
    expect(TestBed.inject(I18nService)).toBeTruthy();
    fixture.componentRef.setInput('workflow', workflow);
    fixture.detectChanges();
    expect(component.leavableOptions().map((option) => option.id)).toEqual(['draft', 'pending']);
    expect(component.colorOptions()).toHaveLength(6);
    expect(component.commentOptions()).toHaveLength(3);
    component.addState();
    expect(last().states.at(-1)?.code).toBe('status5');
    component.patchState(0, { code: 'new' });
    expect(last().states[0].code).toBe('new');
    component.setStateLabel(1, 'en', 'Waiting');
    expect(last().states[1].labels.en).toBe('Waiting');
    component.setColor(1, 'purple');
    expect(last().states[1].color).toBe('purple');
    component.setColor(1, 'pink');
    component.setStateFlag(1, 'terminal', true);
    expect(last().states[1].terminal).toBe(true);
    component.setStateFlag(2, 'terminal', false);
    expect(last().states[2]).not.toHaveProperty('terminal');
    component.setStateFlag(0, 'terminal', true);
    component.addTransition();
    expect(last().transitions.at(-1)).toMatchObject({ code: 'step4', from: ['draft'], to: 'pending' });
    component.setTransitionLabel(0, 'ru', 'Отправить');
    component.setFrom(0, ['draft', 'pending']);
    expect(last().transitions[0].from).toEqual(['draft', 'pending']);
    component.setTo(0, 'approved');
    expect(last().transitions[0].to).toBe('approved');
    component.setComment(0, 'required');
    expect(last().transitions[0].comment).toBe('required');
    component.setComment(0, 'maybe');
    component.setTransitionFlag(0, 'notify', true);
    expect(last().transitions[0].notify).toBe(true);
    component.setTransitionFlag(1, 'notAuthor', false);
    expect(last().transitions[1]).not.toHaveProperty('notAuthor');
    component.setCondition(0, 'total > 0');
    expect(last().transitions[0].condition).toBe('total > 0');
    component.setCondition(0, undefined);
    expect(last().transitions[0]).not.toHaveProperty('condition');
    component.patchTransition(0, { code: 'send' });
    expect(last().transitions[0].code).toBe('send');
    component.removeTransition(2);
    expect(last().transitions.map((transition) => transition.code)).toEqual(['submit', 'approve']);
    component.removeState(3);
    expect(last().states.map((state) => state.code)).toEqual(['draft', 'pending', 'approved']);
    expect(last().transitions.map((transition) => transition.code)).toEqual(['submit', 'approve']);
    fixture.componentRef.setInput('stored', workflow);
    fixture.detectChanges();
    const calls = emit.mock.calls.length;
    component.removeState(3);
    component.toggle(false);
    expect(emit.mock.calls.length).toBe(calls);
    expect(component.storedState(workflow.states[1])).toBe(true);
    expect(component.text(null)).toBe('');
  });
});
