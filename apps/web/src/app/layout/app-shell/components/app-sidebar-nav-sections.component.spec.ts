import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import type { NavSection } from '../app-shell.models';
import { AppSidebarNavSectionsComponent } from './app-sidebar-nav-sections.component';

const WORKSPACE: NavSection = {
  id: 'workspace',
  titleKey: 'nav.section.workspace',
  items: [
    { id: 'tasks', route: '/tasks', labelKey: 'nav.tasks', icon: 'task_alt', permission: () => true, badge: () => 2 },
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
      id: 'upl',
      labelKey: 'nav.projects',
      icon: 'upload',
      permission: () => true,
      children: [
        { id: 'upl-sources', route: '/upl/sources', label: 'Источники', icon: 'source', permission: () => true },
        { id: 'upl-hidden', route: '/upl/hidden', label: 'Скрытое', icon: 'lock', permission: () => false },
      ],
    },
  ],
};

const EMPTY: NavSection = { id: 'system', titleKey: 'nav.section.iam', items: [] };

describe('AppSidebarNavSectionsComponent', () => {
  let expandedSections: Set<string>;
  let expandedSubmenus: Set<string>;

  beforeEach(() => {
    expandedSections = new Set(['workspace']);
    expandedSubmenus = new Set();
    TestBed.configureTestingModule({ imports: [AppSidebarNavSectionsComponent], providers: [provideRouter([])] });
  });

  function render() {
    const fixture = TestBed.createComponent(AppSidebarNavSectionsComponent);
    fixture.componentRef.setInput('navSections', [WORKSPACE, EMPTY]);
    fixture.componentRef.setInput('hasVisibleItems', (section: NavSection) => section.items.length > 0);
    fixture.componentRef.setInput('isSectionExpanded', (id: string) => expandedSections.has(id));
    fixture.componentRef.setInput('isSectionActive', () => true);
    fixture.componentRef.setInput('isSubmenuExpanded', (id: string) => expandedSubmenus.has(id));
    fixture.componentRef.setInput('isRouteActive', (route: string) => route === '/tasks');
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    return { fixture, element };
  }

  /** A new predicate instance, so the OnPush view sees the change as a parent would send it. */
  function redrawWith(fixture: ReturnType<typeof render>['fixture']) {
    fixture.componentRef.setInput('isSectionExpanded', (id: string) => expandedSections.has(id));
    fixture.componentRef.setInput('isSubmenuExpanded', (id: string) => expandedSubmenus.has(id));
    fixture.detectChanges();
  }

  it('gives each section with visible items a header button that controls its content', () => {
    const { fixture, element } = render();
    const toggled: string[] = [];
    fixture.componentInstance.toggleSection.subscribe(({ id }) => toggled.push(id));
    const headers = Array.from(element.querySelectorAll('.nav-section-header')) as HTMLButtonElement[];

    expect(headers).toHaveLength(1);
    expect(headers[0].textContent).toContain(PACKAGED_RUSSIAN['nav.section.workspace']);
    expect(headers[0].getAttribute('aria-expanded')).toBe('true');
    expect(headers[0].getAttribute('aria-label')).toBe(PACKAGED_RUSSIAN['layout.app_shell.collapse_section']);
    expect(element.querySelector(`#${headers[0].getAttribute('aria-controls')}`)).not.toBeNull();

    headers[0].click();
    expect(toggled).toEqual(['workspace']);
  });

  it('marks a collapsed section that holds the current page and names its header for expanding', () => {
    expandedSections.clear();
    const { element } = render();
    const header = element.querySelector('.nav-section-header')!;

    expect(header.getAttribute('aria-expanded')).toBe('false');
    expect(header.getAttribute('aria-label')).toBe(PACKAGED_RUSSIAN['layout.app_shell.expand_section']);
    expect(header.querySelector('.section-active-dot')).not.toBeNull();
  });

  it('shows only the permitted items, marks the current page and the unread count', () => {
    const { element } = render();
    const links = Array.from(element.querySelectorAll('a.nav-item')) as HTMLAnchorElement[];
    const tasks = links.find((link) => link.getAttribute('href') === '/tasks')!;

    expect(links.some((link) => link.getAttribute('href') === '/audit')).toBe(false);
    expect(tasks.getAttribute('aria-current')).toBe('page');
    expect(tasks.querySelector('.unread-chip')?.textContent?.trim()).toBe('2');
  });

  it('opens an external item in a new tab without handing it the opener', () => {
    const { element } = render();
    const docs = element.querySelector('a[href="https://docs.example.test"]')!;

    expect(docs.getAttribute('target')).toBe('_blank');
    expect(docs.getAttribute('rel')).toBe('noopener noreferrer');
    expect(docs.textContent).toContain('Документация');
  });

  it('expands a submenu on request and then lists its permitted children', () => {
    const { fixture, element } = render();
    const toggled: string[] = [];
    fixture.componentInstance.toggleSubmenu.subscribe(({ id }) => toggled.push(id));
    const parent = element.querySelector('.nav-parent-btn') as HTMLButtonElement;

    expect(parent.getAttribute('aria-expanded')).toBe('false');
    expect(element.querySelector('.nav-submenu')).toBeNull();
    parent.click();
    expect(toggled).toEqual(['upl']);

    expandedSubmenus.add('upl');
    redrawWith(fixture);
    expect(parent.getAttribute('aria-expanded')).toBe('true');
    const children = Array.from(element.querySelectorAll('.nav-submenu a')).map((link) => link.textContent?.trim());
    expect(children).toEqual([expect.stringContaining('Источники')]);
  });
});
