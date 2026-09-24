// @vitest-environment jsdom
import '@angular/compiler';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { SMTProgressStep, SMTProgressStepperComponent } from './progress-stepper.component';

const STEPS: SMTProgressStep[] = [
  { id: 'file', label: 'File', status: 'complete', controls: 'panel-file' },
  { id: 'sheets', label: 'Sheets', status: 'error', hint: '2 errors' },
  { id: 'publish', label: 'Publish' },
];

describe('SMTProgressStepperComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  function render(current = 'sheets') {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(SMTProgressStepperComponent);
    fixture.componentRef.setInput('smtSteps', STEPS);
    fixture.componentRef.setInput('smtLabel', 'Format steps');
    fixture.componentRef.setInput('smtCurrent', current);
    const changes: string[] = [];
    fixture.componentInstance.current.subscribe(id => changes.push(id));
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const buttons = () => Array.from(element.querySelectorAll('button')) as HTMLButtonElement[];
    return { fixture, element, buttons, changes };
  }

  it('is a labelled navigation with an ordered list of steps', () => {
    const { element, buttons } = render();

    const nav = element.querySelector('nav')!;
    expect(nav.getAttribute('aria-label')).toBe('Format steps');
    expect(nav.querySelectorAll('ol > li').length).toBe(3);
    expect(buttons().every(button => button.type === 'button' && button.tabIndex !== -1)).toBe(true);
  });

  it('marks only the current step and names each status', () => {
    const { buttons } = render();
    const [file, sheets, publish] = buttons();

    expect(sheets.getAttribute('aria-current')).toBe('step');
    expect(file.hasAttribute('aria-current')).toBe(false);
    expect(file.textContent).toContain('done');
    expect(sheets.textContent).toContain('has errors');
    expect(sheets.textContent).toContain('2 errors');
    expect(publish.textContent).not.toContain('done');
    expect(publish.querySelector('.smt-stepper__marker')!.textContent!.trim()).toBe('3');
    expect(file.getAttribute('aria-controls')).toBe('panel-file');
  });

  it('moves to any step on click, forward or back', () => {
    const { fixture, buttons, changes } = render('file');

    buttons()[2].click();
    fixture.detectChanges();
    expect(buttons()[2].getAttribute('aria-current')).toBe('step');

    buttons()[0].click();
    fixture.detectChanges();
    expect(buttons()[0].getAttribute('aria-current')).toBe('step');
    expect(changes).toEqual(['publish', 'file']);
  });
});
