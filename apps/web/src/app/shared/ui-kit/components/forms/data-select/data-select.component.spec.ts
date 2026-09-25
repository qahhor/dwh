// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { FormField, form, required } from '@angular/forms/signals';
import { Observable, Subject, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTControlComponent } from '../control/control.component';
import { SMTDataSelectComponent } from './data-select.component';
import { SMTDataSelectValueAccessor } from './data-select-value-accessor';
import { SMTMultiDataSelectComponent } from './multi-data-select.component';
import { SMTMultiDataSelectValueAccessor } from './data-select-value-accessor';
import type { SMTLookupSource } from './lookup-source';

interface Person { id: number; name: string; login: string }

const PEOPLE: Person[] = [
  { id: 1, name: 'Aziz Karimov', login: 'aziz' },
  { id: 2, name: 'Dilnoza Rahimova', login: 'dilnoza' },
  { id: 3, name: 'Bekzod Tursunov', login: 'bekzod' },
];
const HIDDEN: Person = { id: 7, name: 'Kamola Yusupova', login: 'kamola' };

/** A lookup over PEOPLE: pages of two, search by name, HIDDEN only by id. */
class FakeSource implements SMTLookupSource<Person> {
  readonly pages: { search: string; cursor: string | null }[] = [];
  readonly resolved: number[][] = [];
  fail = false;
  pending: Subject<{ items: Person[]; nextCursor: string | null; hasMore: boolean }> | null = null;

  page(search: string, cursor: string | null, limit: number) {
    this.pages.push({ search, cursor });
    if (this.fail) return throwError(() => new Error('down'));
    if (this.pending) return this.pending;
    const found = PEOPLE.filter(person => person.name.toLowerCase().includes(search.toLowerCase()));
    const start = cursor ? Number(cursor) : 0;
    const items = found.slice(start, start + Math.min(limit, 2));
    const next = start + items.length < found.length ? String(start + items.length) : null;
    return of({ items, nextCursor: next, hasMore: next !== null });
  }

  key(person: Person): number {
    return person.id;
  }

  option(person: Person) {
    return { label: person.name, subLabel: `@${person.login}` };
  }

  resolve(keys: readonly number[]): Observable<readonly Person[]> {
    this.resolved.push([...keys]);
    return of([...PEOPLE, HIDDEN].filter(person => keys.includes(person.id)));
  }
}

@Component({
  standalone: true,
  imports: [SMTDataSelectComponent, SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Manager">
      <smt-data-select [formField]="user.managerId" [source]="source" [exclude]="notSelf" (rowChange)="rows.push($event)" />
    </smt-control>
  `,
})
class FormHost {
  readonly source = new FakeSource();
  readonly model = signal({ managerId: 7 as number | null });
  readonly user = form(this.model, path => required(path.managerId));
  readonly notSelf = (person: Person) => person.id === 3;
  readonly rows: (Person | null)[] = [];
}

@Component({
  standalone: true,
  imports: [SMTDataSelectComponent, SMTDataSelectValueAccessor, FormsModule],
  template: `<smt-data-select [(ngModel)]="managerId" name="manager" [source]="source" ariaLabel="Manager" />`,
})
class NgModelHost {
  readonly source = new FakeSource();
  managerId: number | null = 9;
}

@Component({
  standalone: true,
  imports: [SMTMultiDataSelectComponent, SMTMultiDataSelectValueAccessor, FormsModule],
  template: `<smt-multi-data-select [(ngModel)]="observers" name="observers" [source]="source" ariaLabel="Observers" (rowsChange)="rows = $event" />`,
})
class MultiHost {
  readonly source = new FakeSource();
  observers: number[] = [7];
  rows: Person[] = [];
}

describe('SMTDataSelectComponent', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }));

  afterEach(() => {
    vi.useRealTimers();
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  async function render<T>(host: new () => T) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const trigger = () => element.querySelector('[role="combobox"]') as HTMLElement;
    const search = () => document.querySelector('.smt-select__search-input, .smt-multi-select__search-input') as HTMLInputElement;
    const options = () => Array.from(document.querySelectorAll('[role="option"]')) as HTMLElement[];
    const labels = () => options().map(option => option.textContent!.replace(/\s+/g, ' ').trim());
    const open = async () => {
      trigger().click();
      await settle();
    };
    const type = async (text: string) => {
      search().value = text;
      search().dispatchEvent(new Event('input'));
      await settle();
    };
    return { fixture, element, trigger, search, options, labels, open, type, settle };
  }

  it('names a record chosen before any page by asking the source for it, once', async () => {
    const { fixture, trigger, settle } = await render(FormHost);
    expect(fixture.componentInstance.source.resolved).toEqual([[7]]);
    expect(trigger().textContent).toContain('Kamola Yusupova');
    await settle();
    expect(fixture.componentInstance.source.resolved).toHaveLength(1);
    expect(fixture.componentInstance.source.pages).toEqual([]);
  });

  it('loads the first page when opened, keeps the chosen record on top and leaves out the excluded rows', async () => {
    const { fixture, labels, open } = await render(FormHost);
    await open();
    expect(fixture.componentInstance.source.pages).toEqual([{ search: '', cursor: null }]);
    expect(labels()[0]).toContain('None');
    expect(labels().slice(1)).toEqual([
      expect.stringContaining('Kamola Yusupova'),
      expect.stringContaining('Aziz Karimov'),
      expect.stringContaining('Dilnoza Rahimova'),
    ]);
  });

  it('searches after a pause, loads more on request and writes the pick into the form with its row', async () => {
    const { fixture, labels, open, type, settle, options } = await render(FormHost);
    await open();
    await type('o');
    expect(fixture.componentInstance.source.pages).toHaveLength(1);
    vi.advanceTimersByTime(300);
    await settle();
    expect(fixture.componentInstance.source.pages[1]).toEqual({ search: 'o', cursor: null });

    const more = document.querySelector('.smt-select__more') as HTMLButtonElement;
    more.click();
    await settle();
    expect(fixture.componentInstance.source.pages[2]).toEqual({ search: 'o', cursor: '2' });
    expect(labels().some(label => label.includes('Bekzod'))).toBe(false);

    options().find(option => option.textContent!.includes('Aziz'))!.click();
    await settle();
    expect(fixture.componentInstance.model().managerId).toBe(1);
    expect(fixture.componentInstance.rows).toEqual([PEOPLE[0]]);
  });

  it('shows a failed page with a retry that asks again', async () => {
    const { fixture, open, settle } = await render(FormHost);
    fixture.componentInstance.source.fail = true;
    await open();
    const retry = document.querySelector('.smt-select__error button') as HTMLButtonElement;
    expect(retry).not.toBeNull();
    fixture.componentInstance.source.fail = false;
    retry.click();
    await settle();
    expect(fixture.componentInstance.source.pages).toHaveLength(2);
    expect(document.querySelector('.smt-select__error')).toBeNull();
  });

  it('shows a key the source cannot name by its id, and works through ngModel', async () => {
    const { fixture, trigger, open, options, settle } = await render(NgModelHost);
    expect(trigger().textContent).toContain('ID: #9');
    await open();
    options().find(option => option.textContent!.includes('Dilnoza'))!.click();
    await settle();
    expect(fixture.componentInstance.managerId).toBe(2);
  });

  it('keeps the chips of a multi select named and reports the chosen rows', async () => {
    const { fixture, element, open, options, settle } = await render(MultiHost);
    expect(element.textContent).toContain('Kamola Yusupova');
    await open();
    options().find(option => option.textContent!.includes('Aziz'))!.click();
    await settle();
    expect(fixture.componentInstance.observers).toEqual([7, 1]);
    expect(fixture.componentInstance.rows.map(person => person.id)).toEqual([7, 1]);
  });
});
