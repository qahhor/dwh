import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NotificationsHeaderComponent } from './notifications-header.component';

interface State {
  totalCount: number;
  unreadCount: number;
  isLoading: boolean;
  hasPendingReads: boolean;
  isMarkingAll: boolean;
}

const IDLE: State = { totalCount: 14, unreadCount: 3, isLoading: false, hasPendingReads: false, isMarkingAll: false };

function render(state: Partial<State> = {}) {
  const fixture = TestBed.createComponent(NotificationsHeaderComponent);
  for (const [name, value] of Object.entries({ ...IDLE, ...state })) fixture.componentRef.setInput(name, value);
  const asked: string[] = [];
  const component = fixture.componentInstance;
  component.refresh.subscribe(() => asked.push('refresh'));
  component.markAllRead.subscribe(() => asked.push('mark-all'));
  component.openPreferences.subscribe(() => asked.push('preferences'));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const button = (text: string) =>
    (Array.from(host.querySelectorAll('button')) as HTMLButtonElement[]).find((candidate) =>
      candidate.textContent?.includes(text),
    )!;
  return { host, button, asked };
}

describe('NotificationsHeaderComponent', () => {
  it('titles the screen and shows how many notifications there are', () => {
    const { host } = render();

    expect(host.querySelector('h1, h2')?.textContent).toContain('Центр уведомлений');
    expect(host.querySelector('.count-badge')?.textContent?.trim()).toBe('14');
  });

  it('asks to refresh and to open the preferences from named buttons', () => {
    const { button, asked } = render();

    expect(button('Обновить').getAttribute('aria-label')).toBe('Обновить');
    expect(button('Настройки уведомлений').getAttribute('aria-label')).toBe('Настройки уведомлений');
    button('Обновить').click();
    button('Настройки уведомлений').click();

    expect(asked).toEqual(['refresh', 'preferences']);
  });

  it('asks to mark everything read while there are unread notifications', () => {
    const { button, asked } = render();

    expect(button('Прочитать все').disabled).toBe(false);
    button('Прочитать все').click();

    expect(asked).toEqual(['mark-all']);
  });

  it('locks marking everything read with nothing unread, with single reads pending, or while it runs', () => {
    expect(render({ unreadCount: 0 }).button('Прочитать все').disabled).toBe(true);
    expect(render({ hasPendingReads: true }).button('Прочитать все').disabled).toBe(true);
    expect(render({ isMarkingAll: true }).button('Прочитать все').disabled).toBe(true);
  });
});
