// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { tickInZone } from '../../../testing/zone-tick';
import { FormsModule } from '@angular/forms';
import { FormField, form, required } from '@angular/forms/signals';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { SMTControlComponent } from '../control/control.component';
import { SMTSelectComponent, SMTSelectOption } from './select.component';
import { SMTSelectValueAccessor } from './select-value-accessor';

const PEOPLE: SMTSelectOption<number>[] = [
  { id: 1, label: 'Aziz Karimov', subLabel: '@aziz' },
  { id: 2, label: 'Dilnoza Rahimova', subLabel: '@dilnoza' },
  { id: 3, label: 'Bekzod Tursunov', subLabel: '@bekzod' },
];

@Component({
  standalone: true,
  imports: [SMTSelectComponent, SMTControlComponent, FormField],
  template: `
    <smt-control smtLabel="Responsible">
      <smt-select
        [formField]="task.responsible"
        [options]="options()"
        [remoteSearch]="remote()"
        [loading]="loading()"
        [loadError]="failed()"
        [hasMore]="more()"
        emptyLabel="Not assigned"
        (searchChange)="searches.push($event)"
        (loadMore)="loadMoreCalls = loadMoreCalls + 1"
        (retry)="retryCalls = retryCalls + 1" />
    </smt-control>
  `,
})
class Host {
  readonly model = signal({ responsible: 2 as number | null });
  readonly task = form(this.model, path => required(path.responsible));
  readonly options = signal<SMTSelectOption<number>[]>(PEOPLE);
  readonly remote = signal(false);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly more = signal(false);
  readonly searches: string[] = [];
  loadMoreCalls = 0;
  retryCalls = 0;
}

@Component({
  standalone: true,
  imports: [SMTSelectComponent, SMTSelectValueAccessor, FormsModule],
  template: `<smt-select [(ngModel)]="owner" name="owner" [options]="options" ariaLabel="Owner" />`,
})
class NgModelHost {
  owner: number | null = 1;
  readonly options = PEOPLE;
}

describe('SMTSelectComponent', () => {
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
    const trigger = element.querySelector('.smt-select__trigger') as HTMLButtonElement;
    const search = () => document.querySelector('.smt-select__search-input') as HTMLInputElement;
    const options = () => Array.from(document.querySelectorAll('[role="option"]')) as HTMLElement[];
    const key = async (target: HTMLElement, name: string) => {
      target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
      await settle();
    };
    const type = async (text: string) => {
      search().value = text;
      search().dispatchEvent(new Event('input'));
      await settle();
    };
    return { fixture, element, trigger, search, options, key, type, settle };
  }

  it('is a combobox named by the surrounding label whose text is the chosen option', async () => {
    const { element, trigger } = await render(Host);

    expect(trigger.getAttribute('role')).toBe('combobox');
    expect(trigger.getAttribute('aria-haspopup')).toBe('listbox');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(element.querySelector('label')!.htmlFor).toBe(trigger.id);
    expect(trigger.textContent).toContain('Dilnoza Rahimova');
    expect(trigger.getAttribute('aria-required')).toBe('true');
  });

  it('opens from the keyboard, focuses the search and highlights the chosen option', async () => {
    const { trigger, search, options, key } = await render(Host);

    await key(trigger, 'ArrowDown');

    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement).toBe(search());
    const listbox = document.getElementById(trigger.getAttribute('aria-controls')!)!;
    expect(listbox.getAttribute('role')).toBe('listbox');
    const active = document.getElementById(search().getAttribute('aria-activedescendant')!)!;
    expect(active.textContent).toContain('Dilnoza Rahimova');
    expect(active.getAttribute('aria-selected')).toBe('true');
    expect(options().some(option => option.hasAttribute('tabindex'))).toBe(false);
  });

  it('moves the highlight with the arrows, picks with Enter and returns focus to the trigger', async () => {
    const { fixture, trigger, search, key } = await render(Host);
    await key(trigger, 'Enter');

    await key(search(), 'ArrowDown');
    expect(document.getElementById(search().getAttribute('aria-activedescendant')!)!.textContent).toContain('Bekzod');
    await key(search(), 'Enter');

    expect(fixture.componentInstance.model().responsible).toBe(3);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(trigger);
  });

  it('starts a search when a letter is typed on the closed trigger and filters locally', async () => {
    const { trigger, search, options, key, type } = await render(Host);

    await key(trigger, 'b');
    expect(search().value).toBe('b');

    await type('bek');
    expect(options().map(option => option.textContent?.trim())).toEqual([expect.stringContaining('Bekzod')]);
    expect(document.getElementById(search().getAttribute('aria-activedescendant')!)!.textContent).toContain('Bekzod');
  });

  it('closes on Escape without changing the value', async () => {
    const { fixture, trigger, search, key } = await render(Host);
    await key(trigger, 'Enter');
    await key(search(), 'ArrowDown');

    await key(search(), 'Escape');

    expect(fixture.componentInstance.model().responsible).toBe(2);
    expect(document.querySelector('[role="listbox"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('clears the choice from the "none" row and from the clear button', async () => {
    const { fixture, element, trigger, search, key, settle } = await render(Host);

    await key(trigger, 'Enter');
    await key(search(), 'Home');
    for (let step = 0; step < 3; step++) await key(search(), 'ArrowUp');
    expect(document.getElementById(search().getAttribute('aria-activedescendant')!)!.textContent).toContain('Not assigned');
    await key(search(), 'Enter');
    expect(fixture.componentInstance.model().responsible).toBeNull();

    fixture.componentInstance.model.set({ responsible: 1 });
    await settle();
    (element.querySelector('[aria-label="Clear the choice"]') as HTMLButtonElement).click();
    await settle();
    expect(fixture.componentInstance.model().responsible).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('hands the search to the parent in remote mode and shows its loading, error and more states', async () => {
    const { fixture, trigger, key, type, settle } = await render(Host);
    fixture.componentInstance.remote.set(true);
    await settle();

    await key(trigger, 'Enter');
    await type('zz');
    expect(fixture.componentInstance.searches).toEqual(['', 'zz']);
    expect(document.querySelectorAll('[role="option"]').length).toBeGreaterThan(1);

    fixture.componentInstance.loading.set(true);
    await settle();
    expect(document.querySelector('.smt-select__note[role="status"]')?.textContent).toContain('Loading');

    fixture.componentInstance.loading.set(false);
    fixture.componentInstance.failed.set(true);
    await settle();
    const alert = document.querySelector('.smt-select__error[role="alert"]')!;
    expect(alert.textContent).toContain('Could not load the options');
    (alert.querySelector('button') as HTMLButtonElement).click();
    expect(fixture.componentInstance.retryCalls).toBe(1);

    fixture.componentInstance.failed.set(false);
    fixture.componentInstance.more.set(true);
    await settle();
    (document.querySelector('.smt-select__more') as HTMLButtonElement).click();
    expect(fixture.componentInstance.loadMoreCalls).toBe(1);
  });

  it('keeps the chosen label when a remote search no longer lists it', async () => {
    const { fixture, trigger, search, key, settle } = await render(Host);
    fixture.componentInstance.remote.set(true);
    await settle();
    await key(trigger, 'Enter');
    await key(search(), 'ArrowDown');
    await key(search(), 'Enter');
    expect(fixture.componentInstance.model().responsible).toBe(3);

    fixture.componentInstance.options.set([PEOPLE[0]]);
    await settle();

    expect(trigger.textContent).toContain('Bekzod Tursunov');
  });

  it('lets an aria-modal dialog own the open popup, and drops that when it closes', async () => {
    const { element, trigger, search, key } = await render(Host);
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    dialog.setAttribute('aria-owns', 'other-panel');
    element.parentElement!.insertBefore(dialog, element);
    dialog.appendChild(element);

    await key(trigger, 'Enter');
    const popupId = document.querySelector('.smt-select__popup')!.id;
    expect(dialog.getAttribute('aria-owns')).toBe(`other-panel ${popupId}`);

    await key(search(), 'Escape');
    expect(dialog.getAttribute('aria-owns')).toBe('other-panel');
    dialog.remove();
  });

  it('closes when the trigger is clicked again, instead of reopening', async () => {
    const { trigger, settle } = await render(Host);

    const press = async () => {
      trigger.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }));
      trigger.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
      trigger.click();
      await settle();
    };
    await press();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    await press();

    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.querySelector('[role="listbox"]')).toBeNull();
  });

  it('works with ngModel through the value accessor', async () => {
    const { fixture, trigger, search, key } = await render(NgModelHost);

    expect(trigger.getAttribute('aria-label')).toBe('Owner');
    expect(trigger.textContent).toContain('Aziz Karimov');
    await key(trigger, 'Enter');
    await key(search(), 'ArrowDown');
    await key(search(), 'Enter');

    expect(fixture.componentInstance.owner).toBe(2);
  });
});
