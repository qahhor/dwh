import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { AuthService } from '@core/services/auth.service';
import { IdleLockService } from '@core/services/idle-lock.service';
import { IdleLockDialogComponent } from './idle-lock-dialog.component';

describe('IdleLockDialogComponent', () => {
  const warningSeconds = signal<number | null>(null);
  const idle = { warningSeconds, keepWorking: vi.fn() };
  const auth = { logout: vi.fn() };

  beforeEach(() => {
    warningSeconds.set(null);
    idle.keepWorking.mockClear();
    auth.logout.mockClear();
    TestBed.configureTestingModule({
      imports: [IdleLockDialogComponent],
      providers: [
        { provide: IdleLockService, useValue: idle },
        { provide: AuthService, useValue: auth },
      ],
    });
  });

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((node) => node.remove());
  });

  async function render() {
    const fixture = TestBed.createComponent(IdleLockDialogComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  function find(testId: string): HTMLElement | null {
    return document.querySelector(`[data-testid="${testId}"]`);
  }

  it('stays hidden while there is nothing to warn about', async () => {
    await render();
    expect(find('idle-warning')).toBeNull();
  });

  it('counts down and offers to go on or sign out', async () => {
    const fixture = await render();
    warningSeconds.set(42);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(find('idle-warning')?.textContent).toContain('42');
    const actions = find('idle-actions')!;
    const keep = actions.querySelector<HTMLButtonElement>('[data-testid="form-submit"]')!;
    const signOut = actions.querySelector<HTMLButtonElement>('[data-testid="form-cancel"]')!;
    // "Go on" is the primary action on the right; "Sign out" the secondary one before it.
    expect(keep.textContent?.trim()).toBe(PACKAGED_RUSSIAN['auth.idle.keep']);
    expect(signOut.compareDocumentPosition(keep) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    keep.click();
    expect(idle.keepWorking).toHaveBeenCalledTimes(1);
    signOut.click();
    expect(auth.logout).toHaveBeenCalledTimes(1);
  });
});
