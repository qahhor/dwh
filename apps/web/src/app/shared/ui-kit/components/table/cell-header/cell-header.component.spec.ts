/* Not vendored: tests for the header semantics added here (ADR-0015 rule 2). */
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { OrderBy } from '../table.types';
import { SMTCellHeaderComponent } from './cell-header.component';

@Component({
  standalone: true,
  imports: [SMTCellHeaderComponent],
  template: `<smt-cell-header
    [smtData]="{ type: 'primitive', value: 'Number' }"
    [smtHasSorting]="true"
    [smtHasSelection]="selection()"
    [(smtSort)]="sort"
  />`,
})
class HostComponent {
  readonly selection = signal(false);
  readonly sort = signal<OrderBy | undefined>(undefined);
}

async function render(selection: boolean) {
  const fixture = TestBed.createComponent(HostComponent);
  fixture.componentInstance.selection.set(selection);
  fixture.detectChanges();
  await fixture.whenStable();
  const host = fixture.nativeElement.querySelector('smt-cell-header') as HTMLElement;
  const press = (target: HTMLElement, key: string) => {
    target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    fixture.detectChanges();
  };
  return { fixture, host, press };
}

describe('SMTCellHeaderComponent', () => {
  it('is itself the sort button when it holds no checkbox', async () => {
    const { fixture, host, press } = await render(false);

    expect(host.getAttribute('role')).toBe('button');
    expect(host.getAttribute('tabindex')).toBe('0');
    press(host, 'Enter');
    expect(fixture.componentInstance.sort()).toBe(OrderBy.Asc);
  });

  it('with the select-all checkbox, makes the label the sort button so that no control nests another', async () => {
    const { fixture, host, press } = await render(true);
    const control = host.querySelector('[role="button"]') as HTMLElement;
    const checkbox = host.querySelector('input[type="checkbox"]') as HTMLElement;

    expect(host.getAttribute('role')).toBeNull();
    expect(host.getAttribute('tabindex')).toBeNull();
    expect(control.getAttribute('tabindex')).toBe('0');
    expect(control.contains(checkbox)).toBe(false);
    expect(control.textContent).toContain('Number');

    press(checkbox, ' ');
    expect(fixture.componentInstance.sort()).toBeUndefined();

    press(control, ' ');
    expect(fixture.componentInstance.sort()).toBe(OrderBy.Asc);
  });
});
