import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, expect, it, vi } from 'vitest';
import { QueryCondition, QueryListMeta } from '../../core/models/query-meta.models';
import { PACKAGED_RUSSIAN } from '../../core/i18n/packaged-russian';
import { SMT_DRAWER_DATA, SMT_DRAWER_REF } from '../ui-kit/components/drawer';
import { SMTSelectComponent } from '../ui-kit/components/forms/select';
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

/** A list with a date field, for the period editor. */
const DATED: QueryListMeta = {
  ...META,
  fields: [
    { key: 'uploadedAt', labelKey: 'upl.pkg.col.uploaded_at', type: 'date', ops: ['between', 'gte'], sortable: true, nullable: false, defaultVisible: true, enumValues: [], enumLabelPrefix: null }
  ]
};

async function render(conditions: QueryCondition[] = [], meta: QueryListMeta = META) {
  const close = vi.fn();
  await TestBed.configureTestingModule({
    imports: [UiFilterPanelComponent],
    providers: [
      { provide: SMT_DRAWER_DATA, useValue: { meta, conditions } },
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
  el(fixture).querySelector(`button[data-testid="${id}"]`) as HTMLButtonElement;
/** The smt-select whose host is the given element. */
function picker(fixture: ComponentFixture<UiFilterPanelComponent>, host: HTMLElement): SMTSelectComponent<unknown> {
  return fixture.debugElement.queryAll(By.directive(SMTSelectComponent)).find(debug => debug.nativeElement === host)!.componentInstance;
}
const labels = (fixture: ComponentFixture<UiFilterPanelComponent>, host: HTMLElement) =>
  picker(fixture, host).options().map(option => option.label);
function choose(fixture: ComponentFixture<UiFilterPanelComponent>, host: HTMLElement, value: string) {
  const select = picker(fixture, host);
  select.pick(select.options().find(option => option.id === value)!);
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
    const field = row.querySelector('[data-testid="filter-field"]') as HTMLElement;
    const fieldTrigger = field.querySelector('button[role="combobox"]') as HTMLButtonElement;
    expect(row.querySelector(`label[for="${fieldTrigger.id}"]`)?.textContent).toContain(PACKAGED_RUSSIAN['ui.filter.field']);
    expect(fieldTrigger.textContent).toContain(PACKAGED_RUSSIAN['upl.list.col.code']);
    expect(labels(fixture, field)).toEqual([
      PACKAGED_RUSSIAN['upl.list.col.code'], PACKAGED_RUSSIAN['upl.list.col.periodicity'], PACKAGED_RUSSIAN['upl.list.col.published_version']
    ]);
    const op = row.querySelector('[data-testid="filter-op"]') as HTMLElement;
    const opTrigger = op.querySelector('button[role="combobox"]') as HTMLButtonElement;
    expect(row.querySelector(`label[for="${opTrigger.id}"]`)?.textContent).toContain(PACKAGED_RUSSIAN['ui.filter.operation']);
    expect(labels(fixture, op)).toEqual([PACKAGED_RUSSIAN['ui.filter.op.eq'], PACKAGED_RUSSIAN['ui.filter.op.starts_with']]);
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

    choose(fixture, row.querySelector('[data-testid="filter-op"]')!, 'starts_with');
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
    choose(fixture, all(fixture, 'filter-field')[0], 'periodicity');
    fixture.detectChanges();
    const single = all(fixture, 'filter-value')[0];
    expect(single.querySelector('button[role="combobox"]')?.textContent).toContain(PACKAGED_RUSSIAN['ui.filter.choose']);
    expect(labels(fixture, single)).toEqual([PACKAGED_RUSSIAN['upl.periodicity.month'], PACKAGED_RUSSIAN['upl.periodicity.year']]);
    choose(fixture, all(fixture, 'filter-op')[0], 'in');
    fixture.detectChanges();

    const group = el(fixture).querySelector('[role="group"]')!;
    expect(document.getElementById(group.getAttribute('aria-labelledby')!)?.textContent).toContain(PACKAGED_RUSSIAN['ui.filter.values']);
    const boxes = all(fixture, 'filter-choice').map(choice => choice.querySelector('[role="checkbox"]') as HTMLElement);
    expect(boxes.map(box => document.getElementById(box.getAttribute('aria-labelledby')!)?.textContent?.trim())).toEqual([
      PACKAGED_RUSSIAN['upl.periodicity.month'], PACKAGED_RUSSIAN['upl.periodicity.year']
    ]);
    expect(boxes.map(box => box.getAttribute('aria-checked'))).toEqual(['false', 'false']);
    boxes[1].click();
    fixture.detectChanges();
    expect(boxes.map(box => box.getAttribute('aria-checked'))).toEqual(['false', 'true']);

    button(fixture, 'filter-add').click();
    fixture.detectChanges();
    choose(fixture, all(fixture, 'filter-field')[1], 'lastPublishedVersion');
    fixture.detectChanges();
    choose(fixture, all(fixture, 'filter-op')[1], 'empty');
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

  it('edits a "between" date as one period with presets and applies both bounds', async () => {
    const { fixture, close } = await render([{ field: 'uploadedAt', op: 'between', value: ['2026-09-01', '2026-09-10'] }], DATED);

    const range = all(fixture, 'filter-date-range')[0];
    const trigger = range.querySelector('.smt-date-range-picker__trigger') as HTMLButtonElement;
    expect(trigger.getAttribute('aria-label')).toBe(`${PACKAGED_RUSSIAN['ui.filter.period']}: 01.09.2026 – 10.09.2026`);
    expect(all(fixture, 'filter-date')).toHaveLength(0);

    trigger.click();
    fixture.detectChanges();
    const presets = [...document.querySelectorAll<HTMLButtonElement>('.smt-date-popup__preset')];
    presets[0].click();
    fixture.detectChanges();
    button(fixture, 'filter-apply').click();

    const applied = close.mock.calls.at(-1)?.[0] as QueryCondition[];
    expect(applied[0].field).toBe('uploadedAt');
    expect(applied[0].op).toBe('between');
    const [from, to] = applied[0].value as string[];
    expect(from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(to).toBe(from);
  });

  it('keeps a single date picker for a one-sided date condition', async () => {
    const { fixture } = await render([{ field: 'uploadedAt', op: 'gte', value: '2026-09-01' }], DATED);

    expect(all(fixture, 'filter-date')).toHaveLength(1);
    expect(all(fixture, 'filter-date-range')).toHaveLength(0);
  });
});
