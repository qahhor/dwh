// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { FormField, form } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import type { SMTSelectOption } from '../select/select.component';
import { SMTMultiSelectComponent } from './multi-select.component';
import { SMTMultiSelectValueAccessor } from './multi-select-value-accessor';

const PEOPLE: SMTSelectOption<number>[] = [
  { id: 1, label: 'Aziz Karimov', subLabel: '@aziz' },
  { id: 2, label: 'Dilnoza Rahimova', subLabel: '@dilnoza' },
  { id: 3, label: 'Bekzod Tursunov', subLabel: '@bekzod' },
];

@Component({
  standalone: true,
  imports: [SMTMultiSelectComponent, FormField],
  template: `
    <smt-multi-select
      [formField]="task.observers"
      [options]="options()"
      [remoteSearch]="remote()"
      ariaLabel="Observers"
      (searchChange)="searches.push($event)" />
  `,
})
class Host {
  readonly model = signal({ observers: [2] as number[] });
  readonly task = form(this.model);
  readonly options = signal<SMTSelectOption<number>[]>(PEOPLE);
  readonly remote = signal(false);
  readonly searches: string[] = [];
}

@Component({
  standalone: true,
  imports: [SMTMultiSelectComponent, SMTMultiSelectValueAccessor, FormsModule],
  template: `<smt-multi-select [(ngModel)]="ids" name="ids" [options]="options" ariaLabel="Executors" />`,
})
class NgModelHost {
  ids: number[] = [1];
  readonly options = PEOPLE;
}

describe('SMTMultiSelectComponent', () => {
  afterEach(() => {
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
    const element = fixture.nativeElement as HTMLElement;
    const trigger = element.querySelector('.smt-multi-select__trigger') as HTMLButtonElement;
    const search = () => document.querySelector('.smt-select__search-input') as HTMLInputElement;
    const chips = () => Array.from(element.querySelectorAll('.smt-multi-select__chip-label')).map(c => c.textContent?.trim());
    const key = async (target: HTMLElement, name: string) => {
      target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
      await settle();
    };
    return { fixture, element, trigger, search, chips, key, settle };
  }

  it('shows the chosen values as chips with labelled remove buttons and a combobox trigger', async () => {
    const { element, trigger, chips } = await render(Host);

    expect(chips()).toEqual(['Dilnoza Rahimova']);
    expect(element.querySelector('.smt-multi-select__chip-remove')?.getAttribute('aria-label')).toBe('Remove Dilnoza Rahimova');
    expect(trigger.getAttribute('role')).toBe('combobox');
    expect(trigger.getAttribute('aria-label')).toBe('Observers');
    expect(trigger.textContent).toContain('+ Add');
  });

  it('opens a multi-selectable list, toggles with Enter and stays open', async () => {
    const { fixture, trigger, search, chips, key } = await render(Host);

    await key(trigger, 'Enter');
    const listbox = document.querySelector('[role="listbox"]')!;
    expect(listbox.getAttribute('aria-multiselectable')).toBe('true');
    expect(document.activeElement).toBe(search());

    await key(search(), 'Enter');
    expect(fixture.componentInstance.model().observers).toEqual([2, 1]);
    expect(document.querySelector('[role="listbox"]')).not.toBeNull();

    await key(search(), 'ArrowDown');
    await key(search(), 'Enter');
    expect(fixture.componentInstance.model().observers).toEqual([1]);
    expect(chips()).toEqual(['Aziz Karimov']);
    expect(document.getElementById(search().getAttribute('aria-activedescendant')!)!.getAttribute('aria-selected')).toBe('false');
  });

  it('removes the last chip with Backspace in an empty search and closes on Escape', async () => {
    const { fixture, trigger, search, key } = await render(Host);
    await key(trigger, 'Enter');

    await key(search(), 'Backspace');
    expect(fixture.componentInstance.model().observers).toEqual([]);

    await key(search(), 'Escape');
    expect(document.querySelector('[role="listbox"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('removes a chip from its button and returns focus to the trigger', async () => {
    const { fixture, element, trigger, settle } = await render(Host);

    (element.querySelector('.smt-multi-select__chip-remove') as HTMLButtonElement).click();
    await settle();

    expect(fixture.componentInstance.model().observers).toEqual([]);
    expect(document.activeElement).toBe(trigger);
    expect(trigger.textContent).toContain('Choose');
  });

  it('keeps chip labels when a remote search no longer lists the chosen options', async () => {
    const { fixture, trigger, search, chips, key, settle } = await render(Host);
    fixture.componentInstance.remote.set(true);
    await settle();

    await key(trigger, 'Enter');
    search().value = 'zz';
    search().dispatchEvent(new Event('input'));
    await settle();
    fixture.componentInstance.options.set([PEOPLE[2]]);
    await settle();

    expect(fixture.componentInstance.searches).toEqual(['', 'zz']);
    expect(chips()).toEqual(['Dilnoza Rahimova']);
  });

  it('works with ngModel through the value accessor', async () => {
    const { fixture, trigger, search, chips, key } = await render(NgModelHost);

    expect(chips()).toEqual(['Aziz Karimov']);
    await key(trigger, 'Enter');
    await key(search(), 'ArrowDown');
    await key(search(), 'Enter');

    expect(fixture.componentInstance.ids).toEqual([1, 2]);
  });
});
