import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { User } from '@core/models/auth.models';
import { I18nService } from '@core/services/i18n.service';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import type { NavSection } from '../app-shell.models';
import { AppSidebarComponent } from './app-sidebar.component';

const WORKSPACE: NavSection = {
  id: 'workspace',
  titleKey: 'nav.section.workspace',
  items: [{ id: 'tasks', route: '/tasks', labelKey: 'nav.tasks', icon: 'task_alt', permission: () => true }],
};
const IAM: NavSection = {
  id: 'iam',
  titleKey: 'nav.section.iam',
  items: [{ id: 'users', route: '/iam/users', labelKey: 'nav.users', icon: 'group', permission: () => true }],
};
const HIDDEN: NavSection = { id: 'hidden', titleKey: 'nav.section.administration', items: [] };

const USER = { name: 'Иван Иванов', login: 'ivan' } as User;

describe('AppSidebarComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [AppSidebarComponent], providers: [provideRouter([])] });
  });

  function render(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(AppSidebarComponent);
    const ref = fixture.componentRef;
    ref.setInput('navSections', [WORKSPACE, IAM, HIDDEN]);
    ref.setInput('isSectionActive', (section: NavSection) => section.id === 'workspace');
    ref.setInput('isRouteActive', (route: string) => route === '/iam/profile');
    ref.setInput('getSectionIcon', (id: string) => `icon-${id}`);
    ref.setInput('hasVisibleItems', (section: NavSection) => section.items.length > 0);
    ref.setInput('isSectionExpanded', () => true);
    ref.setInput('isSubmenuExpanded', () => false);
    ref.setInput('getSectionBadge', (section: NavSection) => (section.id === 'workspace' ? 3 : 0));
    ref.setInput('currentUser', USER);
    for (const [name, value] of Object.entries(inputs)) ref.setInput(name, value);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const aside = () => element.querySelector('aside.sidebar') as HTMLElement;
    return { fixture, element, aside };
  }

  function searchMenu(view: ReturnType<typeof render>, text: string) {
    const input = view.element.querySelector('#sidebar-navigation-search') as HTMLInputElement;
    input.value = text;
    input.dispatchEvent(new Event('input'));
    view.fixture.detectChanges();
  }

  it('searches translated names without changing source sections, routes or the saved expansion state', () => {
    const view = render({ isSectionExpanded: (id: string) => id === 'iam' });
    const sectionToggle = vi.fn();
    view.fixture.componentInstance.toggleSection.subscribe(sectionToggle);
    searchMenu(view, '  зАдА  ');

    expect(view.element.querySelectorAll('nav a.nav-item')).toHaveLength(1);
    expect(view.element.querySelector('nav a')?.getAttribute('href')).toBe('/tasks');
    const header = () => view.element.querySelector('.nav-section-header') as HTMLButtonElement;
    expect(header().getAttribute('aria-expanded')).toBe('true');
    expect(view.element.querySelector('#section-content-workspace')?.hasAttribute('inert')).toBe(false);
    expect(view.element.querySelector('.navigation-search-status')?.getAttribute('aria-live')).toBe('polite');

    header().click();
    view.fixture.detectChanges();
    expect(header().getAttribute('aria-expanded')).toBe('false');
    expect(sectionToggle).not.toHaveBeenCalled();
    searchMenu(view, 'задач');
    expect(header().getAttribute('aria-expanded')).toBe('true');
    searchMenu(view, '');
    const headers = [...view.element.querySelectorAll('.nav-section-header')];
    expect(headers.map((item) => item.getAttribute('aria-expanded'))).toEqual(['false', 'true']);
    expect(view.element.querySelectorAll('nav a.nav-item')).toHaveLength(2);
    expect(WORKSPACE.items).toHaveLength(1);
    expect(WORKSPACE.items[0].route).toBe('/tasks');
  });

  it('finds permitted nested labels and expands their ancestors without changing submenu preferences', () => {
    const sections: NavSection[] = [
      {
        id: 'data',
        titleKey: 'nav.section.workspace',
        items: [
          {
            id: 'imports',
            label: 'Импорт',
            icon: 'upload',
            permission: () => true,
            children: [
              { id: 'sources', label: 'Источники', route: '/upl/sources', icon: 'source', permission: () => true },
              { id: 'hidden', label: 'Источники закрытые', route: '/hidden', icon: 'lock', permission: () => false },
            ],
          },
        ],
      },
    ];
    const view = render({ navSections: sections, isSectionExpanded: () => false, isSubmenuExpanded: () => false });
    const submenuToggle = vi.fn();
    view.fixture.componentInstance.toggleSubmenu.subscribe(submenuToggle);
    searchMenu(view, 'источ');
    expect([...view.element.querySelectorAll('nav a')].map((link) => link.getAttribute('href'))).toEqual([
      '/upl/sources',
    ]);
    const parent = () => view.element.querySelector('.nav-parent-btn') as HTMLButtonElement;
    expect(parent().getAttribute('aria-expanded')).toBe('true');
    parent().click();
    view.fixture.detectChanges();
    expect(parent().getAttribute('aria-expanded')).toBe('false');
    expect(submenuToggle).not.toHaveBeenCalled();
    searchMenu(view, 'Импорт');
    expect(view.element.querySelector('nav a')?.getAttribute('href')).toBe('/upl/sources');
    searchMenu(view, '');
    expect(parent().getAttribute('aria-expanded')).toBe('false');
    expect(sections[0].items[0].children).toHaveLength(2);
  });

  it('keeps permission checks reactive and never reveals a denied parent or child through search', () => {
    const allowed = signal(true);
    const sections: NavSection[] = [
      {
        id: 'secure',
        titleKey: 'nav.section.workspace',
        items: [
          { id: 'private', label: 'Каталог закрытый', route: '/private', icon: 'lock', permission: () => false },
          { id: 'allowed', label: 'Каталог', route: '/catalog', icon: 'list', permission: () => allowed() },
          {
            id: 'denied-parent',
            label: 'Каталог группы',
            icon: 'folder',
            permission: () => false,
            children: [
              {
                id: 'allowed-child',
                label: 'Каталог вложенный',
                route: '/denied-child',
                icon: 'list',
                permission: () => true,
              },
            ],
          },
        ],
      },
    ];
    const view = render({ navSections: sections });
    searchMenu(view, 'каталог');
    expect([...view.element.querySelectorAll('nav a')].map((link) => link.getAttribute('href'))).toEqual(['/catalog']);
    allowed.set(false);
    view.fixture.detectChanges();
    expect(view.element.querySelector('nav a')).toBeNull();
    expect(view.element.querySelector('.navigation-search-empty')).not.toBeNull();
    expect(view.element.querySelector('.nav-section-header')).toBeNull();
  });

  it('matches custom names and section titles while preserving external link safeguards', () => {
    const view = render({
      navSections: [
        {
          ...WORKSPACE,
          items: [
            ...WORKSPACE.items,
            {
              id: 'docs',
              label: 'Руководство',
              icon: 'help',
              permission: () => true,
              external: true,
              targetUrl: 'https://docs.example.test/manual',
            },
          ],
        },
      ],
    });
    searchMenu(view, 'руковод');
    const link = view.element.querySelector('nav a')!;
    expect(link.getAttribute('href')).toBe('https://docs.example.test/manual');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    searchMenu(view, PACKAGED_RUSSIAN['nav.section.workspace']);
    expect(view.element.querySelectorAll('nav a')).toHaveLength(2);
  });

  it('updates search matches when the translated menu label changes', () => {
    const translatedLabel = signal('Задачи');
    const i18n = TestBed.inject(I18nService);
    const translate = i18n.translate.bind(i18n);
    vi.spyOn(i18n, 'translate').mockImplementation((key, params) =>
      key === 'nav.tasks' ? translatedLabel() : translate(key, params),
    );
    const view = render();
    searchMenu(view, 'tasks');
    expect(view.element.querySelector('nav a')).toBeNull();
    translatedLabel.set('Tasks');
    view.fixture.detectChanges();
    expect(view.element.querySelector('nav a')?.getAttribute('href')).toBe('/tasks');
  });

  it('shows menu search only when expanded and keeps mobile navigation open while searching or clearing', () => {
    const view = render({ isCollapsed: true });
    expect(view.element.querySelector('#sidebar-navigation-search')).toBeNull();
    view.fixture.componentRef.setInput('isMobile', true);
    view.fixture.componentRef.setInput('isMobileMenuOpen', true);
    view.fixture.detectChanges();
    const navClicks = vi.fn();
    view.fixture.componentInstance.navClick.subscribe(navClicks);
    const input = view.element.querySelector('#sidebar-navigation-search') as HTMLInputElement;
    expect(input).not.toBeNull();
    input.click();
    searchMenu(view, 'нет такого раздела');
    const clear = view.element.querySelector('.sidebar-navigation-search .smt-input__action') as HTMLButtonElement;
    clear.click();
    view.fixture.detectChanges();
    expect(input.value).toBe('');
    expect(view.element.querySelectorAll('nav a.nav-item')).toHaveLength(2);
    expect(navClicks).not.toHaveBeenCalled();
  });

  it('collapses from a named toggle and shows the full sections while expanded', () => {
    const { fixture, element } = render();
    const toggles: unknown[] = [];
    fixture.componentInstance.toggleSidebar.subscribe(() => toggles.push(true));
    const toggle = element.querySelector('.toggle-btn') as HTMLButtonElement;

    expect(element.querySelector('nav')?.getAttribute('aria-label')).toBe(
      PACKAGED_RUSSIAN['layout.app_shell.main_navigation'],
    );
    expect(toggle.getAttribute('aria-label')).toBe(PACKAGED_RUSSIAN['layout.app_shell.collapse_navigation']);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(element.querySelector('app-sidebar-nav-sections')).not.toBeNull();
    expect(element.querySelector('.rail-category-list')).toBeNull();
    expect(element.querySelectorAll('.nav-tooltip')).toHaveLength(0);

    toggle.click();
    expect(toggles).toHaveLength(1);
  });

  it('shows a rail of named section buttons when collapsed and reports hover and clicks', () => {
    const { fixture, element } = render({ isCollapsed: true });
    const events: string[] = [];
    fixture.componentInstance.categoryClick.subscribe(({ section }) => events.push(`click:${section.id}`));
    fixture.componentInstance.categoryMouseEnter.subscribe(({ section }) => events.push(`enter:${section.id}`));
    fixture.componentInstance.categoryMouseLeave.subscribe(() => events.push('leave'));
    const rail = Array.from(element.querySelectorAll('.rail-category-btn')) as HTMLButtonElement[];

    expect(element.querySelector('.toggle-btn')?.getAttribute('aria-label')).toBe(
      PACKAGED_RUSSIAN['layout.app_shell.expand_navigation'],
    );
    expect(element.querySelector('app-sidebar-nav-sections')).toBeNull();
    expect(rail.map((button) => button.getAttribute('aria-label'))).toEqual([
      PACKAGED_RUSSIAN['nav.section.workspace'],
      PACKAGED_RUSSIAN['nav.section.iam'],
    ]);
    expect(rail[0].querySelector('.rail-category-badge')?.textContent?.trim()).toBe('3');
    expect(rail[1].querySelector('.rail-category-badge')).toBeNull();
    expect(rail[1].querySelector('.nav-tooltip[role="tooltip"]')?.textContent?.trim()).toBe(
      PACKAGED_RUSSIAN['nav.section.iam'],
    );
    expect(element.querySelector('.user-profile-btn .nav-tooltip')?.textContent?.trim()).toBe('Иван Иванов');

    rail[1].dispatchEvent(new MouseEvent('mouseenter'));
    rail[1].click();
    rail[1].dispatchEvent(new MouseEvent('mouseleave'));
    expect(events).toEqual(['enter:iam', 'click:iam', 'leave']);
  });

  it('is a modal dialog on a phone while open, closed by its button or the backdrop', () => {
    // A rail folded on the desktop still opens as the full drawer on a phone.
    const { fixture, element, aside } = render({
      isMobile: true,
      isMobileMenuOpen: true,
      isCollapsed: true,
      sidebarId: 'shell-nav',
    });
    const closes: boolean[] = [];
    fixture.componentInstance.closeMobileMenu.subscribe((restoreFocus) => closes.push(restoreFocus));

    expect(aside().id).toBe('shell-nav');
    expect(aside().getAttribute('role')).toBe('dialog');
    expect(aside().getAttribute('aria-modal')).toBe('true');
    expect(element.querySelector('.toggle-btn')).toBeNull();
    expect(element.querySelector('app-sidebar-nav-sections')).not.toBeNull();

    (element.querySelector(`button[aria-label="${PACKAGED_RUSSIAN['common.close']}"]`) as HTMLButtonElement).click();
    (element.querySelector('.mobile-drawer-backdrop') as HTMLElement).click();
    expect(closes).toEqual([true, true]);
  });

  it('is hidden and inert on a phone while closed', () => {
    const { element, aside } = render({ isMobile: true, isMobileMenuOpen: false });

    expect(aside().getAttribute('aria-hidden')).toBe('true');
    expect(aside().hasAttribute('inert')).toBe(true);
    expect(aside().getAttribute('role')).toBeNull();
    expect(element.querySelector('.mobile-drawer-backdrop')).toBeNull();
  });

  it('passes the current page to the compact flyout', () => {
    const { element } = render({
      isCollapsed: true,
      isFlyoutVisible: true,
      hoveredFlyoutSection: WORKSPACE,
      activeItemId: 'tasks',
    });
    expect(element.querySelector('.flyout-item[aria-current="page"]')?.getAttribute('href')).toBe('/tasks');
  });

  it('refreshes an open flyout when the navigation catalog changes', () => {
    const { fixture, element } = render({ isCollapsed: true, isFlyoutVisible: true, hoveredFlyoutSection: WORKSPACE });
    expect(element.querySelector('.flyout-item[href="/tasks"]')).not.toBeNull();
    fixture.componentRef.setInput('navSections', [{ ...WORKSPACE, items: [] }, IAM]);
    fixture.detectChanges();
    expect(element.querySelector('.flyout-item[href="/tasks"]')).toBeNull();
  });

  it('links to the profile with the person, marks it current and reports followed links', () => {
    const { fixture, element } = render();
    const clicks: unknown[] = [];
    fixture.componentInstance.navClick.subscribe(() => clicks.push(true));
    const profile = element.querySelector('a.user-profile-btn') as HTMLAnchorElement;

    expect(profile.getAttribute('href')).toBe('/iam/profile');
    expect(profile.getAttribute('aria-current')).toBe('page');
    expect(profile.textContent).toContain('Иван Иванов');
    expect(profile.textContent).toContain('@ivan');

    profile.click();
    (element.querySelector('nav a[href="/tasks"]') as HTMLAnchorElement).click();
    expect(clicks).toHaveLength(2);
  });
});
