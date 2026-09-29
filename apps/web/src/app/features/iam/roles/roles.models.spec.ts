import { describe, expect, it } from 'vitest';
import { ModuleGroup, filterModuleGroups } from './roles.models';

describe('filterModuleGroups', () => {
  const groups = (): ModuleGroup[] => [
    {
      moduleCode: 'audit',
      moduleName: 'Аудит',
      isExpanded: false,
      forms: [
        {
          module: 'audit',
          formCode: 'audit.events',
          formName: 'События аудита',
          actions: [{ action: 'view', actionName: 'Просмотр' }],
        },
      ],
    },
    {
      moduleCode: 'md',
      moduleName: 'Справочники',
      isExpanded: false,
      forms: [{ module: 'md', formCode: 'md.users', formName: 'Пользователи', actions: [] }],
    },
  ];

  it('opens a collapsed module that has a match and leaves the modules without one out', () => {
    const source = groups();

    const visible = filterModuleGroups(source, 'События', 'all');

    expect(visible.map((group) => group.moduleCode)).toEqual(['audit']);
    expect(visible[0].isExpanded).toBe(true);
    expect(visible[0].forms).toHaveLength(1);
    expect(source[0].isExpanded).toBe(false);
  });

  it('shows the modules of the chosen tab as they are while nothing is searched', () => {
    const source = groups();

    expect(filterModuleGroups(source, '  ', 'md')).toEqual([source[1]]);
    expect(filterModuleGroups(source, '', 'all')).toEqual(source);
  });
});
