import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import type { User } from '@core/models/auth.models';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import type { NavSection } from '../app-shell.models';
import { AppSidebarFlyoutComponent } from './app-sidebar-flyout.component';

const WORKSPACE: NavSection = {
  id: 'workspace',
  titleKey: 'nav.section.workspace',
  items: [
    { id: 'tasks', route: '/tasks', labelKey: 'nav.tasks', icon: 'task_alt', permission: () => true, badge: () => 5 },
    { id: 'audit', route: '/audit', labelKey: 'nav.audit', icon: 'history', permission: () => false },
    {
      id: 'docs',
      label: 'Документация',
      icon: 'menu_book',
      permission: () => true,
      external: true,
      targetUrl: 'https://docs.example.test',
    },
    {
      id: 'projects',
      labelKey: 'nav.projects',
      icon: 'folder',
      permission: () => true,
      children: [
        { id: 'board', route: '/projects/board', label: 'Доска', icon: 'view_kanban', permission: () => true },
        { id: 'archive', route: '/projects/archive', label: 'Архив', icon: 'archive', permission: () => false },
      ],
    },
  ],
};

const USER = { name: 'Иван Иванов', login: 'ivan' } as User;

describe('AppSidebarFlyoutComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AppSidebarFlyoutComponent], providers: [provideRouter([])] });
  });

  function render(inputs: Record<string, unknown>) {
    const fixture = TestBed.createComponent(AppSidebarFlyoutComponent);
    fixture.componentRef.setInput('isCollapsed', true);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const menuItems = () => Array.from(element.querySelectorAll('[role="menuitem"]')) as HTMLElement[];
    return { fixture, element, menuItems };
  }

  it('opens beside the rail as a menu named by the section, with only the permitted items', () => {
    const { element, menuItems } = render({ isFlyoutVisible: true, hoveredFlyoutSection: WORKSPACE });
    const menu = element.querySelector('[role="menu"]')!;

    expect(menu.getAttribute('aria-label')).toBe(PACKAGED_RUSSIAN['nav.section.workspace']);
    expect(menuItems().map((item) => item.getAttribute('href'))).toEqual([
      '/tasks',
      'https://docs.example.test',
      '/projects/board',
    ]);
    expect(menuItems()[0].textContent).toContain(PACKAGED_RUSSIAN['nav.tasks']);
    expect(menuItems()[0].querySelector('.unread-chip')?.textContent?.trim()).toBe('5');
    expect(menuItems()[1].getAttribute('target')).toBe('_blank');
    expect(menuItems()[1].getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('stays closed when the rail is expanded, on a phone, or with no section hovered', () => {
    expect(
      render({ isFlyoutVisible: true, hoveredFlyoutSection: null }).element.querySelector('[role="menu"]'),
    ).toBeNull();
    expect(
      render({ isFlyoutVisible: true, hoveredFlyoutSection: WORKSPACE, isMobile: true }).element.querySelector(
        '[role="menu"]',
      ),
    ).toBeNull();
    expect(
      render({ isFlyoutVisible: true, hoveredFlyoutSection: WORKSPACE, isCollapsed: false }).element.querySelector(
        '[role="menu"]',
      ),
    ).toBeNull();
  });

  it('tells the shell when the pointer enters or leaves it and when an item is followed', () => {
    const { fixture, element, menuItems } = render({ isFlyoutVisible: true, hoveredFlyoutSection: WORKSPACE });
    const events: string[] = [];
    fixture.componentInstance.flyoutMouseEnter.subscribe(() => events.push('enter'));
    fixture.componentInstance.flyoutMouseLeave.subscribe(() => events.push('leave'));
    fixture.componentInstance.flyoutItemClick.subscribe(() => events.push('item'));
    const menu = element.querySelector('[role="menu"]')!;

    menu.dispatchEvent(new MouseEvent('mouseenter'));
    menuItems()[2].click();
    menu.dispatchEvent(new MouseEvent('mouseleave'));

    expect(events).toEqual(['enter', 'item', 'leave']);
  });

  it('shows the profile menu with the person, their pages and a logout', () => {
    const { fixture, element, menuItems } = render({ isProfileFlyoutVisible: true, currentUser: USER });
    const events: string[] = [];
    fixture.componentInstance.profileFlyoutClick.subscribe(() => events.push('page'));
    fixture.componentInstance.logout.subscribe(() => events.push('logout'));

    expect(element.querySelector('.profile-flyout')?.textContent).toContain('Иван Иванов');
    expect(element.querySelector('.profile-flyout')?.textContent).toContain('@ivan');
    expect(menuItems().map((item) => item.getAttribute('href'))).toEqual([
      '/iam/profile',
      '/exports',
      '/settings',
      null,
    ]);

    menuItems()[1].click();
    menuItems()[3].click();
    expect(events).toEqual(['page', 'logout']);
    expect(menuItems()[3].textContent).toContain(PACKAGED_RUSSIAN['layout.app_shell.sign_out']);
  });
});
