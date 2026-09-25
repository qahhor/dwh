// @vitest-environment jsdom
import '@angular/compiler';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { SMTI18nService } from '../../i18n';
import { testI18n } from '../../i18n/test-messages';
import { SMTAlertComponent } from './alert.component';

@Component({
  standalone: true,
  imports: [SMTAlertComponent],
  template: `
    <smt-alert smtTone="danger" smtTitle="Could not save" data-testid="danger">The server refused.</smt-alert>
    <smt-alert smtTone="success" data-testid="success">Saved.</smt-alert>
    <smt-alert smtTone="success" smtLive="off" data-testid="quiet">10 rows = 10 rows</smt-alert>
    <smt-alert smtTone="warning" smtDismissible (smtDismiss)="dismissed = dismissed + 1" data-testid="warning">Check the period.</smt-alert>
  `,
})
class Host {
  dismissed = 0;
}

describe('SMTAlertComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  function render() {
    TestBed.configureTestingModule({ providers: [{ provide: SMTI18nService, useValue: testI18n() }] });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const alert = (id: string) => fixture.nativeElement.querySelector(`[data-testid="${id}"]`) as HTMLElement;
    return { fixture, alert };
  }

  it('reads a danger message at once and the others politely, unless it is part of the page', () => {
    const { alert } = render();
    expect(alert('danger').getAttribute('role')).toBe('alert');
    expect(alert('success').getAttribute('role')).toBe('status');
    expect(alert('quiet').hasAttribute('role')).toBe(false);
  });

  it('shows the tone, its icon hidden from screen readers, the title and the text', () => {
    const { alert } = render();
    const danger = alert('danger');
    expect(danger.classList).toContain('smt-alert--danger');
    expect(danger.querySelector('.smt-alert__icon')!.getAttribute('aria-hidden')).toBe('true');
    expect(danger.querySelector('.smt-alert__title')!.textContent).toBe('Could not save');
    expect(danger.querySelector('.smt-alert__content')!.textContent).toBe('The server refused.');
    expect(danger.querySelector('button')).toBeNull();
  });

  it('closes on request with a named button', () => {
    const { fixture, alert } = render();
    const close = alert('warning').querySelector('button') as HTMLButtonElement;
    expect(close.getAttribute('aria-label')).toBe('Close');
    close.click();
    expect(fixture.componentInstance.dismissed).toBe(1);
  });
});
