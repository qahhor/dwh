import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../../core/services/auth.service';
import { IdleLockService } from '../../../core/services/idle-lock.service';
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
        { provide: AuthService, useValue: auth }
      ]
    });
  });

  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
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
    (find('idle-keep')?.querySelector('button') ?? find('idle-keep'))!.click();
    expect(idle.keepWorking).toHaveBeenCalledTimes(1);
    (find('idle-sign-out')?.querySelector('button') ?? find('idle-sign-out'))!.click();
    expect(auth.logout).toHaveBeenCalledTimes(1);
  });
});
