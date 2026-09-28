import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import type { User } from '@core/models/auth.models';
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

  it('collapses from a named toggle and shows the full sections while expanded', () => {
    const { fixture, element } = render();
    const toggles: unknown[] = [];
    fixture.componentInstance.toggleSidebar.subscribe(() => toggles.push(true));
    const toggle = element.querySelector('.toggle-btn') as HTMLButtonElement;

    expect(element.querySelector('nav')?.getAttribute('aria-label')).toBe(
      PACKAGED_RUSSIAN['layout.app_shell.osnovnaya_navigaciya'],
    );
    expect(toggle.getAttribute('aria-label')).toBe(PACKAGED_RUSSIAN['layout.app_shell.collapse_navigation']);
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(element.querySelector('app-sidebar-nav-sections')).not.toBeNull();
    expect(element.querySelector('.rail-category-list')).toBeNull();

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

    rail[1].dispatchEvent(new MouseEvent('mouseenter'));
    rail[1].click();
    rail[1].dispatchEvent(new MouseEvent('mouseleave'));
    expect(events).toEqual(['enter:iam', 'click:iam', 'leave']);
  });

  it('is a modal dialog on a phone while open, closed by its button or the backdrop', () => {
    const { fixture, element, aside } = render({ isMobile: true, isMobileMenuOpen: true, sidebarId: 'shell-nav' });
    const closes: boolean[] = [];
    fixture.componentInstance.closeMobileMenu.subscribe((restoreFocus) => closes.push(restoreFocus));

    expect(aside().id).toBe('shell-nav');
    expect(aside().getAttribute('role')).toBe('dialog');
    expect(aside().getAttribute('aria-modal')).toBe('true');
    expect(element.querySelector('.toggle-btn')).toBeNull();

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

  it('links to the profile with the person, marks it current and reports followed links', () => {
    const { fixture, element } = render();
    const clicks: unknown[] = [];
    fixture.componentInstance.onNavClick.subscribe(() => clicks.push(true));
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
