import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastService } from '@core/services/toast.service';
import { PasswordApi } from '@features/auth/password.api';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { inScreen } from '@testing/in-screen';
import { LoginResetModalComponent } from './login-reset-modal.component';

@Component({
  imports: [LoginResetModalComponent],
  template: `<app-login-reset-modal [isOpen]="open()" (closeModal)="open.set(false); closes = closes + 1" />`,
})
class Host {
  readonly open = signal(true);
  closes = 0;
}

async function render(requestReset: (email: string) => Observable<unknown> = () => of(null)) {
  const api = { requestReset: vi.fn(requestReset) };
  const toast = { success: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: PasswordApi, useValue: api },
      { provide: ToastService, useValue: toast },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  document.body.appendChild(fixture.nativeElement);
  const settle = async () => {
    tickInZone();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  const screen = inScreen(fixture.nativeElement);
  const email = () => screen.querySelector('#reset-email') as HTMLInputElement | null;
  const buttons = () => screen.querySelectorAll('[role="dialog"] [footer] button') as HTMLButtonElement[];
  const type = async (text: string) => {
    email()!.value = text;
    email()!.dispatchEvent(new Event('input'));
    await settle();
  };
  const send = async () => {
    buttons()[1].click();
    await settle();
  };
  return { fixture, host: fixture.componentInstance, api, toast, settle, screen, email, buttons, type, send };
}

describe('LoginResetModalComponent', () => {
  it('is a named dialog with a labelled email field explained by the hint', async () => {
    const { screen, email } = await render();

    const dialog = screen.querySelector('[role="dialog"]') as HTMLElement;
    expect(screen.querySelector('.smt-modal__title')?.textContent).toBe('Восстановление пароля');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(screen.querySelector('label[for="reset-email"]')).not.toBeNull();
    expect(email()!.type).toBe('email');
    expect(email()!.getAttribute('aria-describedby')).toBe('reset-hint');
  });

  it('sends nothing while the email is empty', async () => {
    const { api, send } = await render();

    await send();

    expect(api.requestReset).not.toHaveBeenCalled();
  });

  it('requests the link for the typed email, closes and says what happens next', async () => {
    const { api, toast, host, type, send } = await render();

    await type('anna@company.com');
    await send();

    expect(api.requestReset).toHaveBeenCalledWith('anna@company.com');
    expect(host.closes).toBe(1);
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('ссылка уже отправлена'));
  });

  it('shows the server problem as an alert and marks the field invalid', async () => {
    const { screen, email, host, type, send } = await render(() =>
      throwError(() => ({ status: 429, detail: 'Слишком много запросов' })),
    );

    await type('anna@company.com');
    await send();

    expect(screen.querySelector('[role="dialog"] [role="alert"]')?.textContent).toBe('Слишком много запросов');
    expect(email()!.getAttribute('aria-invalid')).toBe('true');
    expect(host.closes).toBe(0);
  });

  it('falls back to a general message when the server gives no reason', async () => {
    const { screen, type, send } = await render(() => throwError(() => ({ status: 500 })));

    await type('anna@company.com');
    await send();

    expect(screen.querySelector('[role="dialog"] [role="alert"]')?.textContent).toBe(
      'Не удалось отправить инструкцию. Повторите попытку позже.',
    );
  });

  it('shows the send button busy while the request runs, and cancel forgets the typed email', async () => {
    const pending = new Subject<unknown>();
    const { host, fixture, buttons, type, send, settle, email } = await render(() => pending);

    await type('anna@company.com');
    await send();
    expect(buttons()[1].getAttribute('aria-busy')).toBe('true');

    buttons()[0].click();
    await settle();
    expect(host.closes).toBe(1);

    host.open.set(true);
    fixture.detectChanges();
    await settle();
    expect(email()!.value).toBe('');
  });
});
