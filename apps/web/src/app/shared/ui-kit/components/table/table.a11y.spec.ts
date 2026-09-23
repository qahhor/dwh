/* Not vendored: tests for the table semantics added here (ADR-0015 rule 2). */
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SMTTableComponent } from './table.component';
import { TableConfig, TableRowKeydownEvent } from './table.types';

interface Row { id: number; name: string }

const rows: Row[] = [{ id: 1, name: 'Tashkent' }, { id: 2, name: 'Samarkand' }, { id: 3, name: 'Bukhara' }];

function config(extra: Partial<TableConfig<Row>> = {}): TableConfig<Row> {
  return {
    trackBy: (_index, row) => row.id,
    columns: {
      name: { header: { type: 'primitive', value: 'Name' }, content: { type: 'primitive', value: row => row.name }, hasSorting: true },
      id: { header: { type: 'primitive', value: 'ID' }, content: { type: 'primitive', value: row => row.id } },
    },
    columnsOrder: ['name', 'id'],
    ariaLabel: 'Branches',
    ...extra,
  };
}

@Component({
  standalone: true,
  imports: [SMTTableComponent],
  template: `<smt-table [smtData]="data()" [smtConfig]="config()" [smtVirtualRows]="false" (smtRowKeydown)="keys.push($event)" />`,
})
class HostComponent {
  readonly data = signal(rows);
  readonly config = signal(config());
  readonly keys: TableRowKeydownEvent<Row>[] = [];
}

async function render(extra: Partial<TableConfig<Row>> = {}) {
  await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
  const fixture = TestBed.createComponent(HostComponent);
  fixture.componentInstance.config.set(config(extra));
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, root: fixture.nativeElement as HTMLElement };
}

describe('smt-table semantics', () => {
  it('is a named table whose row count includes the header', async () => {
    const { root } = await render();
    const table = root.querySelector('[role="table"]')!;
    expect(table.getAttribute('aria-label')).toBe('Branches');
    expect(table.getAttribute('aria-rowcount')).toBe('4');
  });

  it('exposes header, rows and cells in order', async () => {
    const { root } = await render();
    // Decorative glyphs are aria-hidden, so they are not part of the accessible name.
    const name = (cell: Element) => {
      const copy = cell.cloneNode(true) as Element;
      copy.querySelectorAll('[aria-hidden="true"]').forEach(node => node.remove());
      return copy.textContent?.trim();
    };
    const headers = [...root.querySelectorAll('[role="columnheader"]')].map(name);
    expect(headers).toEqual(['Name', 'ID']);
    const bodyRows = [...root.querySelectorAll('[role="rowgroup"] > [role="row"]')];
    expect(bodyRows.map(row => row.getAttribute('aria-rowindex'))).toEqual(['2', '3', '4']);
    expect(bodyRows[0].querySelectorAll('[role="cell"]').length).toBe(2);
  });

  it('states sort on the columnheader and cycles it from the keyboard', async () => {
    const { fixture, root } = await render();
    const header = root.querySelector('[role="columnheader"][aria-sort]')!;
    expect(header.getAttribute('aria-sort')).toBe('none');
    // Only the sortable column claims a sort state.
    expect(root.querySelectorAll('[role="columnheader"][aria-sort]').length).toBe(1);

    const control = header.querySelector<HTMLElement>('[role="button"]')!;
    expect(control.tabIndex).toBe(0);
    control.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    fixture.detectChanges();
    expect(header.getAttribute('aria-sort')).toBe('ascending');
    control.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    fixture.detectChanges();
    expect(header.getAttribute('aria-sort')).toBe('descending');
  });

  it('leaves plain tables without row focus or tree state', async () => {
    const { root } = await render();
    const row = root.querySelector('[role="rowgroup"] > [role="row"]')!;
    expect(row.hasAttribute('tabindex')).toBe(false);
    expect(row.hasAttribute('aria-level')).toBe(false);
    expect(row.hasAttribute('aria-selected')).toBe(false);
  });

  it('carries treegrid state and forwards row keys to the owner', async () => {
    const { fixture, root } = await render({
      ariaRole: 'treegrid',
      rowAria: row => ({
        id: String(row.id), level: row.id === 1 ? 1 : 2, expanded: row.id === 1 ? true : null,
        setSize: row.id === 1 ? 1 : 2, posInSet: row.id === 1 ? 1 : row.id - 1, selected: row.id === 2,
        tabindex: row.id === 2 ? 0 : -1,
      }),
    });
    expect(root.querySelector('[role="treegrid"]')).not.toBeNull();
    const [first, second] = [...root.querySelectorAll<HTMLElement>('[role="rowgroup"] > [role="row"]')];
    expect(first.getAttribute('aria-level')).toBe('1');
    expect(first.getAttribute('aria-expanded')).toBe('true');
    expect(second.hasAttribute('aria-expanded')).toBe(false);
    expect(second.getAttribute('aria-selected')).toBe('true');
    expect(second.tabIndex).toBe(0);
    expect(first.tabIndex).toBe(-1);
    expect(second.dataset['smtRowId']).toBe('2');
    expect(first.querySelectorAll('[role="gridcell"]').length).toBe(2);

    second.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    expect(fixture.componentInstance.keys.map(entry => [entry.row.id, entry.event.key])).toEqual([[2, 'ArrowDown']]);
  });
});
