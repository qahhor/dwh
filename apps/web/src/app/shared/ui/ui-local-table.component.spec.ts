import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { TableConfig } from '../ui-kit/components/table/table.types';
import { UiLocalTableComponent } from './ui-local-table.component';

interface Row { id: number; name: string; size: number }

@Component({
  standalone: true,
  imports: [UiLocalTableComponent],
  template: `<ui-local-table [rows]="rows" [config]="config" [sortValues]="sortValues" (rowClick)="clicked.push($event.id)" />`,
})
class HostComponent {
  rows: Row[] = [{ id: 1, name: 'Beta', size: 20 }, { id: 2, name: 'alpha', size: 5 }, { id: 3, name: 'Gamma', size: 10 }];
  clicked: number[] = [];
  readonly sortValues = { name: (row: Row) => row.name, size: (row: Row) => row.size };
  readonly config: TableConfig<Row> = {
    trackBy: (_index, row) => row.id,
    ariaLabel: 'Rows',
    columns: {
      name: { header: { type: 'primitive', value: 'Name' }, content: { type: 'primitive', value: row => row.name } },
      size: { header: { type: 'primitive', value: 'Size' }, content: { type: 'primitive', value: row => row.size } },
      note: { header: { type: 'primitive', value: 'Note' }, content: { type: 'primitive', value: () => '-' } },
    },
    columnsOrder: ['name', 'size', 'note'],
  };
}

async function render(): Promise<ComponentFixture<HostComponent>> {
  await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
  const fixture = TestBed.createComponent(HostComponent);
  fixture.detectChanges();
  return fixture;
}
const names = (fixture: ComponentFixture<HostComponent>) =>
  [...(fixture.nativeElement as HTMLElement).querySelectorAll('[role="rowgroup"] > [role="row"]')].map(row => row.querySelector('[role="cell"]')?.textContent?.trim());
const headers = (fixture: ComponentFixture<HostComponent>) =>
  [...(fixture.nativeElement as HTMLElement).querySelectorAll('[role="columnheader"]')] as HTMLElement[];
function clickHeader(fixture: ComponentFixture<HostComponent>, index: number) {
  headers(fixture)[index].querySelector('smt-cell-header')!.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  fixture.detectChanges();
}

describe('ui-local-table', () => {
  it('keeps the given order until a header is clicked, then sorts every row', async () => {
    const fixture = await render();
    expect(names(fixture)).toEqual(['Beta', 'alpha', 'Gamma']);

    clickHeader(fixture, 0);
    expect(names(fixture)).toEqual(['alpha', 'Beta', 'Gamma']);
    expect(headers(fixture)[0].getAttribute('aria-sort')).toBe('ascending');

    clickHeader(fixture, 1);
    expect(names(fixture)).toEqual(['alpha', 'Gamma', 'Beta']);
    expect(headers(fixture)[0].getAttribute('aria-sort')).toBe('none');
  });

  it('offers sorting only where a value reader exists and passes row clicks on', async () => {
    const fixture = await render();
    expect(headers(fixture)[2].hasAttribute('aria-sort')).toBe(false);

    ([...fixture.nativeElement.querySelectorAll('[role="rowgroup"] > [role="row"]')] as HTMLElement[])[1].click();
    expect(fixture.componentInstance.clicked).toEqual([2]);
  });
});
