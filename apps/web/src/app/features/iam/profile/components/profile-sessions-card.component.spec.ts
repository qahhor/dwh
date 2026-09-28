import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { buttonText } from '@testing/button-text';
import { UserSession } from '../profile.models';
import { ProfileSessionsCardComponent } from './profile-sessions-card.component';

describe('ProfileSessionsCardComponent', () => {
  const session = (id: number, ip: string, extra: Partial<UserSession> = {}): UserSession => ({
    id,
    userId: 7,
    ip,
    userAgent: 'Mozilla/5.0',
    deviceInfo: '',
    createdAt: '2026-09-01T10:00:00Z',
    lastSeenAt: '2026-09-28T10:00:00Z',
    ...extra,
  });
  const current = session(1, '10.0.0.1', { current: true, deviceInfo: 'Chrome on Windows' });
  const phone = session(2, '10.0.0.2', { userAgent: '' });

  function setup(sessions: UserSession[] = [current, phone], isLoadingSessions = false) {
    const fixture = TestBed.createComponent(ProfileSessionsCardComponent);
    fixture.componentRef.setInput('sessions', sessions);
    fixture.componentRef.setInput('isLoadingSessions', isLoadingSessions);
    const component = fixture.componentInstance;
    const asked = { load: vi.fn(), one: vi.fn(), others: vi.fn() };
    component.loadSessions.subscribe(asked.load);
    component.terminateSession.subscribe(asked.one);
    component.terminateOtherSessions.subscribe(asked.others);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const rows = () => Array.from(host.querySelectorAll('.smt-data-row')) as HTMLElement[];
    const byText = (text: string) =>
      (Array.from(host.querySelectorAll('button')) as HTMLButtonElement[]).find((item) => buttonText(item) === text) ??
      null;
    return { host, rows, byText, asked };
  }

  it('lists the sessions, marks this one and names an unknown device', () => {
    const { host, rows } = setup();

    expect(host.querySelector('.badge-count')?.textContent?.trim()).toBe('2');
    expect(rows()[0].textContent).toContain('Текущая сессия');
    expect(rows()[0].textContent).toContain('Chrome on Windows');
    expect(rows()[1].textContent).toContain('Неизвестное устройство');
  });

  it('offers to end another session by its named button, but not this one', () => {
    const { host, rows, asked } = setup();

    expect(rows()[0].querySelector('.current-session-label')?.textContent?.trim()).toBe('Текущая');
    expect(rows()[0].querySelector('button')).toBeNull();
    (host.querySelector('button[aria-label="Завершить сессию с IP 10.0.0.2"]') as HTMLButtonElement).click();

    expect(asked.one).toHaveBeenCalledWith(phone);
  });

  it('offers to end the other sessions only when there are others', () => {
    const many = setup();
    many.byText('Завершить другие сессии')!.click();
    expect(many.asked.others).toHaveBeenCalledTimes(1);

    const alone = setup([current]);
    expect(alone.byText('Завершить другие сессии')).toBeNull();
  });

  it('reloads on request and says when there are no sessions', () => {
    const { host, byText, asked } = setup([]);

    byText('Обновить')!.click();

    expect(asked.load).toHaveBeenCalledTimes(1);
    expect(host.querySelector('.empty-cell')?.textContent?.trim()).toBe('Нет активных сессий');
  });

  it('marks the list busy while it loads', () => {
    const { host } = setup([], true);

    expect(host.querySelector('[aria-label="Активные сессии"][aria-busy="true"]')).not.toBeNull();
    expect(host.querySelector('.empty-cell')).toBeNull();
  });
});
