import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { StudioFormulasApi } from '../studio-formulas.api';
import { columnField, NewFormLinesComponent } from './new-form-lines.component';
import type { SimpleFormLines } from './new-form.models';

const LABELS = { ru: 'Метка', uz: 'Yorliq', en: 'Label' };

/** A stored table of an order: its rows and columns cannot shrink. */
function storedLines(): SimpleFormLines[] {
  return [
    {
      key: 'items',
      labels: LABELS,
      maxRows: 50,
      fields: [
        { id: 'qty', type: 'number', labels: LABELS, required: true, scale: 3 },
        { id: 'price', type: 'number', labels: LABELS, required: true, scale: 2 },
      ],
    },
  ];
}

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
    const stored = storedLines();
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
