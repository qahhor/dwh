import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { QueryCondition, QueryListMeta } from '../../core/models/query-meta.models';
import { PACKAGED_RUSSIAN } from '../../core/i18n/packaged-russian';
import { SMTDrawerService } from '../ui-kit/components/drawer';
import { UiFilterBarComponent } from './ui-filter-bar.component';
import { UiFilterPanelComponent } from './ui-filter-panel.component';

const META: QueryListMeta = {
  code: 'upl.sources',
  defaultSort: 'code',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    { key: 'code', labelKey: 'upl.list.col.code', type: 'text', ops: ['starts_with'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'periodicity', labelKey: 'upl.list.col.periodicity', type: 'enum', ops: ['in'], sortable: false, nullable: false, defaultVisible: true, enumValues: ['month', 'year'], enumLabelPrefix: 'upl.periodicity.' }
  ]
};

const ACTIVE: QueryCondition[] = [
  { field: 'code', op: 'starts_with', value: 'cement.' },
  { field: 'periodicity', op: 'in', value: ['month', 'year'] }
];

async function render(conditions: QueryCondition[], result: QueryCondition[] | undefined = undefined) {
  const open = vi.fn(() => ({ close: vi.fn(), afterClosed: () => of(result), componentInstance: null }));
  await TestBed.configureTestingModule({
    imports: [UiFilterBarComponent],
    providers: [{ provide: SMTDrawerService, useValue: { open } }]
  }).compileComponents();
  const fixture = TestBed.createComponent(UiFilterBarComponent);
  fixture.componentRef.setInput('meta', META);
  fixture.componentRef.setInput('conditions', conditions);
  const changes: QueryCondition[][] = [];
  fixture.componentInstance.conditionsChange.subscribe(next => changes.push(next));
  fixture.detectChanges();
  return { fixture, open, changes };
}

const el = (fixture: ComponentFixture<UiFilterBarComponent>) => fixture.nativeElement as HTMLElement;

describe('ui-filter-bar', () => {
  it('shows one chip per condition in a named list, and the count on the button', async () => {
    const { fixture } = await render(ACTIVE);

    const list = el(fixture).querySelector('ul')!;
    expect(list.getAttribute('aria-label')).toBe(PACKAGED_RUSSIAN['ui.filter.active']);
    const chips = [...el(fixture).querySelectorAll('[data-testid="filter-chip"] .filter-chip-text')].map(chip => chip.textContent?.replace(/\s+/g, ' ').trim());
    expect(chips).toEqual([
      `${PACKAGED_RUSSIAN['upl.list.col.code']}: ${PACKAGED_RUSSIAN['ui.filter.op.starts_with']} cement.`,
      `${PACKAGED_RUSSIAN['upl.list.col.periodicity']}: ${PACKAGED_RUSSIAN['ui.filter.op.in']} ${PACKAGED_RUSSIAN['upl.periodicity.month']}, ${PACKAGED_RUSSIAN['upl.periodicity.year']}`
    ]);
    expect(el(fixture).querySelector('[data-testid="filter-count"]')?.textContent).toContain('Активных условий: 2');
  });

  it('removes one condition from its chip and clears them all', async () => {
    const { fixture, changes } = await render(ACTIVE);

    const remove = el(fixture).querySelectorAll('.filter-chip-remove')[0] as HTMLButtonElement;
    expect(remove.getAttribute('aria-label')).toContain('cement.');
    remove.click();
    (el(fixture).querySelector('[data-testid="filter-clear-all"]') as HTMLButtonElement).click();

    expect(changes).toEqual([[ACTIVE[1]], []]);
  });

  it('opens the builder in a drawer with the active conditions and takes back what it applies', async () => {
    const applied: QueryCondition[] = [{ field: 'periodicity', op: 'in', value: ['year'] }];
    const { fixture, open, changes } = await render([], applied);
    expect(el(fixture).querySelector('ul')).toBeNull();

    (el(fixture).querySelector('[data-testid="filter-trigger"]') as HTMLButtonElement).click();

    expect(open).toHaveBeenCalledWith(UiFilterPanelComponent, expect.objectContaining({
      title: PACKAGED_RUSSIAN['ui.filter.title'],
      data: { meta: META, conditions: [] }
    }));
    expect(changes).toEqual([applied]);
  });

  it('changes nothing when the builder is cancelled', async () => {
    const { fixture, changes } = await render(ACTIVE, undefined);
    (el(fixture).querySelector('[data-testid="filter-trigger"]') as HTMLButtonElement).click();
    expect(changes).toEqual([]);
  });
});
