import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
import { ApiToken, TokenExpirationOption } from '../profile.models';
import { ProfileTokensCardComponent } from './profile-tokens-card.component';

describe('ProfileTokensCardComponent', () => {
  const deploy: ApiToken = {
    id: 1,
    userId: 7,
    name: 'CI deploy',
    tokenPrefix: 'smt_ab12',
    createdAt: '2026-09-01T10:00:00Z',
    expiresAt: '2026-12-01T10:00:00Z',
  };
  const sync: ApiToken = {
    id: 2,
    userId: 7,
    name: 'Kafka sync',
    tokenPrefix: 'smt_cd34',
    createdAt: '2026-09-02T10:00:00Z',
  };
  const lifetimes: TokenExpirationOption[] = [
    { value: '30', labelKey: 'iam.profile.expiry_30_days' },
    { value: '90', labelKey: 'iam.profile.expiry_90_days' },
    { value: 'never', labelKey: 'iam.profile.no_expiry' },
  ];

  function setup(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(ProfileTokensCardComponent);
    fixture.componentRef.setInput('tokens', [deploy, sync]);
    fixture.componentRef.setInput('tokenExpirationOptions', lifetimes);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    const component = fixture.componentInstance;
    const asked = {
      open: vi.fn(),
      close: vi.fn(),
      create: vi.fn(),
      name: vi.fn(),
      expiration: vi.fn(),
      closeSecret: vi.fn(),
      copy: vi.fn(),
      revoke: vi.fn(),
    };
    component.openCreateTokenModal.subscribe(asked.open);
    component.closeCreateTokenModal.subscribe(asked.close);
    component.createTokenSubmit.subscribe(asked.create);
    component.nameChange.subscribe(asked.name);
    component.expirationChange.subscribe(asked.expiration);
    component.closeSecretModal.subscribe(asked.closeSecret);
    component.copySecret.subscribe(asked.copy);
    component.requestRevoke.subscribe(asked.revoke);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const screen = inScreen(host);
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text)!;
    return { fixture, host, screen, byText, asked };
  }

  it('lists the tokens by name and prefix, with no end date shown as never', () => {
    const { host } = setup();

    expect(host.querySelector('.badge-count')?.textContent?.trim()).toBe('2');
    const rows = Array.from(host.querySelectorAll('.smt-data-row')) as HTMLElement[];
    expect(rows[0].textContent).toContain('CI deploy');
    expect(rows[0].textContent).toContain('smt_ab12...');
    expect(rows[0].textContent).toContain('01.12.2026');
    expect(rows[1].textContent).toContain('Бессрочно');
  });

  it('asks the page to issue a token and to revoke one by its named button', () => {
    const { host, byText, asked } = setup();

    byText('Выпустить токен').click();
    (host.querySelector('button[aria-label="Отозвать API-токен Kafka sync"]') as HTMLButtonElement).click();

    expect(asked.open).toHaveBeenCalledTimes(1);
    expect(asked.revoke).toHaveBeenCalledWith(sync);
  });

  it('says when there are no tokens', () => {
    const { host } = setup({ tokens: [] });

    expect(host.querySelector('.empty-cell')?.textContent?.trim()).toBe('Нет созданных API токенов');
  });

  it('passes the name and the chosen lifetime of a new token to the page', () => {
    const { fixture, screen, asked } = setup({ isCreateTokenModalOpen: true, selectedTokenExpiration: '90' });
    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Выпуск нового API Токена');
    const radios = Array.from(screen.querySelectorAll('[role="radio"]')) as HTMLElement[];
    expect(radios.map((radio) => radio.textContent?.trim())).toEqual(['30 дней', '90 дней', 'Бессрочно']);
    expect(radios.map((radio) => radio.getAttribute('aria-checked'))).toEqual(['false', 'true', 'false']);

    const name = screen.querySelector('#profile-token-name') as HTMLInputElement;
    name.value = 'Nightly export';
    name.dispatchEvent(new Event('input'));
    radios[2].click();
    fixture.detectChanges();

    expect(asked.name).toHaveBeenLastCalledWith('Nightly export');
    expect(asked.expiration).toHaveBeenCalledWith('never');
  });

  it('asks for a name only after an attempt without one, and submits or cancels on request', () => {
    const fresh = setup({ isCreateTokenModalOpen: true });
    expect(fresh.screen.querySelector('#profile-token-name-error')).toBeNull();
    fresh.byText('Сгенерировать').click();
    fresh.byText('Отмена').click();
    expect(fresh.asked.create).toHaveBeenCalledTimes(1);
    expect(fresh.asked.close).toHaveBeenCalledTimes(1);
    fresh.fixture.destroy();

    const tried = setup({ isCreateTokenModalOpen: true, isTokenSubmitted: true, newTokenName: '  ' });
    expect(tried.screen.querySelector('#profile-token-name-error').textContent).toContain(
      'Введите название API-токена',
    );
    expect(tried.screen.querySelector('#profile-token-name').getAttribute('aria-describedby')).toBe(
      'profile-token-name-error',
    );
  });

  it('shows the new secret once, with a copy button that says when it has copied', () => {
    const { fixture, screen, byText, asked } = setup({
      isTokenSecretModalOpen: true,
      createdTokenSecret: 'smt_secret',
    });
    expect(screen.querySelector('.token-secret-box code').textContent).toBe('smt_secret');

    byText('Скопировать').click();
    fixture.componentRef.setInput('copiedSecret', true);
    fixture.detectChanges();
    expect(byText('Скопировано!')).toBeTruthy();
    byText('Я сохранил токен').click();

    expect(asked.copy).toHaveBeenCalledTimes(1);
    expect(asked.closeSecret).toHaveBeenCalledTimes(1);
  });
});
