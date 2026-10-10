import { TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
import { ProfileApi } from '../profile.api';
import { UserChannel } from '../profile.models';
import { ProfileChannelsCardComponent } from './profile-channels-card.component';

describe('ProfileChannelsCardComponent', () => {
  const mail: UserChannel = {
    id: 1,
    userId: 7,
    channel: 'email',
    address: 'anna@example.test',
    isVerified: true,
    createdAt: '2026-09-01T10:00:00Z',
  };
  const telegram: UserChannel = {
    id: 2,
    userId: 7,
    channel: 'telegram',
    address: '@anna',
    isVerified: false,
    createdAt: '2026-09-02T10:00:00Z',
  };

  function setup(
    inputs: { channels?: UserChannel[]; canManageChannels?: boolean } = {},
    api: {
      bind?: () => Observable<{ verifyToken: string }>;
      confirm?: () => Observable<void>;
      discard?: boolean;
    } = {},
  ) {
    const profile = {
      bindChannel: vi.fn(api.bind ?? (() => of({ verifyToken: 'token-1' }))),
      confirmChannel: vi.fn(api.confirm ?? (() => of(undefined))),
    };
    const toast = { success: vi.fn(), info: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ProfileApi, useValue: profile },
        { provide: ToastService, useValue: toast },
      ],
    });
    const confirmDialog = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(api.discard ?? true));
    const fixture = TestBed.createComponent(ProfileChannelsCardComponent);
    fixture.componentRef.setInput('channels', inputs.channels ?? [mail, telegram]);
    fixture.componentRef.setInput('canManageChannels', inputs.canManageChannels ?? true);
    const component = fixture.componentInstance;
    const asked = { changed: vi.fn(), unbind: vi.fn() };
    component.changed.subscribe(asked.changed);
    component.unbindChannel.subscribe(asked.unbind);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const screen = inScreen(host);
    const settle = async () => {
      tickInZone();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };
    const button = (label: string) => screen.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text)!;
    const type = async (id: string, value: string) => {
      const field = screen.querySelector(`#${id}`) as HTMLInputElement;
      field.value = value;
      field.dispatchEvent(new Event('input'));
      await settle();
      return field;
    };
    const errorText = () => screen.querySelector('[role="dialog"] .smt-control__error')?.textContent?.trim();
    return {
      fixture,
      component,
      host,
      screen,
      button,
      byText,
      type,
      settle,
      asked,
      profile,
      toast,
      confirmDialog,
      errorText,
    };
  }

  it('lists the channels with their kind and whether each is confirmed', () => {
    const { host } = setup();

    expect(host.querySelector('.badge-count')?.textContent?.trim()).toBe('2');
    const rows = Array.from(host.querySelectorAll('.smt-data-row')) as HTMLElement[];
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('Электронная почта (Email)');
    expect(rows[0].textContent).toContain('Подтверждён');
    expect(rows[1].textContent).toContain('Telegram');
    expect(rows[1].textContent).toContain('Ожидает подтверждения');
  });

  it('says there are no channels when the list is empty', () => {
    const { host } = setup({ channels: [] });

    expect(host.querySelector('.empty-cell')?.textContent).toContain('Каналы связи не привязаны');
  });

  it('sends a new code to an unconfirmed channel and asks the page to unbind any', async () => {
    const { button, asked, profile, component, settle } = setup();

    expect(button('Подтвердить anna@example.test кодом')).toBeNull();
    button('Подтвердить @anna кодом')!.click();
    button('Отвязать anna@example.test')!.click();
    await settle();

    expect(profile.bindChannel).toHaveBeenCalledWith('telegram', '@anna');
    expect(component.isConfirmModalOpen()).toBe(true);
    expect(asked.changed).toHaveBeenCalledTimes(1);
    expect(asked.unbind).toHaveBeenCalledWith(mail);
  });

  it('reports a refused new code of a listed channel in one toast', async () => {
    const { button, toast, settle } = setup({}, { bind: () => throwError(() => ({ detail: 'Слишком часто' })) });

    button('Подтвердить @anna кодом')!.click();
    await settle();

    expect(toast.error).toHaveBeenCalledWith('Слишком часто');
  });

  it('offers no channel changes to a viewer who may not manage them', () => {
    const { host, button } = setup({ canManageChannels: false });

    expect(host.textContent).not.toContain('Привязать канал');
    expect(button('Подтвердить @anna кодом')).toBeNull();
    expect(button('Отвязать anna@example.test')).toBeNull();
  });

  it('explains an empty address under the field, then sends it trimmed and opens the code dialog', async () => {
    const { screen, byText, type, settle, profile, errorText, component } = setup();
    byText('Привязать канал').click();
    await settle();
    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Привязка канала связи');
    const address = screen.querySelector('#profile-channel-address') as HTMLInputElement;
    expect(screen.querySelector('label[for="profile-channel-address"]')).not.toBeNull();
    expect(address.getAttribute('aria-required')).toBe('true');
    expect(address.hasAttribute('cdkFocusInitial')).toBe(true);

    byText('Отправить код').click();
    await settle();
    await settle();
    expect(profile.bindChannel).not.toHaveBeenCalled();
    expect(errorText()).toBe('Адрес канала обязателен');
    expect(address.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(address);

    await type('profile-channel-address', '  anna@work.test ');
    byText('Отправить код').click();
    await settle();

    expect(profile.bindChannel).toHaveBeenCalledWith('email', 'anna@work.test');
    expect(component.isBindModalOpen()).toBe(false);
    expect(component.isConfirmModalOpen()).toBe(true);
    expect(component.activeVerifyToken()).toBe('token-1');
  });

  it('puts the server word on the address under the field and other refusals in an alert', async () => {
    let answer: Observable<{ verifyToken: string }> = throwError(() => ({
      status: 422,
      errors: [{ field: 'address', code: 'X', message: 'Неверный адрес' }],
    }));
    const { screen, byText, type, settle, errorText } = setup({}, { bind: () => answer });
    byText('Привязать канал').click();
    await settle();
    await type('profile-channel-address', 'anna');

    byText('Отправить код').click();
    await settle();
    expect(errorText()).toBe('Неверный адрес');

    answer = throwError(() => ({ status: 409, detail: 'Этот адрес уже привязан' }));
    byText('Отправить код').click();
    await settle();
    expect(screen.querySelector('[role="dialog"] [role="alert"]')?.textContent).toContain('Этот адрес уже привязан');
  });

  it('sends one code request while the first is running', async () => {
    const pending = new Subject<{ verifyToken: string }>();
    const { byText, type, settle, profile } = setup({}, { bind: () => pending });
    byText('Привязать канал').click();
    await settle();
    await type('profile-channel-address', 'anna@work.test');

    byText('Отправить код').click();
    byText('Отправить код').click();

    expect(profile.bindChannel).toHaveBeenCalledTimes(1);
  });

  it('asks before a typed address is lost', async () => {
    const { byText, type, settle, confirmDialog, component } = setup({}, { discard: false });
    byText('Привязать канал').click();
    await settle();
    byText('Отмена').click();
    expect(confirmDialog).not.toHaveBeenCalled();
    expect(component.isBindModalOpen()).toBe(false);

    byText('Привязать канал').click();
    await settle();
    await type('profile-channel-address', 'anna@work.test');
    byText('Отмена').click();
    expect(confirmDialog).toHaveBeenCalledTimes(1);
    expect(component.isBindModalOpen()).toBe(true);
  });

  it('confirms a channel only with a six-digit code', async () => {
    const { component, screen, byText, type, settle, profile, asked, errorText } = setup();
    component.openConfirmModal('token-1', '@anna');
    await settle();
    expect(screen.querySelector('.confirm-info-text').textContent).toContain('@anna');

    await type('profile-channel-code', '123');
    byText('Подтвердить').click();
    await settle();
    expect(profile.confirmChannel).not.toHaveBeenCalled();
    expect(errorText()).toBe('Код должен содержать ровно 6 цифр');

    await type('profile-channel-code', '123456');
    byText('Подтвердить').click();
    await settle();

    expect(profile.confirmChannel).toHaveBeenCalledWith('token-1', '123456');
    expect(component.isConfirmModalOpen()).toBe(false);
    expect(asked.changed).toHaveBeenCalledTimes(1);
  });

  it('puts a wrong code under the code field', async () => {
    const { component, byText, type, settle, errorText } = setup(
      {},
      { confirm: () => throwError(() => ({ code: 'otp_invalid', detail: 'Неверный код' })) },
    );
    component.openConfirmModal('token-1', '@anna');
    await settle();
    await type('profile-channel-code', '123456');

    byText('Подтвердить').click();
    await settle();

    expect(errorText()).toBe('Неверный код');
    expect(component.isConfirmModalOpen()).toBe(true);
  });
});
