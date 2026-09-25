// @vitest-environment jsdom
import '@angular/compiler';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { tickInZone } from '../../testing/zone-tick';
import { SMTSortableActionsDirective, SMTSortableItemDirective, SMTSortableListComponent } from './sortable-list.component';

interface Status { id: number; name: string; system?: boolean }

@Component({
  standalone: true,
  imports: [SMTSortableListComponent, SMTSortableItemDirective, SMTSortableActionsDirective],
  template: `
    <smt-sortable-list
      smtAriaLabel="Statuses"
      [items]="items()"
      [trackBy]="byId"
      [itemLabel]="nameOf"
      [locked]="isSystem"
      (reorder)="items.set($event); orders.push($event)">
      <ng-template smtSortableItem let-status let-index="index"><span class="name">{{ index + 1 }}. {{ status.name }}</span></ng-template>
      <ng-template smtSortableActions let-status><button type="button" class="delete">Delete {{ status.name }}</button></ng-template>
    </smt-sortable-list>
  `,
})
class Host {
  readonly items = signal<Status[]>([
    { id: 1, name: 'New' },
    { id: 2, name: 'In progress' },
    { id: 3, name: 'Review' },
    { id: 9, name: 'Done', system: true },
  ]);
  readonly orders: Status[][] = [];
  readonly byId = (status: Status) => status.id;
  readonly nameOf = (status: Status) => status.name;
  readonly isSystem = (status: Status) => !!status.system;
}

describe('SMTSortableListComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  async function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    document.body.appendChild(fixture.nativeElement);
    const settle = async () => {
      tickInZone();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    await settle();
    const element = fixture.nativeElement as HTMLElement;
    const names = () => Array.from(element.querySelectorAll('.name')).map(node => node.textContent!.trim());
    const button = (label: string) => element.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
    return { fixture, element, names, button, settle };
  }

  it('is a named list whose rows carry the caller\'s body and actions', async () => {
    const { element, names } = await render();
    const list = element.querySelector('[role="list"]')!;
    expect(list.getAttribute('aria-label')).toBe('Statuses');
    expect(element.querySelectorAll('[role="listitem"]')).toHaveLength(4);
    expect(names()).toEqual(['1. New', '2. In progress', '3. Review', '4. Done']);
    expect(element.querySelectorAll('.delete')).toHaveLength(4);
    expect(element.querySelector('.smt-sortable-list__handle')!.getAttribute('aria-hidden')).toBe('true');
  });

  it('moves a row with its buttons, announces the new place and keeps focus on the moved row', async () => {
    const { fixture, element, names, button, settle } = await render();
    button('Move New down')!.focus();
    button('Move New down')!.click();
    await settle();
    expect(names()).toEqual(['1. In progress', '2. New', '3. Review', '4. Done']);
    expect(element.querySelector('[role="status"]')!.textContent).toBe('New: place 2 of 4');
    expect(document.activeElement).toBe(button('Move New down'));
    expect(fixture.componentInstance.orders).toHaveLength(1);

    button('Move New up')!.click();
    await settle();
    expect(names()[0]).toBe('1. New');
    // At the top the "up" button is disabled, so focus goes to "down".
    expect(button('Move New up')!.disabled).toBe(true);
    expect(document.activeElement).toBe(button('Move New down'));
  });

  it('keeps a locked row in place: no buttons for it, and nothing moves past it', async () => {
    const { element, button } = await render();
    expect(button('Move Done up')).toBeNull();
    expect(button('Move Review down')!.disabled).toBe(true);
    expect(element.querySelectorAll('[role="listitem"]')[3].classList).toContain('smt-sortable-list__row--locked');
  });

  it('reorders on a drop, and ignores a drop in place', async () => {
    const { fixture, names, settle } = await render();
    const list = fixture.debugElement.children[0].componentInstance as SMTSortableListComponent<Status>;
    list.onDrop({ previousIndex: 2, currentIndex: 0 } as never);
    await settle();
    expect(names()).toEqual(['1. Review', '2. New', '3. In progress', '4. Done']);
    list.onDrop({ previousIndex: 1, currentIndex: 1 } as never);
    expect(fixture.componentInstance.orders).toHaveLength(1);
    expect(list.canDropAt(3)).toBe(false);
    expect(list.canDropAt(0)).toBe(true);
  });
});
