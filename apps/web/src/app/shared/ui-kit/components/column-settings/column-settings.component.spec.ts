// @vitest-environment jsdom
import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { tickInZone } from '../../testing/zone-tick';
import { EMPTY_COLUMN_STATE, TableColumnState } from '../table/column-state';
import { SMTColumnOption, SMTColumnSettingsComponent } from './column-settings.component';

const COLUMNS: SMTColumnOption[] = [
  { key: 'code', label: 'Code', locked: true },
  { key: 'name', label: 'Name' },
  { key: 'period', label: 'Period' },
];

describe('SMTColumnSettingsComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  function render(state: TableColumnState = EMPTY_COLUMN_STATE) {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(SMTColumnSettingsComponent);
    fixture.componentRef.setInput('smtColumns', COLUMNS);
    fixture.componentRef.setInput('smtState', state);
    const changes: TableColumnState[] = [];
    fixture.componentInstance.state.subscribe(next => changes.push(next));
    fixture.detectChanges();
    const trigger = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    const panel = () => document.querySelector('[role="dialog"]') as HTMLElement | null;
    const refresh = () => { tickInZone(); fixture.detectChanges(); };
    const openPanel = () => { trigger.click(); refresh(); };
    const labels = () => [...panel()!.querySelectorAll('.smt-columns__label')].map(label => label.firstChild?.textContent?.trim());
    return { fixture, trigger, panel, openPanel, refresh, labels, changes };
  }

  it('is a disclosure button for a labelled dialog of the columns', () => {
    const { trigger, panel, openPanel } = render();

    expect(trigger.type).toBe('button');
    expect(trigger.getAttribute('aria-haspopup')).toBe('dialog');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    openPanel();

    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(trigger.getAttribute('aria-controls')).toBe(panel()!.id);
    const titleId = panel()!.getAttribute('aria-labelledby')!;
    expect(document.getElementById(titleId)!.textContent).toContain('Table columns');
    const checks = [...panel()!.querySelectorAll('input[type="checkbox"]')] as HTMLInputElement[];
    expect(checks.map(check => document.querySelector(`label[for="${check.id}"]`)?.textContent)).toEqual([
      expect.stringContaining('Code'), expect.stringContaining('Name'), expect.stringContaining('Period'),
    ]);
    expect(checks[0].disabled).toBe(true);
    expect(panel()!.textContent).toContain('always shown');
  });

  it('hides a column but never the last visible one', () => {
    const { panel, openPanel, refresh, changes, fixture } = render({ order: [], hidden: ['name'], widths: {} });
    fixture.componentRef.setInput('smtColumns', COLUMNS.map(column => ({ ...column, locked: false })));
    openPanel();

    const period = panel()!.querySelectorAll('input[type="checkbox"]')[2] as HTMLInputElement;
    period.click();
    refresh();

    expect(changes.at(-1)!.hidden).toEqual(['name', 'period']);
    const code = panel()!.querySelectorAll('input[type="checkbox"]')[0] as HTMLInputElement;
    expect(code.checked).toBe(true);
    expect(code.disabled).toBe(true);
  });

  it('moves a column, announces its new place and keeps focus on it', () => {
    const { panel, openPanel, refresh, labels, changes } = render();
    openPanel();

    const periodUp = panel()!.querySelector('button[aria-label="Move Period up"]') as HTMLButtonElement;
    periodUp.click();
    refresh();

    expect(changes.at(-1)!.order).toEqual(['code', 'period', 'name']);
    expect(labels()).toEqual(['Code', 'Period', 'Name']);
    expect(panel()!.querySelector('[role="status"]')!.textContent).toContain('Period: place 2 of 3');
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Move Period up');

    (panel()!.querySelector('button[aria-label="Move Period up"]') as HTMLButtonElement).click();
    refresh();
    expect(labels()).toEqual(['Period', 'Code', 'Name']);
    expect((panel()!.querySelector('button[aria-label="Move Period up"]') as HTMLButtonElement).disabled).toBe(true);
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Move Period down');
  });

  it('resets to the table defaults and closes on Escape with focus back on the button', () => {
    const { panel, openPanel, refresh, changes, trigger } = render({ order: ['period'], hidden: ['name'], widths: { code: '90px' } });
    openPanel();

    (panel()!.querySelector('.smt-columns__reset') as HTMLButtonElement).click();
    refresh();
    expect(changes.at(-1)).toEqual(EMPTY_COLUMN_STATE);
    expect(panel()!.querySelector('[role="status"]')!.textContent).toContain('Columns reset');

    panel()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    refresh();
    expect(panel()).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });
});
