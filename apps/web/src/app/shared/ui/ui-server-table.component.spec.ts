import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { KeysetPager, KeysetResponse } from '../paging/keyset-pager';
import { TableConfig } from '../ui-kit/components/table/table.types';
import { UiServerTableComponent } from './ui-server-table.component';
import { SMTSelectComponent } from '../ui-kit/components/forms/select';

interface Row { id: number }

let respond: (cursor: string | null) => Observable<KeysetResponse<Row>> = () => of({ items: [], nextCursor: null });

@Component({
  standalone: true,
  imports: [UiServerTableComponent],
  template: `
    <ui-server-table [pager]="pager" [config]="config" loadingLabel="Loading rows" errorLabel="Rows failed" errorId="rows-error"
      [emptyTemplate]="empty" [countsPage]="countsPage" />
    <ng-template #empty><p class="custom-empty">No rows for these filters</p></ng-template>`,
})
class HostComponent {
  readonly pager = new KeysetPager<Row>(cursor => respond(cursor), { pageSize: 2 });
  countsPage = false;
  readonly config: TableConfig<Row> = {
    trackBy: (_index, row) => row.id,
    ariaLabel: 'Rows',
    columns: { id: { header: { type: 'primitive', value: 'ID' }, content: { type: 'primitive', value: row => `#${row.id}` } } },
    columnsOrder: ['id'],
  };
}

async function render(): Promise<ComponentFixture<HostComponent>> {
  await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
  const fixture = TestBed.createComponent(HostComponent);
  fixture.detectChanges();
  return fixture;
}
const el = (fixture: ComponentFixture<HostComponent>) => fixture.nativeElement as HTMLElement;
const rowText = (fixture: ComponentFixture<HostComponent>) =>
  [...el(fixture).querySelectorAll('[role="rowgroup"] > [role="row"]')].map(row => row.textContent?.trim());

describe('ui-server-table', () => {
  it('announces loading, then shows the rows', async () => {
    const pending = new Subject<KeysetResponse<Row>>();
    respond = () => pending;
    const fixture = await render();
    fixture.componentInstance.pager.first();
    fixture.detectChanges();
    expect(el(fixture).querySelector('[data-server-table-status]')?.textContent).toContain('Loading rows');
    expect(el(fixture).querySelector('[role="table"]')?.getAttribute('aria-busy')).toBe('true');

    pending.next({ items: [{ id: 1 }, { id: 2 }], nextCursor: null });
    fixture.detectChanges();
    expect(el(fixture).querySelector('[data-server-table-status]')).toBeNull();
    expect(rowText(fixture)).toEqual(['#1', '#2']);
  });

  it('keeps the rows on a failure and retries exactly the failed request', async () => {
    let fail = false;
    respond = cursor => fail ? throwError(() => new Error('x'))
      : of(cursor ? { items: [{ id: 3 }], nextCursor: null, totalEstimated: 3 } : { items: [{ id: 1 }, { id: 2 }], nextCursor: 'c2', hasMore: true, totalEstimated: 3 });
    const fixture = await render();
    fixture.componentInstance.pager.first();
    fixture.detectChanges();

    fail = true;
    (el(fixture).querySelector('button[aria-label="Следующая страница"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const alert = el(fixture).querySelector('#rows-error[role="alert"]')!;
    expect(alert.textContent).toContain('Rows failed');
    expect(rowText(fixture)).toEqual(['#1', '#2']);
    // Until the retry, no paging control may continue from a cursor of the failed attempt.
    for (const name of ['Следующая страница', 'Предыдущая страница']) {
      expect((el(fixture).querySelector(`button[aria-label="${name}"]`) as HTMLButtonElement | null)?.disabled ?? true).toBe(true);
    }

    fail = false;
    (alert.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('#rows-error')).toBeNull();
    expect(rowText(fixture)).toEqual(['#3']);
  });

  it('does not claim an empty result when the first page failed', async () => {
    let fail = true;
    respond = () => fail ? throwError(() => new Error('403')) : of({ items: [{ id: 1 }], nextCursor: null });
    const fixture = await render();
    fixture.componentInstance.pager.first();
    fixture.detectChanges();

    expect(el(fixture).querySelector('#rows-error[role="alert"]')?.textContent).toContain('Rows failed');
    expect(el(fixture).querySelector('.custom-empty')).toBeNull();
    expect(el(fixture).querySelector('[role="table"]')).toBeNull();

    fail = false;
    (el(fixture).querySelector('#rows-error button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('#rows-error')).toBeNull();
    expect(rowText(fixture)).toEqual(['#1']);
  });

  it('shows the screen’s own empty state', async () => {
    respond = () => of({ items: [], nextCursor: null });
    const fixture = await render();
    fixture.componentInstance.pager.first();
    fixture.detectChanges();
    expect(el(fixture).querySelector('.custom-empty')?.textContent).toContain('No rows for these filters');
  });

  it('offers the current page size among the size options', async () => {
    respond = () => of({ items: [{ id: 1 }], nextCursor: null, totalEstimated: 1 });
    const fixture = await render();
    fixture.componentInstance.pager.first();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const select = fixture.debugElement.query(By.css('ui-pagination smt-select')).componentInstance as SMTSelectComponent<number>;
    expect(select.options().map(option => option.label)).toEqual(['2', '10', '25', '50', '100']);
    expect(select.selectedOption()?.label).toBe('2');
    expect(el(fixture).querySelector('ui-pagination button[role="combobox"]')?.textContent).toContain('2');
  });

  it('shows the range on screen without a false total when the server counts only the page', async () => {
    // As the user list answers: totalEstimated is the length of the page returned.
    respond = cursor => cursor === null
      ? of({ items: [{ id: 1 }, { id: 2 }], nextCursor: 'c2', hasMore: true, totalEstimated: 2 })
      : of({ items: [{ id: 3 }], nextCursor: null, hasMore: false, totalEstimated: 1 });
    await TestBed.configureTestingModule({ imports: [HostComponent] }).compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.countsPage = true;
    fixture.detectChanges();
    fixture.componentInstance.pager.first();
    fixture.detectChanges();
    fixture.componentInstance.pager.next();
    fixture.detectChanges();
    const info = el(fixture).querySelector('.pagination-info')?.textContent?.replace(/\s+/g, ' ').trim();
    expect(info).toContain('3–3');
    expect(info).not.toMatch(/ 1$/);
    expect(el(fixture).querySelector('.pagination-info strong:nth-of-type(2)')).toBeNull();
  });
});

@Component({
  standalone: true,
  imports: [UiServerTableComponent],
  template: `
    <ui-server-table [pager]="pager" [config]="config" loadingLabel="Loading" errorLabel="Failed"
      columnsId="test.columns" [lockedColumns]="['id']" />`,
})
class ColumnsHostComponent {
  readonly pager = new KeysetPager<Row>(() => of({ items: [{ id: 1 }], nextCursor: null }), { pageSize: 2 });
  readonly config: TableConfig<Row> = {
    trackBy: (_index, row) => row.id,
    ariaLabel: 'Rows',
    columns: {
      id: { header: { type: 'primitive', value: 'ID' }, content: { type: 'primitive', value: row => `#${row.id}` } },
      name: { header: { type: 'primitive', value: 'Name' }, content: { type: 'primitive', value: row => `n${row.id}` } },
      note: { header: { type: 'primitive', value: 'Note' }, content: { type: 'primitive', value: () => 'x' } },
    },
    columnsOrder: ['id', 'name', 'note'],
  };
}

describe('ui-server-table column settings', () => {
  async function renderColumns(): Promise<ComponentFixture<ColumnsHostComponent>> {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({ imports: [ColumnsHostComponent] }).compileComponents();
    const fixture = TestBed.createComponent(ColumnsHostComponent);
    fixture.detectChanges();
    fixture.componentInstance.pager.first();
    fixture.detectChanges();
    return fixture;
  }
  const headers = (fixture: ComponentFixture<ColumnsHostComponent>) =>
    [...(fixture.nativeElement as HTMLElement).querySelectorAll('[role="columnheader"]')].map(cell => cell.textContent?.trim());

  it('hides a column, remembers the choice for the table id and restores it', async () => {
    localStorage.clear();
    const fixture = await renderColumns();
    expect(headers(fixture)).toEqual(['ID', 'Name', 'Note']);

    (fixture.nativeElement.querySelector('.smt-columns__trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
    const checks = [...document.querySelectorAll('[role="dialog"] input[type="checkbox"]')] as HTMLInputElement[];
    expect(checks[0].disabled).toBe(true);
    checks[1].click();
    fixture.detectChanges();

    expect(headers(fixture)).toEqual(['ID', 'Note']);
    expect(JSON.parse(localStorage.getItem('dwh.table-columns.v1.test.columns')!).hidden).toEqual(['name']);

    fixture.destroy();
    const again = await renderColumns();
    expect(headers(again)).toEqual(['ID', 'Note']);
    localStorage.clear();
  });

  it('keeps a dragged width and forgets a choice reset to the defaults', async () => {
    localStorage.clear();
    const fixture = await renderColumns();
    const table = fixture.debugElement.query(debug => debug.name === 'ui-server-table').componentInstance as UiServerTableComponent<Row>;

    (table as unknown as { onColumnResize(event: { key: string; widthPx: number; widthPercent: string }): void })
      .onColumnResize({ key: 'name', widthPx: 240, widthPercent: '30%' });
    expect(JSON.parse(localStorage.getItem('dwh.table-columns.v1.test.columns')!).widths).toEqual({ name: '240px' });

    (fixture.nativeElement.querySelector('.smt-columns__trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
    (document.querySelector('.smt-columns__reset') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(localStorage.getItem('dwh.table-columns.v1.test.columns')).toBeNull();
  });
});

@Component({
  standalone: true,
  imports: [UiServerTableComponent],
  template: `
    <ui-server-table [pager]="pager" [config]="config" loadingLabel="Loading" errorLabel="Failed"
      [selectable]="true" [(selected)]="chosen">
      <button bulkActions type="button" class="host-action">Archive</button>
    </ui-server-table>`,
})
class SelectHostComponent {
  readonly pager = new KeysetPager<Row>(
    cursor => of(cursor ? { items: [{ id: 3 }], nextCursor: null } : { items: [{ id: 1 }, { id: 2 }], nextCursor: 'c2', hasMore: true }),
    { pageSize: 2 });
  chosen: Row[] = [];
  readonly config: TableConfig<Row> = {
    trackBy: (_index, row) => row.id,
    ariaLabel: 'Rows',
    columns: { id: { header: { type: 'primitive', value: 'ID' }, content: { type: 'primitive', value: row => `#${row.id}` } } },
    columnsOrder: ['id'],
  };
}

describe('ui-server-table selection', () => {
  async function renderSelect(): Promise<ComponentFixture<SelectHostComponent>> {
    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({ imports: [SelectHostComponent] }).compileComponents();
    const fixture = TestBed.createComponent(SelectHostComponent);
    fixture.detectChanges();
    fixture.componentInstance.pager.first();
    fixture.detectChanges();
    return fixture;
  }
  const rowChecks = (fixture: ComponentFixture<SelectHostComponent>) =>
    [...(fixture.nativeElement as HTMLElement).querySelectorAll('[role="rowgroup"] input[type="checkbox"]')] as HTMLInputElement[];
  const bar = (fixture: ComponentFixture<SelectHostComponent>) =>
    (fixture.nativeElement as HTMLElement).querySelector('[data-testid="bulk-bar"]') as HTMLElement | null;

  it('shows the bulk bar with the count and the screen actions once rows are chosen', async () => {
    const fixture = await renderSelect();
    expect(bar(fixture)).toBeNull();
    expect(rowChecks(fixture)).toHaveLength(2);

    rowChecks(fixture)[0].click();
    fixture.detectChanges();
    rowChecks(fixture)[1].click();
    fixture.detectChanges();

    expect(fixture.componentInstance.chosen.map(row => row.id)).toEqual([1, 2]);
    expect(bar(fixture)!.getAttribute('role')).toBe('region');
    expect(bar(fixture)!.querySelector('[role="status"]')?.textContent).toContain('Выбрано: 2');
    expect(bar(fixture)!.querySelector('.host-action')).not.toBeNull();

    (bar(fixture)!.querySelector('[data-testid="bulk-clear"] button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.chosen).toEqual([]);
    expect(bar(fixture)).toBeNull();
  });

  it('drops the choice when another page arrives', async () => {
    const fixture = await renderSelect();
    rowChecks(fixture)[0].click();
    fixture.detectChanges();
    expect(fixture.componentInstance.chosen).toHaveLength(1);

    fixture.componentInstance.pager.next();
    fixture.detectChanges();

    expect(fixture.componentInstance.chosen).toEqual([]);
    expect(bar(fixture)).toBeNull();
  });
});
