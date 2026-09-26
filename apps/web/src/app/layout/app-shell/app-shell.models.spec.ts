import { describe, expect, it } from 'vitest';
import { buildNavSections, BuildNavSectionsOptions, NavItem } from './app-shell.models';
import { CustomNavigationItem } from '../../core/models/navigation.models';

function item(code: string, requiredPermission: string | null): CustomNavigationItem {
  return {
    id: code.length, code, title: code, sectionId: 'custom', icon: 'analytics', targetType: 'INTERNAL_ROUTE',
    url: `/${code}`, openInIframe: false, requiredPermission, sortOrder: 10, state: 'A'
  };
}

function customItems(held: string[]): NavItem[] {
  const no = () => false;
  const options: BuildNavSectionsOptions = {
    activeCustomModules: [], customNavItems: [item('open', null), item('guarded', 'tasks.items.view')],
    isNotesActive: false, canViewTasks: no, canViewProjects: no, canViewNotes: no, canViewSources: no,
    canViewPackages: no, canViewFiles: no, canViewAnalytics: no, canViewNotifications: no, canViewUsers: no,
    canViewRoles: no, canViewOrgUnits: no, canViewCustomFields: no, canViewAnnouncements: no, canViewModules: no,
    canViewNavigationSettings: no, canViewAudit: no, canViewSystem: no, canViewSettings: no,
    hasPermission: permission => held.includes(permission), unreadCount: () => 0
  };
  return buildNavSections(options).find(section => section.id === 'custom-reports')!.items;
}

describe('buildNavSections — custom menu items (FR-MOD-02)', () => {
  it('shows an item with a right only to its holder and an item without one to everyone', () => {
    const shown = (held: string[]) => customItems(held).filter(nav => nav.permission()).map(nav => nav.id);
    expect(shown([])).toEqual(['custom-nav-open']);
    expect(shown(['tasks.items.view'])).toEqual(['custom-nav-open', 'custom-nav-guarded']);
  });
});
