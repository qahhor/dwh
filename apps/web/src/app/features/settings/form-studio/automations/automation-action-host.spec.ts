import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { FormMetaService } from '@core/services/form-meta.service';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { ORDER_META } from '@testing/automations';
import { AutomationActionComponent } from './automation-action.component';
import { AutomationFormulaComponent } from './automation-formula.component';
import { AutomationsApi, type AutomationAction } from './automations.api';

@Component({
  imports: [AutomationActionComponent],
  template: `<app-automation-action
    [action]="action()"
    [index]="0"
    form="studio.orders"
    [meta]="meta"
    trigger="saved"
    (changed)="action.set($event)"
  />`,
})
class HostComponent {
  readonly action = signal<AutomationAction>({ type: 'set_field', values: [{ field: '', formula: '' }] });
  readonly meta = ORDER_META;
}

/* An action edited part by part keeps every part: a field chosen, then its formula typed (ADR-0046). */
describe('AutomationActionComponent in its editor', () => {
  it('keeps the field chosen when the formula is typed after it', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: FormMetaService, useValue: { get: vi.fn(() => of(ORDER_META)) } },
        { provide: AutomationsApi, useValue: { check: vi.fn(() => of({ valid: true, type: 'bool' })) } },
      ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const select = fixture.debugElement.query(By.directive(SMTSelectComponent))
      .componentInstance as SMTSelectComponent<string>;
    select.pick({ id: 'review', label: 'review' });
    await fixture.whenStable();
    fixture.detectChanges();
    const formula = fixture.debugElement.query(By.directive(AutomationFormulaComponent))
      .componentInstance as AutomationFormulaComponent;
    formula.edit('true');
    await fixture.whenStable();
    expect(fixture.componentInstance.action().values).toEqual([{ field: 'review', formula: 'true' }]);
  });
});
