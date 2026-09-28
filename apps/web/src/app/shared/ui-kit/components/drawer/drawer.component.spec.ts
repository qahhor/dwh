/* Not vendored: tests for the drawer panel itself (ADR-0015 rule 2); drawer.service.spec.ts opens it through the CDK. */
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SMTI18nService } from '@shared/ui-kit/i18n';
import { testI18n } from '@shared/ui-kit/i18n/test-messages';
import { SMTDrawerComponent } from './drawer.component';
import { SMT_DRAWER_CONFIG, SMT_DRAWER_REF, type SMTDrawerConfig } from './drawer.types';

describe('SMTDrawerComponent', () => {
  const drawerRef = { close: vi.fn(), afterClosed: vi.fn(), componentInstance: null };

  beforeEach(() => {
    drawerRef.close.mockReset();
  });

  function render(config: SMTDrawerConfig) {
    TestBed.configureTestingModule({
      providers: [
        { provide: SMTI18nService, useValue: testI18n() },
        { provide: SMT_DRAWER_REF, useValue: drawerRef },
        { provide: SMT_DRAWER_CONFIG, useValue: config },
      ],
    });
    const fixture = TestBed.createComponent(SMTDrawerComponent);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const dialog = () => element.querySelector('[role="dialog"]') as HTMLElement;
    const closeButton = () => element.querySelector('button[aria-label="Close"]') as HTMLButtonElement;
    const backdrop = () => element.querySelector('.smt-drawer') as HTMLElement;
    return { fixture, element, dialog, closeButton, backdrop };
  }

  it('is a modal dialog named by its heading, as wide as asked', () => {
    const { dialog, closeButton } = render({ title: 'Пакет 42', width: '480px' });
    const heading = document.getElementById(dialog().getAttribute('aria-labelledby')!);

    expect(dialog().getAttribute('aria-modal')).toBe('true');
    expect(heading?.tagName).toBe('H2');
    expect(heading?.textContent).toBe('Пакет 42');
    expect(dialog().hasAttribute('aria-label')).toBe(false);
    expect(dialog().style.width).toBe('480px');
    expect(closeButton()).not.toBeNull();
  });

  it('is named by its aria label when it has no heading, and takes most of the screen by default', () => {
    const { element, dialog } = render({ ariaLabel: 'Детали пакета' });

    expect(element.querySelector('h2')).toBeNull();
    expect(dialog().getAttribute('aria-label')).toBe('Детали пакета');
    expect(dialog().hasAttribute('aria-labelledby')).toBe(false);
    expect(dialog().style.width).toBe('90dvw');
  });

  it('closes from its close button, or asks the caller first when the caller wants to decide', () => {
    render({ title: 'Пакет' }).closeButton().click();
    expect(drawerRef.close).toHaveBeenCalledTimes(1);

    TestBed.resetTestingModule();
    const onCloseRequest = vi.fn();
    render({ title: 'Пакет', onCloseRequest }).closeButton().click();
    expect(onCloseRequest).toHaveBeenCalledTimes(1);
    expect(drawerRef.close).toHaveBeenCalledTimes(1);
  });

  it('stays open on a click beside the panel unless asked to close on it, and never on a click inside', () => {
    const kept = render({ title: 'Пакет' });
    kept.backdrop().click();
    expect(drawerRef.close).not.toHaveBeenCalled();

    TestBed.resetTestingModule();
    const closing = render({ title: 'Пакет', closeOnBackdropClick: true });
    closing.dialog().click();
    expect(drawerRef.close).not.toHaveBeenCalled();
    closing.backdrop().click();
    expect(drawerRef.close).toHaveBeenCalledTimes(1);
  });
});
