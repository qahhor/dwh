import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastService } from '@core/services/toast.service';
import { PasswordApi } from '@features/auth/password.api';
import { SMTModalService } from '@shared/ui-kit/components/modal';
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

async function render(requestReset: (email: string) => Observable<unknown> = () => of(null), discard = true) {
  const api = { requestReset: vi.fn(requestReset) };
  const toast = { success: vi.fn(), error: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: PasswordApi, useValue: api },
      { provide: ToastService, useValue: toast },
    ],
  });
  const confirm = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(discard));
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
  const submit = () => screen.querySelector('[data-testid="form-submit"]') as HTMLButtonElement;
  const cancel = () => screen.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement;
  const error = () => screen.querySelector('[role="dialog"] .smt-control__error')?.textContent?.trim();
  const type = async (text: string) => {
    email()!.value = text;
    email()!.dispatchEvent(new Event('input'));
    await settle();
  };
  const send = async () => {
    submit().click();
    await settle();
    await settle();
  };
  return {
    fixture,
    host: fixture.componentInstance,
    api,
    toast,
    confirm,
    settle,
    screen,
    email,
    submit,
    cancel,
    error,
    type,
    send,
  };
}

describe('LoginResetModalComponent', () => {
  it('is a named dialog with a labelled, required email field explained by the hint', async () => {
    const { screen, email, settle } = await render();
    await settle();

    const dialog = screen.querySelector('[role="dialog"]') as HTMLElement;
    expect(screen.querySelector('.smt-modal__title')?.textContent).toBe('Восстановление пароля');
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(screen.querySelector('label[for="reset-email"]')?.textContent).toContain('Email');
    expect(screen.querySelector('.smt-control__required')).not.toBeNull();
    expect(email()!.type).toBe('email');
    expect(email()!.getAttribute('aria-describedby')).toBe('reset-hint');
    expect(email()!.getAttribute('aria-required')).toBe('true');
  });

  it('puts focus on the email field when it opens, not on the close button', async () => {
    const { email, settle } = await render();
    await settle();

    expect(email()!.hasAttribute('cdkFocusInitial')).toBe(true);
  });

  it('explains an empty submit under the field and focuses it instead of doing nothing', async () => {
    const { api, send, error, email } = await render();

    await send();

    expect(api.requestReset).not.toHaveBeenCalled();
    expect(error()).toBe('Укажите email');
    expect(email()!.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(email());
  });

  it('explains a malformed address under the field', async () => {
    const { api, type, send, error } = await render();

    await type('anna');
    await send();

    expect(api.requestReset).not.toHaveBeenCalled();
    expect(error()).toBeTruthy();
  });

  it('requests the link for the typed email, closes and says what happens next', async () => {
    const { api, toast, host, type, send } = await render();

    await type('anna@company.com');
    await send();

    expect(api.requestReset).toHaveBeenCalledWith('anna@company.com');
    expect(host.closes).toBe(1);
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('ссылка уже отправлена'));
  });

  it('shows a refusal of no field as an alert and keeps the dialog open', async () => {
    const { screen, host, type, send } = await render(() =>
      throwError(() => ({ status: 429, detail: 'Слишком много запросов' })),
    );

    await type('anna@company.com');
    await send();

    expect(screen.querySelector('[role="dialog"] [role="alert"]')?.textContent).toContain('Слишком много запросов');
    expect(host.closes).toBe(0);
  });

  it('puts the server word about the address under the field', async () => {
    const { screen, type, send, error, email } = await render(() =>
      throwError(() => ({ status: 422, errors: [{ field: 'email', code: 'Email', message: 'Неверный адрес' }] })),
    );

    await type('anna@company.com');
    await send();

    expect(error()).toBe('Неверный адрес');
    expect(email()!.getAttribute('aria-invalid')).toBe('true');
    expect(screen.querySelector('[role="dialog"] [role="alert"]')).toBeNull();
  });

  it('falls back to a general message when the server gives no reason', async () => {
    const { screen, type, send } = await render(() => throwError(() => ({ status: 500 })));

    await type('anna@company.com');
    await send();

    expect(screen.querySelector('[role="dialog"] [role="alert"]')?.textContent).toContain(
      'Не удалось отправить инструкцию. Повторите попытку позже.',
    );
  });

  it('keeps the send button busy and the dialog open while the request runs', async () => {
    const pending = new Subject<unknown>();
    const { host, submit, cancel, type, send, api } = await render(() => pending);

    await type('anna@company.com');
    await send();
    await send();

    expect(api.requestReset).toHaveBeenCalledTimes(1);
    expect(submit().getAttribute('aria-busy')).toBe('true');
    expect(cancel().disabled).toBe(true);
    expect(host.closes).toBe(0);
  });

  it('closes an untouched dialog without a question', async () => {
    const { host, cancel, confirm, settle } = await render();

    cancel().click();
    await settle();

    expect(confirm).not.toHaveBeenCalled();
    expect(host.closes).toBe(1);
  });

  it('asks before a typed address is lost and starts empty on the next opening', async () => {
    const { host, fixture, cancel, confirm, type, settle, email } = await render();

    await type('anna@company.com');
    cancel().click();
    await settle();
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(host.closes).toBe(1);

    host.open.set(true);
    fixture.detectChanges();
    await settle();
    expect(email()!.value).toBe('');
  });

  it('keeps the dialog when the person chooses to go on editing', async () => {
    const { host, cancel, type, settle, email } = await render(() => of(null), false);

    await type('anna@company.com');
    cancel().click();
    await settle();

    expect(host.closes).toBe(0);
    expect(email()!.value).toBe('anna@company.com');
  });
});
