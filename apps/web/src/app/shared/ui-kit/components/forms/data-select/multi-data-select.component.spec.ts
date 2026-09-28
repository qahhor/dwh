import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { FormField, disabled, form } from '@angular/forms/signals';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { testI18n } from '@shared/ui-kit/i18n/test-messages';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { SMTMultiDataSelectValueAccessor } from './data-select-value-accessor';
import type { SMTLookupSource } from './lookup-source';
import { SMTMultiDataSelectComponent } from './multi-data-select.component';

interface Person {
  id: number;
  name: string;
}

const PEOPLE: Person[] = [
  { id: 1, name: 'Aziz Karimov' },
  { id: 2, name: 'Dilnoza Rahimova' },
  { id: 3, name: 'Bekzod Tursunov' },
];

/** One page of PEOPLE; `resolve` names only the listed people. */
class FakeSource implements SMTLookupSource<Person> {
  readonly pages: string[] = [];
  failPage = false;

  page(search: string) {
    this.pages.push(search);
    if (this.failPage) return throwError(() => new Error('down'));
    return of({ items: PEOPLE, nextCursor: null, hasMore: false });
  }

  key(person: Person): number {
    return person.id;
  }

  option(person: Person) {
    return { label: person.name };
  }

  resolve(keys: readonly number[]): Observable<readonly Person[]> {
    return of(PEOPLE.filter((person) => keys.includes(person.id)));
  }
}

@Component({
  imports: [SMTMultiDataSelectComponent, FormField],
  template: `<smt-multi-data-select
    [formField]="task.observers"
    [source]="source"
    [exclude]="notMe"
    ariaLabel="Observers"
    (rowsChange)="rows = $event"
  />`,
})
class SignalFormHost {
  readonly source = new FakeSource();
  readonly locked = signal(false);
  readonly model = signal({ observers: [2] as readonly number[] });
  readonly task = form(this.model, (path) => disabled(path.observers, () => this.locked()));
  readonly notMe = (person: Person) => person.id === 3;
  rows: Person[] | null = null;
}

@Component({
  imports: [SMTMultiDataSelectComponent, SMTMultiDataSelectValueAccessor, FormsModule],
  template: `<smt-multi-data-select
    [(ngModel)]="observers"
    [disabled]="locked"
    name="observers"
    [source]="source"
    ariaLabel="Observers"
  />`,
})
class NgModelHost {
  readonly source = new FakeSource();
  observers: number[] = [9];
  locked = false;
}

describe('SMTMultiDataSelectComponent', () => {
  beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] }));

  afterEach(() => {
    vi.useRealTimers();
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());
    TestBed.resetTestingModule();
  });

  async function render<T>(host: new () => T, setup: (instance: T) => void = () => undefined) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(host);
    setup(fixture.componentInstance);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const trigger = () => element.querySelector('.smt-multi-select__trigger') as HTMLButtonElement;
    const chips = () =>
      Array.from(element.querySelectorAll('.smt-multi-select__chip-label')).map((chip) => chip.textContent?.trim());
    const options = () => Array.from(document.querySelectorAll('[role="option"]')) as HTMLElement[];
    const open = async () => {
      trigger().click();
      await settle();
    };
    return { fixture, element, trigger, chips, options, open, settle };
  }

  it('shows the form’s chosen records as named chips and writes a pick back with the chosen rows', async () => {
    const { fixture, chips, options, open, settle } = await render(SignalFormHost);
    expect(chips()).toEqual(['Dilnoza Rahimova']);

    await open();
    options()
      .find((option) => option.textContent?.includes('Aziz'))!
      .click();
    await settle();

    expect(fixture.componentInstance.model().observers).toEqual([2, 1]);
    expect(fixture.componentInstance.rows?.map((person) => person.id)).toEqual([2, 1]);
    expect(chips()).toEqual(['Dilnoza Rahimova', 'Aziz Karimov']);
  });

  it('removes a chip from the value and reports the rows that remain', async () => {
    const { fixture, element, chips, settle } = await render(SignalFormHost, (host) =>
      host.model.set({ observers: [1, 2] }),
    );

    (element.querySelector('[aria-label="Remove Aziz Karimov"]') as HTMLButtonElement).click();
    await settle();

    expect(fixture.componentInstance.model().observers).toEqual([2]);
    expect(fixture.componentInstance.rows).toEqual([PEOPLE[1]]);
    expect(chips()).toEqual(['Dilnoza Rahimova']);
  });

  it('leaves the excluded rows out of the list', async () => {
    const { fixture, options, open } = await render(SignalFormHost);
    await open();

    expect(fixture.componentInstance.source.pages).toEqual(['']);
    expect(options().map((option) => option.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      expect.stringContaining('Aziz Karimov'),
      expect.stringContaining('Dilnoza Rahimova'),
    ]);
  });

  it('cannot be changed while a signal form or ngModel disables it', async () => {
    const signalForm = await render(SignalFormHost, (host) => host.locked.set(true));
    expect(signalForm.trigger().disabled).toBe(true);
    expect(signalForm.element.querySelector('.smt-multi-select__chip-remove')).toBeNull();
    TestBed.resetTestingModule();

    const ngModel = await render(NgModelHost, (host) => (host.locked = true));
    expect(ngModel.trigger().disabled).toBe(true);
  });

  it('shows a chosen key the source cannot name by its id, and a failed page with a retry', async () => {
    const { fixture, chips, open, settle } = await render(NgModelHost, (host) => (host.source.failPage = true));
    expect(chips()).toEqual(['ID: #9']);

    await open();
    const retry = document.querySelector('.smt-select__error button') as HTMLButtonElement;
    expect(retry).not.toBeNull();
    fixture.componentInstance.source.failPage = false;
    retry.click();
    await settle();

    expect(fixture.componentInstance.source.pages).toHaveLength(2);
    expect(document.querySelector('.smt-select__error')).toBeNull();
  });
});
