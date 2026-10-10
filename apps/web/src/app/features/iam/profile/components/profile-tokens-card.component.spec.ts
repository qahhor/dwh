import { TestBed } from '@angular/core/testing';
import { Observable, of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ToastService } from '@core/services/toast.service';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { buttonText } from '@testing/button-text';
import { inScreen } from '@testing/in-screen';
import { ProfileApi } from '../profile.api';
import { ApiToken, CreatedTokenResponse } from '../profile.models';
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

  function setup(create: () => Observable<CreatedTokenResponse> = () => of(created()), discard = true) {
    const profile = { createToken: vi.fn(create) };
    const toast = { success: vi.fn() };
    TestBed.configureTestingModule({
      providers: [
        { provide: ProfileApi, useValue: profile },
        { provide: ToastService, useValue: toast },
      ],
    });
    const confirmDialog = vi.spyOn(TestBed.inject(SMTModalService), 'confirm').mockReturnValue(of(discard));
    const fixture = TestBed.createComponent(ProfileTokensCardComponent);
    fixture.componentRef.setInput('tokens', [deploy, sync]);
    const component = fixture.componentInstance;
    const asked = { changed: vi.fn(), revoke: vi.fn() };
    component.changed.subscribe(asked.changed);
    component.requestRevoke.subscribe(asked.revoke);
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
    const byText = (text: string) =>
      (Array.from(screen.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text)!;
    const typeName = async (value: string) => {
      const name = screen.querySelector('#profile-token-name') as HTMLInputElement;
      name.value = value;
      name.dispatchEvent(new Event('input'));
      await settle();
      return name;
    };
    const open = async () => {
      byText('Выпустить токен').click();
      await settle();
    };
    return { fixture, component, host, screen, byText, typeName, open, settle, asked, profile, toast, confirmDialog };
  }

  function created(): CreatedTokenResponse {
    return { record: deploy, rawSecretToken: 'smt_secret' } as CreatedTokenResponse;
  }

  it('lists the tokens by name and prefix, with no end date shown as never', () => {
    const { host } = setup();

    const rows = Array.from(host.querySelectorAll('.smt-data-row')) as HTMLElement[];
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('CI deploy');
    expect(rows[0].textContent).toContain('smt_ab12...');
    expect(rows[1].textContent).toContain('Бессрочно');
  });

  it('asks the page to revoke a token by its named button', () => {
    const { host, asked } = setup();

    (host.querySelector('button[aria-label="Отозвать API-токен Kafka sync"]') as HTMLButtonElement).click();

    expect(asked.revoke).toHaveBeenCalledWith(sync);
  });

  it('says when there are no tokens', () => {
    const { fixture, host } = setup();
    fixture.componentRef.setInput('tokens', []);
    fixture.detectChanges();

    expect(host.querySelector('.empty-cell')?.textContent?.trim()).toBe('Нет созданных API токенов');
  });

  it('issues a token with the typed name and the chosen lifetime and shows its secret once', async () => {
    const { component, screen, open, typeName, byText, settle, profile, asked } = setup();
    await open();
    expect(screen.querySelector('.smt-modal__title').textContent).toBe('Выпуск нового API Токена');
    const radios = Array.from(screen.querySelectorAll('[role="radio"]')) as HTMLElement[];
    expect(radios.map((radio) => radio.textContent?.trim())).toEqual(['30 дней', '90 дней', '1 год', 'Бессрочно']);
    expect(radios.map((radio) => radio.getAttribute('aria-checked'))).toEqual(['false', 'true', 'false', 'false']);

    await typeName('  Nightly export ');
    radios[3].click();
    await settle();
    byText('Сгенерировать').click();
    await settle();

    expect(profile.createToken).toHaveBeenCalledWith('Nightly export', null);
    expect(component.isCreateTokenModalOpen()).toBe(false);
    expect(screen.querySelector('.token-secret-box code').textContent).toBe('smt_secret');
    expect(asked.changed).toHaveBeenCalledTimes(1);
  });

  it('marks the name required, explains an empty one under the field and focuses it', async () => {
    const { screen, open, byText, settle, profile } = setup();
    await open();
    const name = screen.querySelector('#profile-token-name') as HTMLInputElement;
    expect(name.getAttribute('aria-required')).toBe('true');
    expect(name.hasAttribute('cdkFocusInitial')).toBe(true);
    expect(screen.querySelector('.smt-control__error')).toBeNull();

    byText('Сгенерировать').click();
    await settle();
    await settle();

    expect(profile.createToken).not.toHaveBeenCalled();
    expect(screen.querySelector('.smt-control__error').textContent).toContain('Введите название API-токена');
    expect(name.getAttribute('aria-invalid')).toBe('true');
    expect(document.activeElement).toBe(name);
  });

  it('issues one token while the first request runs, and shows a refusal in the dialog', async () => {
    const pending = new Subject<CreatedTokenResponse>();
    const { screen, open, typeName, byText, settle, profile } = setup(() => pending);
    await open();
    await typeName('Nightly');

    byText('Сгенерировать').click();
    byText('Сгенерировать').click();
    expect(profile.createToken).toHaveBeenCalledTimes(1);

    pending.error({ status: 500, detail: 'Сервер недоступен' });
    await settle();
    expect(screen.querySelector('[role="dialog"] [role="alert"]')?.textContent).toContain('Сервер недоступен');
  });

  it('puts the server word on the name under the field', async () => {
    const { screen, open, typeName, byText, settle } = setup(() =>
      throwError(() => ({ status: 422, errors: [{ field: 'name', code: 'X', message: 'Имя занято' }] })),
    );
    await open();
    await typeName('Nightly');

    byText('Сгенерировать').click();
    await settle();

    expect(screen.querySelector('.smt-control__error').textContent).toContain('Имя занято');
  });

  it('asks before a typed token is lost and closes an untouched one at once', async () => {
    const { component, open, typeName, byText, settle, confirmDialog } = setup(undefined, false);
    await open();
    byText('Отмена').click();
    expect(confirmDialog).not.toHaveBeenCalled();
    expect(component.isCreateTokenModalOpen()).toBe(false);

    await open();
    await typeName('Nightly');
    byText('Отмена').click();
    await settle();
    expect(confirmDialog).toHaveBeenCalledTimes(1);
    expect(component.isCreateTokenModalOpen()).toBe(true);
  });

  it('shows the new secret with a copy button that says when it has copied', async () => {
    const writeText = vi.fn(() => Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const { fixture, component, byText, settle, toast } = setup();
    component.createdTokenSecret.set('smt_secret');
    component.isTokenSecretModalOpen.set(true);
    await settle();

    byText('Скопировать').click();
    fixture.detectChanges();
    expect(writeText).toHaveBeenCalledWith('smt_secret');
    expect(toast.success).toHaveBeenCalled();
    expect(byText('Скопировано!')).toBeTruthy();
    byText('Я сохранил токен').click();
    await settle();

    expect(component.isTokenSecretModalOpen()).toBe(false);
    expect(component.createdTokenSecret()).toBe('');
  });
});
