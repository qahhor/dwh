import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NotificationItem } from '@core/models/notification.models';
import { NotificationFilterTab } from '../notifications.models';
import { NotificationsListComponent } from './notifications-list.component';

const ASSIGNED: NotificationItem = {
  id: 7,
  userId: 3,
  title: 'Новая задача',
  bodyMarkdown: 'Вам назначена задача',
  sourceModule: 'tasks',
  targetUrl: '/tasks/100',
  isRead: false,
  createdAt: '2026-08-30T09:15:00',
};
const DIGEST: NotificationItem = {
  id: 8,
  userId: 3,
  title: 'Сводка',
  isRead: true,
  createdAt: '2026-08-29T18:00:00',
};

interface State {
  paginatedItems: NotificationItem[];
  itemsCount: number;
  filteredCount: number;
  filterTab: NotificationFilterTab;
  isLoading: boolean;
  isMarkingAll: boolean;
  pendingReads: Set<number>;
  currentPage: number;
  pageSize: number;
  loadError: string | null;
}

const LOADED: State = {
  paginatedItems: [ASSIGNED, DIGEST],
  itemsCount: 2,
  filteredCount: 2,
  filterTab: 'all',
  isLoading: false,
  isMarkingAll: false,
  pendingReads: new Set(),
  currentPage: 1,
  pageSize: 25,
  loadError: null,
};
const EMPTY: Partial<State> = { paginatedItems: [], itemsCount: 0, filteredCount: 0 };

function render(state: Partial<State> = {}) {
  const fixture = TestBed.createComponent(NotificationsListComponent);
  for (const [name, value] of Object.entries({ ...LOADED, ...state })) fixture.componentRef.setInput(name, value);
  const asked: string[] = [];
  const component = fixture.componentInstance;
  component.itemClick.subscribe((item) => asked.push(`open:${item.id}`));
  component.markAsReadClick.subscribe(({ item }) => asked.push(`read:${item.id}`));
  component.retry.subscribe(() => asked.push('retry'));
  component.pageChange.subscribe((page) => asked.push(`page:${page}`));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const items = () => Array.from(host.querySelectorAll('article.notif-item')) as HTMLElement[];
  return { host, items, asked };
}

describe('NotificationsListComponent', () => {
  it('shows each notification with its title, text, time and a link hint when it leads somewhere', () => {
    const { host, items } = render();

    expect(host.querySelector('[role="region"]')?.getAttribute('aria-label')).toBe('Список уведомлений');
    expect(items()).toHaveLength(2);
    const [assigned, digest] = items();
    expect(assigned.textContent).toContain('Новая задача');
    expect(assigned.textContent).toContain('Вам назначена задача');
    expect(assigned.textContent).toContain('30.08.2026 09:15');
    expect(assigned.textContent).toContain('Перейти к объекту');
    expect(assigned.querySelector('.notif-icon-box')?.textContent?.trim()).toBe('task_alt');
    expect(digest.textContent).not.toContain('Перейти к объекту');
  });

  it('makes only a notification with a link a focusable button, and asks to open what is clicked', () => {
    const { items, asked } = render();
    const [assigned, digest] = items();

    expect(assigned.getAttribute('role')).toBe('button');
    expect(assigned.getAttribute('tabindex')).toBe('0');
    expect(digest.getAttribute('role')).toBeNull();
    expect(digest.getAttribute('tabindex')).toBeNull();
    assigned.click();

    expect(asked).toEqual(['open:7']);
  });

  it('offers a named mark-read button on unread notifications only, locked while its read is pending', () => {
    const { items, asked } = render();
    const [assigned, digest] = items();
    const markRead = assigned.querySelector('.mark-read-btn') as HTMLButtonElement;

    expect(markRead.getAttribute('aria-label')).toBe('Отметить уведомление «Новая задача» как прочитанное');
    expect(digest.querySelector('.mark-read-btn')).toBeNull();
    markRead.click();
    expect(asked).toContain('read:7');

    const pending = render({ pendingReads: new Set([7]) });
    expect((pending.items()[0].querySelector('.mark-read-btn') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows a loading status while the first page loads', () => {
    const { host, items } = render({ ...EMPTY, isLoading: true });

    expect(host.querySelector('[role="status"]')?.textContent).toContain('Загрузка уведомлений…');
    expect(items()).toHaveLength(0);
  });

  it('shows a load error in place of the list with a retry', () => {
    const { host, items, asked } = render({ loadError: 'Не удалось загрузить уведомления' });

    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Не удалось загрузить уведомления');
    expect(items()).toHaveLength(0);
    (host.querySelector('[role="alert"] button') as HTMLButtonElement).click();

    expect(asked).toEqual(['retry']);
  });

  it('says what an empty list means on each tab and hides the pages then', () => {
    const all = render(EMPTY);
    expect(all.host.textContent).toContain('У вас нет уведомлений');
    expect(all.host.querySelector('ui-pagination')).toBeNull();

    const unread = render({ ...EMPTY, filterTab: 'unread' });
    expect(unread.host.textContent).toContain('Все уведомления прочитаны');
    expect(unread.host.textContent).toContain('У вас нет непрочитанных уведомлений');
  });

  it('pages a long list and asks for the page chosen', () => {
    const { host, asked } = render({ filteredCount: 60 });

    (host.querySelector('button[aria-label="Следующая страница"]') as HTMLButtonElement).click();

    expect(asked).toEqual(['page:2']);
  });
});
