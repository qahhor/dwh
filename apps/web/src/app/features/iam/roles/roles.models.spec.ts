import { describe, expect, it } from 'vitest';
import { translateTest } from '@testing/i18n-test.stub';
import { ModuleGroup, filterModuleGroups, moduleDisplayName, moduleNameKey } from './roles.models';

describe('permission area names', () => {
  const i18n = {
    hasKey: (key: string) => translateTest(key) !== key,
    translate: (key: string, params?: Record<string, string>) => translateTest(key, params),
  };

  it('keys an area by its code with dots as underscores', () => {
    expect(moduleNameKey('ms.task')).toBe('iam.roles.area.ms_task');
    expect(moduleNameKey('upl')).toBe('iam.roles.area.upl');
  });

  it('names every built-in area in the viewer language, never by its code', () => {
    for (const code of [
      'analytics',
      'audit',
      'example',
      'md',
      'mf',
      'ms.note',
      'ms.notify',
      'ms.task',
      'search',
      'upl',
      'webhook',
    ]) {
      const name = moduleDisplayName(code, i18n);
      expect(name, code).toBe(translateTest(moduleNameKey(code)));
      expect(name, code).not.toContain(code.toUpperCase());
    }
  });

  it('keeps the code of an area a module brings without a name', () => {
    expect(moduleDisplayName('crm', i18n)).toBe(translateTest('iam.module_named', { name: 'crm' }));
  });
});

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
