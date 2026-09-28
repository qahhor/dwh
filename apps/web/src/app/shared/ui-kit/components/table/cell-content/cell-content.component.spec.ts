/* Not vendored: tests for the cell as the tables here use it (ADR-0015 rule 2). */
import { Component, input, signal, TemplateRef, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { testI18n } from '@shared/ui-kit/i18n/test-messages';
import type { ColumnContentType } from '../table.types';
import { SMTCellContentComponent } from './cell-content.component';

interface Row {
  id: number;
  name: string;
  due: string;
  note: string;
}

const ROW: Row = { id: 7, name: 'Сверка остатков', due: '2026-09-28T14:05:00', note: '' };

@Component({
  selector: 'app-test-owner',
  template: `<span class="owner">{{ login() }}</span>`,
})
class OwnerComponent {
  readonly login = input('');
}

@Component({
  imports: [SMTCellContentComponent],
  template: `
    <ng-template #nameCell let-row
      ><strong class="name">{{ row.name }}</strong></ng-template
    >
    <smt-cell-content
      [smtData]="data()"
      [smtRow]="row"
      [smtHasSelection]="selection()"
      [smtIsSelected]="selected()"
      (smtIsSelectedChange)="changes.push($event)"
      (click)="rowClicks = rowClicks + 1"
    />
  `,
})
class HostComponent {
  readonly row = ROW;
  readonly nameCell = viewChild.required<TemplateRef<unknown>>('nameCell');
  readonly data = signal<ColumnContentType<Row>>({ type: 'primitive', value: (row) => row.name });
  readonly selection = signal(false);
  readonly selected = signal(false);
  readonly changes: boolean[] = [];
  rowClicks = 0;
}

describe('SMTCellContentComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
  });

  function render(setup: (host: HostComponent) => void = () => undefined) {
    const fixture = TestBed.createComponent(HostComponent);
    setup(fixture.componentInstance);
    fixture.detectChanges();
    const cell = () => fixture.nativeElement.querySelector('smt-cell-content') as HTMLElement;
    return { fixture, cell };
  }

  it('shows the value the column reads from the row', () => {
    const { cell } = render();

    expect(cell().textContent?.trim()).toBe('Сверка остатков');
    expect(cell().querySelector('[role="checkbox"]')).toBeNull();
  });

  it('formats a date column, with the column’s own format when it has one', () => {
    const { fixture, cell } = render((host) => host.data.set({ type: 'date', value: (row) => row.due }));
    expect(cell().textContent?.trim()).toBe('28 Sep 2026');

    fixture.componentInstance.data.set({ type: 'date-time', value: (row) => row.due, format: 'dd.MM.yyyy HH:mm' });
    fixture.detectChanges();
    expect(cell().textContent?.trim()).toBe('28.09.2026 14:05');
  });

  it('draws a template or a component with the row it is given', () => {
    const { fixture, cell } = render((host) => host.data.set({ type: 'templateRef', value: host.nameCell }));
    expect(cell().querySelector('strong.name')?.textContent).toBe('Сверка остатков');

    fixture.componentInstance.data.set({
      type: 'component',
      value: { component: OwnerComponent, inputs: (row) => ({ login: `user-${row.id}` }) },
    });
    fixture.detectChanges();
    expect(cell().querySelector('.owner')?.textContent).toBe('user-7');
  });

  it('keeps markup of an html column but drops scripts from it', () => {
    const { cell } = render((host) =>
      host.data.set({ type: 'html', value: (row) => `<b>${row.name}</b><img src="x" onerror="alert(1)">` }),
    );

    expect(cell().querySelector('b')?.textContent).toBe('Сверка остатков');
    expect(cell().querySelector('img')?.hasAttribute('onerror')).toBe(false);
  });

  it('offers a named row checkbox that reports the choice without clicking the row', () => {
    const { fixture, cell } = render((host) => host.selection.set(true));
    const checkbox = cell().querySelector('[role="checkbox"]') as HTMLElement;

    expect(checkbox.getAttribute('aria-label')).toBe('Select row');
    expect(checkbox.getAttribute('aria-checked')).toBe('false');
    expect(cell().textContent).toContain('Сверка остатков');

    checkbox.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.changes).toEqual([true]);
    expect(fixture.componentInstance.rowClicks).toBe(0);
  });

  it('holds only the checkbox in a selection column whose value is empty', () => {
    const { cell } = render((host) => {
      host.selection.set(true);
      host.selected.set(true);
      host.data.set({ type: 'primitive', value: (row) => row.note });
    });

    expect(cell().querySelector('[role="checkbox"]')?.getAttribute('aria-checked')).toBe('true');
    expect(cell().querySelector('.text-md')).toBeNull();
  });
});
