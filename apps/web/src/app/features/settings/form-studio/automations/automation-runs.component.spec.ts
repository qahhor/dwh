import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AUTOMATION, RUNS } from '@testing/automations';
import { translateTest } from '@testing/i18n-test.stub';
import { AutomationRunsComponent } from './automation-runs.component';
import { AutomationsApi } from './automations.api';

/* The run log of an automation (ADR-0046): runs with their outcome, and what each action did or why it failed. */
describe('AutomationRunsComponent', () => {
  const api = { runs: vi.fn(() => of({ items: RUNS, hasMore: true, nextCursor: 'next' })) };

  beforeEach(() => {
    api.runs.mockClear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AutomationsApi, useValue: api }],
    });
  });

  function render() {
    const fixture = TestBed.createComponent(AutomationRunsComponent);
    fixture.componentRef.setInput('automation', AUTOMATION);
    fixture.detectChanges();
    return { fixture, component: fixture.componentInstance };
  }

  it('lists the runs, opens one with what its actions did and pages on', () => {
    const { component } = render();
    expect(api.runs).toHaveBeenCalledWith(5, null);
    expect(component.cursor()).toBe('next');
    component.toggle(RUNS[0]);
    expect(component.open()).toBe(11);
    expect(component.detail(RUNS[0].results[0])).toContain('Менеджер');
    expect(component.detail(RUNS[1].results[0])).toContain('review = true');
    expect(component.detail(RUNS[1].results[0])).toContain(translateTest('studio.automations.reason.refused'));
    expect(component.detail({ index: 0, type: 'create_record', status: 'done', recordId: 7 })).toContain('7');
    expect(component.problem(RUNS[1].error!)).toContain('review');
    component.toggle(RUNS[0]);
    expect(component.open()).toBeNull();
    component.load('next');
    expect(api.runs).toHaveBeenLastCalledWith(5, 'next');
    expect(component.runs()).toHaveLength(4);
  });

  it('says when the log cannot be read', () => {
    api.runs.mockReturnValueOnce(throwError(() => ({ status: 500 })));
    const { component } = render();
    expect(component.error()).toBe(translateTest('studio.automations.runs_failed'));
  });
});
