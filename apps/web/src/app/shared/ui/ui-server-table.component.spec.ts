import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { KeysetPager, KeysetResponse } from '../paging/keyset-pager';
import { TableConfig } from '../ui-kit/components/table/table.types';
import { UiServerTableComponent } from './ui-server-table.component';

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
    const select = el(fixture).querySelector('select') as HTMLSelectElement;
    expect([...select.options].map(option => option.textContent?.trim())).toEqual(['2', '10', '25', '50', '100']);
    expect(select.selectedOptions[0]?.textContent?.trim()).toBe('2');
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
