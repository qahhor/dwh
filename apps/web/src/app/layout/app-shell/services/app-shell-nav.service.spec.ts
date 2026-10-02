import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CustomNavigationItem, EntityMenuItem } from '@core/models/navigation.models';
import { InstalledModule, ModuleService } from '@core/services/module.service';
import { NavigationService } from '@core/services/navigation.service';
import { NotificationService } from '@core/services/notification.service';
import { PermissionService } from '@core/services/permission.service';
import { COLLAPSED_SECTIONS_KEY, COLLAPSED_STATE_KEY, NavSection } from '../app-shell.models';
import { AppShellNavService } from './app-shell-nav.service';

const NOTES_ENTITY: EntityMenuItem = {
  code: 'ms.notes',
  form: 'notes',
  route: '/notes',
  labelKey: 'nav.notes',
  icon: 'description',
  section: 'workspace',
  order: 30,
  module: 'notes',
};

/** The user accounts, a declared entity of the iam section without a route of its own (ADR-0032 8). */
const USERS_ENTITY: EntityMenuItem = {
  code: 'md.users',
  form: 'md.users',
  route: '/e/md.users',
  labelKey: 'nav.users',
  icon: 'people',
  section: 'iam',
  order: 10,
  module: null,
};

const SALES_REPORT: CustomNavigationItem = {
  id: 1,
  code: 'superset-sales',
  title: 'Отчет по продажам (Superset)',
  sectionId: 'custom-reports',
  icon: 'bar-chart',
  targetType: 'EMBEDDED_IFRAME',
  url: 'https://superset.example.com/superset/dashboard/sales/',
  openInIframe: true,
  sortOrder: 10,
  state: 'A',
  createdAt: '2026-09-09T10:00:00Z',
  modifiedAt: '2026-09-09T10:00:00Z',
};

const CRM: InstalledModule = {
  code: 'crm',
  name: 'CRM & Deals',
  version: '1.0.0',
  route: '/crm',
  icon: 'handshake',
  isSystem: false,
  status: 'ACTIVE',
  isActive: true,
};

describe('AppShellNavService', () => {
  const canView = vi.fn((_form: string) => true);
  const activeCodes = signal(new Set(['notes', 'upl']));
  const customModules = signal<InstalledModule[]>([]);
  const activeItems = signal<CustomNavigationItem[]>([]);
  let url = '/';

  beforeEach(() => {
    localStorage.removeItem(COLLAPSED_STATE_KEY);
    localStorage.removeItem(COLLAPSED_SECTIONS_KEY);
    canView.mockImplementation(() => true);
    activeCodes.set(new Set(['notes', 'upl']));
    customModules.set([]);
    activeItems.set([]);
    url = '/';
    TestBed.configureTestingModule({
      providers: [
        AppShellNavService,
        {
          provide: PermissionService,
          useValue: { canView, canUpdate: () => true, hasPermissionKey: () => true },
        },
        {
          provide: ModuleService,
          useValue: {
            isModuleActive: (code: string) => activeCodes().has(code),
            getActiveCustomModules: () => customModules(),
          },
        },
        { provide: NavigationService, useValue: { activeItems, entityItems: signal([NOTES_ENTITY, USERS_ENTITY]) } },
        { provide: NotificationService, useValue: { unreadCount: signal(3) } },
        {
          provide: Router,
          useValue: {
            get url() {
              return url;
            },
          },
        },
      ],
    });
  });

  afterEach(() => {
    localStorage.removeItem(COLLAPSED_STATE_KEY);
    localStorage.removeItem(COLLAPSED_SECTIONS_KEY);
  });

  const nav = () => TestBed.inject(AppShellNavService);
  const section = (id: string) =>
    nav()
      .navSections()
      .find((candidate) => candidate.id === id);
  const itemIds = (id: string) => section(id)?.items.map((item) => item.id);
  const allItems = () =>
    nav()
      .navSections()
      .flatMap((candidate) => candidate.items);

  it('declares the workspace, team and administration sections with their items in order', () => {
    expect(
      nav()
        .navSections()
        .map((candidate) => candidate.id),
    ).toEqual(['workspace', 'iam', 'administration']);
    expect(itemIds('workspace')).toEqual([
      'tasks',
      'projects',
      'notes',
      'upl-overview',
      'upl-sources',
      'upl-packages',
      'files',
      'analytics',
      'notifications',
    ]);
    expect(itemIds('iam')).toEqual(['users', 'roles', 'org-units', 'custom-fields']);
    expect(itemIds('administration')).toEqual([
      'announcements',
      'modules',
      'navigation-settings',
      'audit',
      'system',
      'settings',
    ]);
  });

  it('follows the active modules, lists custom modules and adds a reports section for custom items', () => {
    activeCodes.set(new Set());
    customModules.set([CRM]);
    activeItems.set([SALES_REPORT, { ...SALES_REPORT, id: 2, code: 'wiki', sectionId: 'workspace' }]);

    expect(itemIds('workspace')).not.toContain('notes');
    expect(itemIds('workspace')).toContain('custom-nav-wiki');
    const crm = allItems().find((item) => item.route === '/crm');
    expect(crm?.label).toBe('CRM & Deals');
    expect(crm?.permission()).toBe(true);
    const reports = section('custom-reports');
    expect(reports?.items).toHaveLength(1);
    expect(reports?.items[0].route).toBe('/embed/superset-sales');
    expect(reports?.items[0].label).toBe('Отчет по продажам (Superset)');

    activeCodes.set(new Set(['notes']));
    expect(itemIds('workspace')).toContain('notes');
  });

  it.each([
    { held: (form: string) => form === 'upl.sources', modules: ['notes', 'upl'], allowed: true },
    { held: (form: string) => form !== 'upl.sources', modules: ['notes', 'upl'], allowed: false },
    { held: () => true, modules: ['notes'], allowed: false },
  ])('offers sources and formats only with upl.sources view and the upl module ($allowed)', (row) => {
    canView.mockImplementation(row.held);
    activeCodes.set(new Set(row.modules));

    const sources = section('workspace')?.items.find((item) => item.route === '/upl/sources');
    expect(sources).toBeDefined();
    expect(sources!.permission()).toBe(row.allowed);
  });

  it('offers the organization only with md.org_units view', () => {
    canView.mockImplementation((form) => form === 'md.users');
    const orgUnits = () => section('iam')!.items.find((item) => item.id === 'org-units')!;
    expect(nav().canViewOrgUnits()).toBe(false);
    expect(orgUnits().permission()).toBe(false);

    canView.mockImplementation((form) => form === 'md.org_units');
    expect(nav().canViewOrgUnits()).toBe(true);
    expect(orgUnits().permission()).toBe(true);
  });

  it('restores the folded rail and sections from storage and saves every toggle', () => {
    localStorage.setItem(COLLAPSED_STATE_KEY, 'true');
    localStorage.setItem(COLLAPSED_SECTIONS_KEY, JSON.stringify(['iam']));

    expect(nav().isCollapsed()).toBe(true);
    expect(nav().isSectionExpanded('iam')).toBe(false);
    expect(nav().isSectionExpanded('workspace')).toBe(true);

    nav().toggleCollapsed();
    nav().toggleSection('iam');
    nav().toggleSection('workspace');
    expect(nav().isCollapsed()).toBe(false);
    expect(localStorage.getItem(COLLAPSED_STATE_KEY)).toBe('false');
    expect(JSON.parse(localStorage.getItem(COLLAPSED_SECTIONS_KEY)!)).toEqual(['workspace']);

    nav().toggleSubmenu('upl');
    expect(nav().isSubmenuExpanded('upl')).toBe(true);
    nav().toggleSubmenu('upl');
    expect(nav().isSubmenuExpanded('upl')).toBe(false);
  });

  it('marks the section of the current page, sums its badges and picks its icon', () => {
    url = '/tasks/projects/7';
    const workspace = section('workspace') as NavSection;

    expect(nav().isRouteActive('/tasks')).toBe(true);
    expect(nav().isRouteActive('/tasks', true)).toBe(false);
    expect(nav().isSectionActive(workspace)).toBe(true);
    expect(nav().isSectionActive(section('administration')!)).toBe(false);
    expect(nav().getSectionBadge(workspace)).toBe(3);
    expect(nav().hasVisibleItems(workspace)).toBe(true);
    expect(nav().getSectionIcon('unknown')).toBe('folder');
  });
});
