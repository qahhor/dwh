import { describe, expect, it } from 'vitest';
import { documentChanges, incompatibleChanges } from '../forms/form-version.rules';
import { documentContext } from './new-form-document';
import { columnField, fieldColumn } from './new-form-lines.component';
import { numberExample } from './new-form-numbering.component';
import { initialWorkflow } from './new-form-workflow.component';
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
