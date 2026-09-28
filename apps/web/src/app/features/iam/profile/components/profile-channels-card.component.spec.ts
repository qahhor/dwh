import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
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

  function setup(inputs: { channels?: UserChannel[]; canManageChannels?: boolean } = {}) {
    const fixture = TestBed.createComponent(ProfileChannelsCardComponent);
    fixture.componentRef.setInput('channels', inputs.channels ?? [mail, telegram]);
    fixture.componentRef.setInput('canManageChannels', inputs.canManageChannels ?? true);
    const component = fixture.componentInstance;
    const asked = { bind: vi.fn(), confirm: vi.fn(), unbind: vi.fn() };
    component.bindChannel.subscribe(asked.bind);
    component.confirmChannel.subscribe(asked.confirm);
    component.unbindChannel.subscribe(asked.unbind);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const screen = inScreen(host);
    const button = (label: string) => screen.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text)!;
    const type = (id: string, value: string) => {
      const field = screen.querySelector(`#${id}`) as HTMLInputElement;
      field.value = value;
      field.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      return field;
    };
    return { fixture, component, host, screen, button, byText, type, asked };
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

  it('offers confirming only an unconfirmed channel and unbinding any, and asks the page for both', () => {
    const { button, asked } = setup();

    expect(button('Подтвердить anna@example.test кодом')).toBeNull();
    button('Подтвердить @anna кодом')!.click();
    button('Отвязать anna@example.test')!.click();

    expect(asked.bind).toHaveBeenCalledWith({ channel: 'telegram', address: '@anna' });
    expect(asked.unbind).toHaveBeenCalledWith(mail);
  });

  it('offers no channel changes to a viewer who may not manage them', () => {
    const { host, button } = setup({ canManageChannels: false });

    expect(host.textContent).not.toContain('Привязать канал');
    expect(button('Подтвердить @anna кодом')).toBeNull();
    expect(button('Отвязать anna@example.test')).toBeNull();
  });

  it('asks for an address before sending the code, then sends it trimmed', () => {
    const { fixture, screen, byText, type, asked } = setup();
    byText('Привязать канал').click();
    fixture.detectChanges();
    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Привязка канала связи');
    expect(screen.querySelector('label[for="profile-channel-address"]')).not.toBeNull();

    byText('Отправить код').click();
    fixture.detectChanges();
    const address = screen.querySelector('#profile-channel-address') as HTMLInputElement;
    expect(asked.bind).not.toHaveBeenCalled();
    expect(screen.querySelector('#profile-channel-address-error').textContent).toContain('Адрес канала обязателен');
    expect(address.getAttribute('aria-describedby')).toBe('profile-channel-address-error');

    type('profile-channel-address', '  anna@work.test ');
    byText('Отправить код').click();

    expect(asked.bind).toHaveBeenCalledWith({ channel: 'email', address: 'anna@work.test' });
  });

  it('confirms a channel only with a six-digit code', () => {
    const { fixture, component, screen, byText, type, asked } = setup();
    component.openConfirmModal('token-1', '@anna');
    fixture.detectChanges();
    expect(screen.querySelector('.confirm-info-text').textContent).toContain('@anna');

    type('profile-channel-code', '123');
    byText('Подтвердить').click();
    fixture.detectChanges();
    expect(asked.confirm).not.toHaveBeenCalled();
    expect(screen.querySelector('#profile-channel-code-error').textContent).toContain(
      'Код должен содержать ровно 6 цифр',
    );

    type('profile-channel-code', '123456');
    byText('Подтвердить').click();

    expect(asked.confirm).toHaveBeenCalledWith({ verifyToken: 'token-1', code: '123456' });
  });
});
