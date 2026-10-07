import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { SMTEntityPageStateComponent } from './smt-entity-page-state.component';

type Kind = 'entity' | 'record' | 'denied' | 'failed';

describe('SMTEntityPageStateComponent', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  function render(kind: Kind, back: string | null = null) {
    const fixture = TestBed.createComponent(SMTEntityPageStateComponent);
    fixture.componentRef.setInput('kind', kind);
    fixture.componentRef.setInput('back', back);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    return { fixture, host, state: host.querySelector('[data-testid="entity-page-state"]') as HTMLElement };
  }

  it.each<[Kind, string, string, string]>([
    ['entity', 'search_off', 'ui.entity_page.entity_missing', 'ui.entity_page.entity_missing_hint'],
    ['record', 'search_off', 'ui.entity_page.record_missing', 'ui.entity_page.record_missing_hint'],
    ['denied', 'lock', 'ui.entity_page.denied', 'ui.entity_page.denied_hint'],
    ['failed', 'error', 'ui.entity_page.load_failed', 'ui.entity_page.load_failed_hint'],
  ])('shows the %s state with its icon, title and hint', (kind, icon, title, hint) => {
    const { state } = render(kind);

    expect(state.querySelector('.entity-state-icon')?.textContent?.trim()).toBe(icon);
    expect(state.querySelector('h2')?.textContent?.trim()).toBe(translateTest(title));
    expect(state.querySelector('p')?.textContent?.trim()).toBe(translateTest(hint));
  });

  it('announces only a failure, and offers its retry', () => {
    const { fixture, state } = render('failed');
    const retried = vi.fn();
    fixture.componentInstance.retry.subscribe(retried);

    expect(state.getAttribute('role')).toBe('alert');
    (state.querySelector('button') as HTMLButtonElement).click();
    expect(retried).toHaveBeenCalledTimes(1);

    const missing = render('record').state;
    expect(missing.getAttribute('role')).toBeNull();
    expect(missing.querySelector('button')).toBeNull();
  });

  it('links back only when it is given where to', () => {
    expect(render('entity').state.querySelector('a')).toBeNull();

    const link = render('entity', '/e/demo_requests').state.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/e/demo_requests');
    expect(link.textContent).toContain(translateTest('ui.entity_page.back'));
  });
});
