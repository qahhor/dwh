import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NotificationFilterTab } from '../notifications.models';
import { NotificationsTabsComponent } from './notifications-tabs.component';

function render(filterTab: NotificationFilterTab, totalCount: number, unreadCount: number) {
  const fixture = TestBed.createComponent(NotificationsTabsComponent);
  fixture.componentRef.setInput('filterTab', filterTab);
  fixture.componentRef.setInput('totalCount', totalCount);
  fixture.componentRef.setInput('unreadCount', unreadCount);
  const chosen: NotificationFilterTab[] = [];
  fixture.componentInstance.tabChange.subscribe((tab) => chosen.push(tab));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const tabs = () => Array.from(host.querySelectorAll('[role="tab"]')) as HTMLButtonElement[];
  const names = () => tabs().map((tab) => tab.textContent?.replace(/\s+/g, ' ').trim());
  return { host, tabs, names, chosen };
}

describe('NotificationsTabsComponent', () => {
  it('offers all and unread notifications with their counts in a named tab list', () => {
    const { host, tabs, names } = render('all', 14, 3);

    expect(host.querySelector('[role="tablist"]')?.getAttribute('aria-label')).toBe('Список уведомлений');
    expect(names()).toEqual(['Все 14', 'Непрочитанные 3']);
    expect(tabs().map((tab) => tab.getAttribute('aria-selected'))).toEqual(['true', 'false']);
    expect(tabs()[1].querySelector('.smt-tab-bar__count--attention')).not.toBeNull();
  });

  it('shows no unread count when everything is read', () => {
    expect(render('unread', 14, 0).names()).toEqual(['Все 14', 'Непрочитанные']);
  });

  it('asks for the other list when its tab is chosen', () => {
    const { tabs, chosen } = render('all', 14, 3);

    tabs()[1].click();

    expect(chosen).toEqual(['unread']);
  });
});
