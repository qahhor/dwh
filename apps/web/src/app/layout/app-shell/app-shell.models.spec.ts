import { describe, expect, it } from 'vitest';
import { buildNavSections, BuildNavSectionsOptions, NavItem } from './app-shell.models';
import { CustomNavigationItem, EntityMenuItem } from '../../core/models/navigation.models';

function item(code: string, requiredPermission: string | null): CustomNavigationItem {
  return {
    id: code.length,
    code,
    title: code,
    sectionId: 'custom',
    icon: 'analytics',
    targetType: 'INTERNAL_ROUTE',
    url: `/${code}`,
    openInIframe: false,
    requiredPermission,
    sortOrder: 10,
    state: 'A',
  };
}

function options(held: string[], entityItems: EntityMenuItem[] = [], active: string[] = []): BuildNavSectionsOptions {
  const no = () => false;
  return {
    activeCustomModules: [],
    customNavItems: [item('open', null), item('guarded', 'tasks.items.view')],
    entityItems,
    isModuleActive: (code) => active.includes(code),
    canViewTasks: no,
    canViewProjects: no,
    canViewSources: no,
    canViewPackages: no,
    canViewFiles: no,
    canViewAnalytics: no,
    canViewNotifications: no,
    canViewUsers: no,
    canViewRoles: no,
    canViewOrgUnits: no,
    canViewCustomFields: no,
    canViewAnnouncements: no,
    canViewModules: no,
    canViewNavigationSettings: no,
    canViewAudit: no,
    canViewSystem: no,
    canViewSettings: no,
    hasPermission: (permission) => held.includes(permission),
    unreadCount: () => 0,
  };
}

function customItems(held: string[]): NavItem[] {
  return buildNavSections(options(held)).find((section) => section.id === 'custom-reports')!.items;
}

function entity(code: string, section: string, order: number, module: string | null): EntityMenuItem {
  return {
    code,
    form: code.split('.')[1],
    route: '/' + code,
    labelKey: 'nav.' + code,
    icon: 'description',
    section,
    order,
    module,
  };
}

describe('buildNavSections — custom menu items (FR-MOD-02)', () => {
  it('shows an item with a right only to its holder and an item without one to everyone', () => {
    const shown = (held: string[]) =>
      customItems(held)
        .filter((nav) => nav.permission())
        .map((nav) => nav.id);
    expect(shown([])).toEqual(['custom-nav-open']);
    expect(shown(['tasks.items.view'])).toEqual(['custom-nav-open', 'custom-nav-guarded']);
  });
});

describe('buildNavSections — items of declared entities (roadmap item 57)', () => {
  const items = [
    entity('ms.notes', 'workspace', 30, 'notes'),
    entity('ms.later', 'workspace', 40, null),
    entity('ms.admin', 'administration', 10, null),
    entity('ms.off', 'workspace', 20, 'off'),
  ];

  it('places each item in its section by order and leaves out those whose module is off', () => {
    const sections = buildNavSections(options(['notes.view'], items, ['notes']));
    const workspace = sections.find((section) => section.id === 'workspace')!.items.map((one) => one.id);
    const administration = sections.find((section) => section.id === 'administration')!.items.map((one) => one.id);

    expect(workspace.slice(0, 4)).toEqual(['tasks', 'projects', 'notes', 'later']);
    expect(workspace).not.toContain('off');
    expect(administration.at(-1)).toBe('admin');
  });

  it('does not list a module again under Modules when its entity brings the menu item', () => {
    const opts = {
      ...options(['notes.view'], items, ['notes']),
      activeCustomModules: [
        { code: 'notes', name: 'Notes', version: '1', route: '/notes', isSystem: false, status: 'ACTIVE' as const },
        { code: 'crm', name: 'CRM', version: '1', route: '/crm', isSystem: false, status: 'ACTIVE' as const },
      ],
    };

    const modules = buildNavSections(opts)
      .find((section) => section.id === 'custom-modules')!
      .items.map((one) => one.id);

    expect(modules).toEqual(['module-crm']);
  });

  it("shows an item with its entity's view right", () => {
    const workspace = buildNavSections(options(['notes.view'], items, ['notes'])).find(
      (section) => section.id === 'workspace',
    )!.items;

    expect(workspace.find((one) => one.id === 'notes')!.permission()).toBe(true);
    expect(workspace.find((one) => one.id === 'later')!.permission()).toBe(false);
  });
});
