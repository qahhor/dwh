// @vitest-environment jsdom
import '@angular/compiler';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { tickInZone } from '../../testing/zone-tick';
import { SMTDropdownButtonComponent, SMTMenuItem } from './dropdown-button.component';

type Action = 'edit' | 'copy' | 'archive' | 'delete';

@Component({
  standalone: true,
  imports: [SMTDropdownButtonComponent],
  template: `
    <smt-dropdown-button label="Actions" icon="bolt" [items]="items" (itemSelect)="chosen.push($event)" />
    <smt-dropdown-button smtIconOnly icon="more_vert" smtAriaLabel="More for row 4" [items]="items" />
  `,
})
class Host {
  readonly chosen: Action[] = [];
  readonly items: SMTMenuItem<Action>[] = [
    { id: 'edit', label: 'Edit', icon: 'edit' },
    { id: 'copy', label: 'Copy', disabled: true },
    { id: 'archive', label: 'Archive' },
    { id: 'delete', label: 'Delete', danger: true, separated: true },
  ];
}

describe('SMTDropdownButtonComponent', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
    TestBed.resetTestingModule();
  });

  async function render() {
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const [trigger, iconTrigger] = Array.from(fixture.nativeElement.querySelectorAll('.smt-dropdown-button__trigger')) as HTMLButtonElement[];
    const items = () => Array.from(document.querySelectorAll('[role="menuitem"]')) as HTMLElement[];
    const key = async (target: HTMLElement, name: string) => {
      // The CDK menu reads keyCode.
      const keyCode = { ArrowDown: 40, Escape: 27, Enter: 13 }[name];
      target.dispatchEvent(new KeyboardEvent('keydown', { key: name, keyCode, bubbles: true, cancelable: true }));
      await settle();
    };
    return { fixture, trigger, iconTrigger, items, key, settle };
  }

  it('is a menu button that opens a menu of its items', async () => {
    const { trigger, items, settle } = await render();
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(trigger.textContent).toContain('Actions');
    trigger.click();
    await settle();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(document.querySelector('[role="menu"]')).not.toBeNull();
    expect(items().map(item => item.textContent!.trim())).toEqual(['editEdit', 'Copy', 'Archive', 'Delete']);
    expect(items()[1].getAttribute('aria-disabled')).toBe('true');
    expect(items()[3].classList).toContain('smt-dropdown-button__item--danger');
    expect(document.querySelector('[role="separator"]')).not.toBeNull();
  });

  it('emits the chosen item and closes', async () => {
    const { fixture, trigger, items, settle } = await render();
    trigger.click();
    await settle();
    items()[2].click();
    await settle();
    expect(fixture.componentInstance.chosen).toEqual(['archive']);
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });

  it('opens from the keyboard onto the first item and closes on Escape', async () => {
    const { trigger, items, key } = await render();
    trigger.focus();
    await key(trigger, 'ArrowDown');
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(document.activeElement).toBe(items()[0]);
    await key(items()[0], 'Escape');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });

  it('names an icon-only button by its aria label', async () => {
    const { iconTrigger } = await render();
    expect(iconTrigger.getAttribute('aria-label')).toBe('More for row 4');
    expect(iconTrigger.querySelector('.smt-dropdown-button__label')).toBeNull();
  });
});
