import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { QueryCondition, QueryListMeta } from '../../core/models/query-meta.models';
import { PACKAGED_RUSSIAN } from '../../core/i18n/packaged-russian';
import { SMT_DRAWER_DATA, SMT_DRAWER_REF } from '../ui-kit/components/drawer';
import { UiFilterPanelComponent } from './ui-filter-panel.component';

const META: QueryListMeta = {
  code: 'upl.sources',
  defaultSort: 'code',
  defaultLimit: 50,
  maxLimit: 200,
  maxConditions: 2,
  maxInValues: 100,
  fields: [
    { key: 'code', labelKey: 'upl.list.col.code', type: 'text', ops: ['eq', 'starts_with'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null },
    { key: 'periodicity', labelKey: 'upl.list.col.periodicity', type: 'enum', ops: ['eq', 'in'], sortable: false, nullable: false, defaultVisible: true, enumValues: ['month', 'year'], enumLabelPrefix: 'upl.periodicity.' },
    { key: 'lastPublishedVersion', labelKey: 'upl.list.col.published_version', type: 'number', ops: ['gt', 'empty'], sortable: false, nullable: true, defaultVisible: true, enumValues: [], enumLabelPrefix: null }
  ]
};

async function render(conditions: QueryCondition[] = []) {
  const close = vi.fn();
  await TestBed.configureTestingModule({
    imports: [UiFilterPanelComponent],
    providers: [
      { provide: SMT_DRAWER_DATA, useValue: { meta: META, conditions } },
      { provide: SMT_DRAWER_REF, useValue: { close, afterClosed: vi.fn(), componentInstance: null } }
    ]
  }).compileComponents();
  const fixture = TestBed.createComponent(UiFilterPanelComponent);
  fixture.detectChanges();
  return { fixture, close };
}

const el = (fixture: ComponentFixture<UiFilterPanelComponent>) => fixture.nativeElement as HTMLElement;
const all = (fixture: ComponentFixture<UiFilterPanelComponent>, id: string) =>
  [...el(fixture).querySelectorAll(`[data-testid="${id}"]`)] as HTMLElement[];
const button = (fixture: ComponentFixture<UiFilterPanelComponent>, id: string) =>
  el(fixture).querySelector(`[data-testid="${id}"] button`) as HTMLButtonElement;
function choose(select: HTMLElement, value: string) {
  (select as HTMLSelectElement).value = value;
  select.dispatchEvent(new Event('change'));
}
function type(input: HTMLElement, value: string) {
  (input as HTMLInputElement).value = value;
  input.dispatchEvent(new Event('input'));
}

describe('ui-filter-panel', () => {
  it('builds rows of labelled field, condition and value controls inside numbered fieldsets', async () => {
    const { fixture } = await render();
    expect(all(fixture, 'filter-empty')).toHaveLength(1);

    button(fixture, 'filter-add').click();
    fixture.detectChanges();

    const row = all(fixture, 'filter-row')[0];
    expect(row.tagName).toBe('FIELDSET');
    expect(row.querySelector('legend')?.textContent).toContain('Условие 1');
    const field = row.querySelector('[data-testid="filter-field"]') as HTMLSelectElement;
    expect(field.closest('label')?.textContent).toContain(PACKAGED_RUSSIAN['ui.filter.field']);
    expect([...field.options].map(option => option.textContent?.trim())).toEqual([
      PACKAGED_RUSSIAN['upl.list.col.code'], PACKAGED_RUSSIAN['upl.list.col.periodicity'], PACKAGED_RUSSIAN['upl.list.col.published_version']
    ]);
    const op = row.querySelector('[data-testid="filter-op"]') as HTMLSelectElement;
    expect([...op.options].map(option => option.textContent?.trim())).toEqual([PACKAGED_RUSSIAN['ui.filter.op.eq'], PACKAGED_RUSSIAN['ui.filter.op.starts_with']]);
    expect(row.querySelector('[data-testid="filter-remove"]')?.getAttribute('aria-label')).toBe('Удалить условие 1');
  });

  it('keeps an incomplete row open with its reason and applies complete rows as conditions', async () => {
    const { fixture, close } = await render();
    button(fixture, 'filter-add').click();
    fixture.detectChanges();

    button(fixture, 'filter-apply').click();
    fixture.detectChanges();
    expect(close).not.toHaveBeenCalled();
    const row = all(fixture, 'filter-row')[0];
    const error = row.querySelector('[data-testid="filter-error"]')!;
    expect(error.textContent).toContain(PACKAGED_RUSSIAN['ui.filter.err.required']);
    expect(row.getAttribute('aria-describedby')).toBe(error.id);

    choose(row.querySelector('[data-testid="filter-op"]')!, 'starts_with');
    fixture.detectChanges();
    type(all(fixture, 'filter-value')[0], 'cement.');
    fixture.detectChanges();
    expect(all(fixture, 'filter-error')).toHaveLength(0);

    button(fixture, 'filter-apply').click();
    expect(close).toHaveBeenCalledWith([{ field: 'code', op: 'starts_with', value: 'cement.' }]);
  });

  it('offers the enum values as choices and needs no value for "is empty"', async () => {
    const { fixture, close } = await render();
    button(fixture, 'filter-add').click();
    fixture.detectChanges();
    choose(all(fixture, 'filter-field')[0], 'periodicity');
    fixture.detectChanges();
    choose(all(fixture, 'filter-op')[0], 'in');
    fixture.detectChanges();

    const group = el(fixture).querySelector('[role="group"]')!;
    expect(document.getElementById(group.getAttribute('aria-labelledby')!)?.textContent).toContain(PACKAGED_RUSSIAN['ui.filter.values']);
    const choices = all(fixture, 'filter-choice') as HTMLInputElement[];
    expect(choices.map(choice => choice.closest('label')?.textContent?.trim())).toEqual([
      PACKAGED_RUSSIAN['upl.periodicity.month'], PACKAGED_RUSSIAN['upl.periodicity.year']
    ]);
    choices[1].click();
    fixture.detectChanges();

    button(fixture, 'filter-add').click();
    fixture.detectChanges();
    choose(all(fixture, 'filter-field')[1], 'lastPublishedVersion');
    fixture.detectChanges();
    choose(all(fixture, 'filter-op')[1], 'empty');
    fixture.detectChanges();
    expect(all(fixture, 'filter-row')[1].querySelector('[data-testid="filter-value"]')).toBeNull();
    expect(button(fixture, 'filter-add').disabled).toBe(true);
    expect(el(fixture).textContent).toContain('Не больше 2 условий');

    button(fixture, 'filter-apply').click();
    expect(close).toHaveBeenCalledWith([
      { field: 'periodicity', op: 'in', value: ['year'] },
      { field: 'lastPublishedVersion', op: 'empty' }
    ]);
  });

  it('opens with the active conditions, clears them all and cancels without a result', async () => {
    const { fixture, close } = await render([{ field: 'lastPublishedVersion', op: 'gt', value: 2 }]);
    expect((all(fixture, 'filter-value')[0] as HTMLInputElement).value).toBe('2');
    expect((all(fixture, 'filter-value')[0] as HTMLInputElement).type).toBe('number');

    button(fixture, 'filter-clear').click();
    fixture.detectChanges();
    expect(all(fixture, 'filter-row')).toHaveLength(0);
    button(fixture, 'filter-apply').click();
    expect(close).toHaveBeenLastCalledWith([]);

    button(fixture, 'filter-cancel').click();
    expect(close).toHaveBeenLastCalledWith();
  });
});
