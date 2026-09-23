import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { SMTTreeTableComponent, TreeTableColumns } from './tree-table.component';
import { flattenTree, TreeRow } from './tree.utils';

interface Unit { id: number; name: string; kind: string; children?: Unit[] }

const tree: Unit[] = [
  { id: 1, name: 'Company', kind: 'company', children: [
    { id: 2, name: 'North', kind: 'region', children: [{ id: 3, name: 'Branch A', kind: 'branch' }, { id: 4, name: 'Branch B', kind: 'branch' }] },
    { id: 5, name: 'South', kind: 'region' },
  ] },
];

@Component({
  standalone: true,
  imports: [SMTTreeTableComponent],
  template: `<smt-tree-table [smtRows]="rows" [smtColumns]="columns" smtAriaLabel="Divisions" [smtSearch]="search()"
    [smtSearchText]="text" [smtSelectedId]="selected()" (smtSelect)="selected.set($event.id)" />`,
})
class HostComponent {
  readonly rows: TreeRow<Unit>[] = flattenTree(tree, { id: unit => unit.id, children: unit => unit.children, data: unit => unit });
  readonly columns: TreeTableColumns<Unit> = {
    treeColumn: 'name',
    columnsOrder: ['name', 'kind'],
    columns: {
      name: { header: { type: 'primitive', value: 'Name' }, content: { type: 'primitive', value: row => row.data.name } },
      kind: { header: { type: 'primitive', value: 'Kind' }, content: { type: 'primitive', value: row => row.data.kind } },
    },
  };
  readonly text = (row: TreeRow<Unit>) => row.data.name;
  readonly search = signal('');
  readonly selected = signal<string | null>(null);
}

async function render() {
  await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
  const fixture = TestBed.createComponent(HostComponent);
  await settle(fixture);
  return fixture;
}

async function settle(fixture: ComponentFixture<HostComponent>) {
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}

const bodyRows = (fixture: ComponentFixture<HostComponent>) =>
  [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('[role="treegrid"] [role="rowgroup"] > [role="row"]')];
const shown = (fixture: ComponentFixture<HostComponent>) => bodyRows(fixture).map(row => row.dataset['smtRowId']);
const row = (fixture: ComponentFixture<HostComponent>, id: string) => bodyRows(fixture).find(item => item.dataset['smtRowId'] === id)!;

async function press(fixture: ComponentFixture<HostComponent>, id: string, key: string) {
  row(fixture, id).dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  await settle(fixture);
}

describe('smt-tree-table', () => {
  it('is a named treegrid with level, position and expanded state on each row', async () => {
    const fixture = await render();
    const grid = (fixture.nativeElement as HTMLElement).querySelector('[role="treegrid"]')!;
    expect(grid.getAttribute('aria-label')).toBe('Divisions');
    expect(shown(fixture)).toEqual(['1', '2', '3', '4', '5']);
    const north = row(fixture, '2');
    expect(north.getAttribute('aria-level')).toBe('2');
    expect(north.getAttribute('aria-expanded')).toBe('true');
    expect(north.getAttribute('aria-setsize')).toBe('2');
    expect(north.getAttribute('aria-posinset')).toBe('1');
    // A leaf has no expanded state at all.
    expect(row(fixture, '3').hasAttribute('aria-expanded')).toBe(false);
  });

  it('has exactly one Tab stop', async () => {
    const fixture = await render();
    expect(bodyRows(fixture).filter(item => item.tabIndex === 0).map(item => item.dataset['smtRowId'])).toEqual(['1']);
    // The expand buttons are pointer affordances, not extra Tab stops.
    const toggles = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="treegrid"] button')];
    expect(toggles.length).toBeGreaterThan(0);
    expect(toggles.every(button => button.tabIndex === -1)).toBe(true);
  });

  it('moves, closes and opens branches with the arrow keys', async () => {
    const fixture = await render();
    await press(fixture, '1', 'ArrowDown');
    expect(row(fixture, '2').tabIndex).toBe(0);
    expect(document.activeElement).toBe(row(fixture, '2'));

    await press(fixture, '2', 'ArrowLeft');
    expect(shown(fixture)).toEqual(['1', '2', '5']);
    expect(row(fixture, '2').getAttribute('aria-expanded')).toBe('false');

    await press(fixture, '2', 'ArrowRight');
    expect(shown(fixture)).toEqual(['1', '2', '3', '4', '5']);
    await press(fixture, '2', 'ArrowRight');
    expect(document.activeElement).toBe(row(fixture, '3'));

    // From a leaf, Left goes to the parent.
    await press(fixture, '3', 'ArrowLeft');
    expect(document.activeElement).toBe(row(fixture, '2'));

    await press(fixture, '2', 'End');
    expect(document.activeElement).toBe(row(fixture, '5'));
    await press(fixture, '5', 'Home');
    expect(document.activeElement).toBe(row(fixture, '1'));
  });

  it('chooses a row with Enter, Space or a click, and marks it selected', async () => {
    const fixture = await render();
    await press(fixture, '1', 'Enter');
    expect(fixture.componentInstance.selected()).toBe('1');
    await press(fixture, '2', ' ');
    expect(fixture.componentInstance.selected()).toBe('2');
    row(fixture, '4').click();
    await settle(fixture);
    expect(fixture.componentInstance.selected()).toBe('4');
    expect(row(fixture, '4').getAttribute('aria-selected')).toBe('true');
    expect(row(fixture, '2').getAttribute('aria-selected')).toBe('false');
  });

  it('shows each search match inside its ancestors, even inside a collapsed branch', async () => {
    const fixture = await render();
    await press(fixture, '2', 'ArrowLeft');
    fixture.componentInstance.search.set('branch b');
    await settle(fixture);
    expect(shown(fixture)).toEqual(['1', '2', '4']);
    // Positions count the siblings on screen.
    expect(row(fixture, '4').getAttribute('aria-setsize')).toBe('1');

    fixture.componentInstance.search.set('');
    await settle(fixture);
    // Clearing the search restores the branch the user had closed.
    expect(shown(fixture)).toEqual(['1', '2', '5']);
  });

  it('expands and collapses everything from the toolbar', async () => {
    const fixture = await render();
    const [expandAll, collapseAll] = [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(':scope smt-tree-table > div button')];
    collapseAll.click();
    await settle(fixture);
    expect(shown(fixture)).toEqual(['1']);
    expandAll.click();
    await settle(fixture);
    expect(shown(fixture)).toEqual(['1', '2', '3', '4', '5']);
  });
});
