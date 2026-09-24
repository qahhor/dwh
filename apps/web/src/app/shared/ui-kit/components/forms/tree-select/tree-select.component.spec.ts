// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../../i18n';
import { testI18n } from '../../../i18n/test-messages';
import { tickInZone } from '../../../testing/zone-tick';
import { SMTTreeOption, SMTTreeSelectComponent } from './tree-select.component';
import { SMTTreeSelectValueAccessor } from './tree-select-value-accessor';

const TREE: SMTTreeOption<number>[] = [
  {
    id: 1,
    label: 'Head office',
    children: [
      { id: 2, label: 'Tashkent branch', children: [{ id: 4, label: 'Sales department' }] },
      { id: 3, label: 'Samarkand branch' },
    ],
  },
  { id: 5, label: 'Warehouse' },
];

@Component({
  standalone: true,
  imports: [SMTTreeSelectComponent],
  template: `<smt-tree-select [(value)]="unit" [nodes]="nodes" ariaLabel="Parent" />`,
})
class Host {
  readonly unit = signal<number | null>(4);
  readonly nodes = TREE;
}

@Component({
  standalone: true,
  imports: [SMTTreeSelectComponent, SMTTreeSelectValueAccessor, FormsModule],
  template: `<smt-tree-select [(ngModel)]="unit" name="unit" [nodes]="nodes" ariaLabel="Parent" />`,
})
class NgModelHost {
  unit: number | null = null;
  readonly nodes = TREE;
}

@Component({
  standalone: true,
  imports: [SMTTreeSelectComponent, SMTTreeSelectValueAccessor, FormsModule],
  // Inside a form, NgForm registers the control in a microtask, as on the org unit editor.
  template: `<form>@if (shown()) { <smt-tree-select [(ngModel)]="unit" name="unit" [nodes]="nodes" /> }</form>`,
})
class FleetingHost {
  readonly shown = signal(true);
  unit: number | null = 1;
  readonly nodes = TREE;
}

describe('SMTTreeSelectComponent', () => {
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
    const items = () => Array.from(document.querySelectorAll('[role="treeitem"]')) as HTMLElement[];
    const labels = () => items().map(item => item.querySelector('.smt-select__option-label')?.textContent?.trim());
    const active = () => document.getElementById(search().getAttribute('aria-activedescendant')!)!;
    const key = async (target: HTMLElement, name: string) => {
      target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true, cancelable: true }));
      await settle();
    };
    return { fixture, element, trigger, search, items, labels, active, key, settle };
  }

  it('is a combobox with a tree popup that shows the chosen node', async () => {
    const { trigger } = await render(Host);

    expect(trigger.getAttribute('role')).toBe('combobox');
    expect(trigger.getAttribute('aria-haspopup')).toBe('tree');
    expect(trigger.textContent).toContain('Sales department');
  });

  it('opens with the path to the chosen node expanded and names each node’s place in the tree', async () => {
    const { trigger, labels, active, items, key } = await render(Host);

    await key(trigger, 'Enter');

    expect(document.querySelector('[role="tree"]')).not.toBeNull();
    expect(labels()).toEqual(['Head office', 'Tashkent branch', 'Sales department', 'Samarkand branch', 'Warehouse']);
    expect(active().textContent).toContain('Sales department');
    const sales = items()[2];
    expect(sales.getAttribute('aria-level')).toBe('3');
    expect(sales.getAttribute('aria-setsize')).toBe('1');
    expect(sales.getAttribute('aria-selected')).toBe('true');
    const head = items()[0];
    expect(head.getAttribute('aria-expanded')).toBe('true');
    expect(items()[4].hasAttribute('aria-expanded')).toBe(false);
    expect(items()[3].getAttribute('aria-posinset')).toBe('2');
  });

  it('steps to the parent with Left, closes and reopens a node with Left and Right', async () => {
    const { trigger, search, labels, active, key } = await render(Host);
    await key(trigger, 'Enter');

    await key(search(), 'ArrowLeft');
    expect(active().textContent).toContain('Tashkent branch');

    await key(search(), 'ArrowLeft');
    expect(labels()).not.toContain('Sales department');
    expect(active().getAttribute('aria-expanded')).toBe('false');

    await key(search(), 'ArrowRight');
    expect(labels()).toContain('Sales department');
    await key(search(), 'ArrowRight');
    expect(active().textContent).toContain('Sales department');
  });

  it('picks the highlighted node with Enter and returns focus to the trigger', async () => {
    const { fixture, trigger, search, key } = await render(Host);
    await key(trigger, 'Enter');

    await key(search(), 'End');
    await key(search(), 'Enter');

    expect(fixture.componentInstance.unit()).toBe(5);
    expect(document.querySelector('[role="tree"]')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('keeps every search match inside its ancestors', async () => {
    const { trigger, search, labels, active, key, settle } = await render(Host);
    await key(trigger, 'Enter');

    search().value = 'samar';
    search().dispatchEvent(new Event('input'));
    await settle();

    expect(labels()).toEqual(['Head office', 'Samarkand branch']);
    expect(active().textContent).toContain('Samarkand branch');
  });

  it('closes on Escape without changing the value', async () => {
    const { fixture, trigger, search, key } = await render(Host);
    await key(trigger, 'Enter');
    await key(search(), 'Home');

    await key(search(), 'Escape');

    expect(fixture.componentInstance.unit()).toBe(4);
    expect(document.activeElement).toBe(trigger);
  });

  it('ignores NgModel registering after the field was already destroyed', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(FleetingHost);
    fixture.detectChanges();
    // NgModel registers its callbacks in a microtask; the field goes away first.
    fixture.componentInstance.shown.set(false);
    fixture.detectChanges();

    await expect(fixture.whenStable()).resolves.not.toThrow();
  });

  it('works with ngModel through the value accessor', async () => {
    const { fixture, trigger, search, key } = await render(NgModelHost);

    await key(trigger, 'Enter');
    await key(search(), 'ArrowDown');
    await key(search(), 'Enter');

    expect(fixture.componentInstance.unit).toBe(5);
  });
});
