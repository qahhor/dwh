import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { buttonText } from '@testing/button-text';
import { ModuleGroup } from '../roles.models';
import { RolePermissionsMatrixComponent } from './role-permissions-matrix.component';

describe('RolePermissionsMatrixComponent', () => {
  const analyst: Role = {
    id: 2,
    name: 'Аналитик',
    state: 'A',
    orderNo: 2,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
  };
  const users: ModuleGroup = {
    moduleCode: 'md',
    moduleName: 'Пользователи и безопасность',
    isExpanded: true,
    forms: [
      {
        module: 'md',
        formCode: 'iam.users',
        formName: 'Пользователи',
        actions: [
          { action: 'view', actionName: 'Просмотр' },
          { action: 'create', actionName: 'Создание' },
        ],
      },
    ],
  };
  const tasks: ModuleGroup = {
    moduleCode: 'ms.task',
    moduleName: 'Задачи',
    isExpanded: false,
    forms: [
      {
        module: 'ms.task',
        formCode: 'tasks',
        formName: 'Задачи',
        actions: [{ action: 'view', actionName: 'Просмотр' }],
      },
    ],
  };

  function setup(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(RolePermissionsMatrixComponent);
    const set = (name: string, value: unknown) => fixture.componentRef.setInput(name, value);
    set('role', analyst);
    set('hasPermission', (form: string, action: string) => form === 'iam.users' && action === 'view');
    set('isPermissionDirty', (form: string, action: string) => form === 'iam.users' && action === 'create');
    set('getModuleIcon', () => 'security');
    set('getModuleActionsCount', (mod: ModuleGroup) => mod.forms.reduce((sum, form) => sum + form.actions.length, 0));
    set('moduleGroups', [users, tasks]);
    set('visibleModuleGroups', [users, tasks]);
    set('formsCount', 2);
    set('activePermissionsCount', 1);
    set('totalActionsCount', 3);
    set('permissionPercentage', 33);
    for (const [name, value] of Object.entries(inputs)) set(name, value);
    const component = fixture.componentInstance;
    const asked = {
      toggle: vi.fn(),
      save: vi.fn(),
      reset: vi.fn(),
      refresh: vi.fn(),
      expand: vi.fn(),
      moduleAll: vi.fn(),
      search: vi.fn(),
      tab: vi.fn(),
    };
    component.togglePermission.subscribe(asked.toggle);
    component.savePermissions.subscribe(asked.save);
    component.resetChanges.subscribe(asked.reset);
    component.refreshRole.subscribe(asked.refresh);
    component.toggleModuleExpand.subscribe(asked.expand);
    component.toggleAllModule.subscribe(asked.moduleAll);
    component.matrixSearchQueryChange.subscribe(asked.search);
    component.selectedModuleTabChange.subscribe(asked.tab);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const byText = (text: string) =>
      (Array.from(host.querySelectorAll('button')) as HTMLButtonElement[]).find((item) =>
        buttonText(item).startsWith(text),
      ) ?? null;
    const checkboxes = () =>
      Array.from(host.querySelectorAll('.action-checkbox-card [role="checkbox"]')) as HTMLElement[];
    return { fixture, host, byText, checkboxes, asked };
  }

  it('shows the role and how much of the system it may do, as a named progress bar', () => {
    const { host } = setup();

    expect(host.querySelector('.role-name-text')?.textContent?.trim()).toBe('Аналитик');
    expect(host.querySelector('.meter-text')?.textContent).toContain('Разрешено 1 из 3 действий');
    const meter = host.querySelector('[role="progressbar"]') as HTMLElement;
    expect(meter.getAttribute('aria-label')).toBe('Доля разрешённых действий');
    expect(meter.getAttribute('aria-valuenow')).toBe('33');
  });

  it('shows each action of an open module as a checkbox in its state and reports a change', () => {
    const { host, checkboxes, asked } = setup({ canEditPermissions: true });

    // The collapsed module shows no actions.
    expect(checkboxes()).toHaveLength(2);
    expect(checkboxes().map((box) => box.getAttribute('aria-checked'))).toEqual(['true', 'false']);
    expect(host.querySelectorAll('.dirty-indicator-dot')).toHaveLength(1);
    checkboxes()[1].click();

    expect(asked.toggle).toHaveBeenCalledWith({ formCode: 'iam.users', action: 'create', checked: true });
  });

  it('leaves the matrix read-only for a viewer who may not edit it', () => {
    const { byText, checkboxes, asked } = setup({ canEditPermissions: false });

    expect(checkboxes()[0].getAttribute('aria-disabled')).toBe('true');
    expect(byText('Только чтение')!.disabled).toBe(true);
    expect(byText('Выбрать все права')).toBeNull();
    checkboxes()[1].click();
    expect(asked.toggle).not.toHaveBeenCalled();
  });

  it('offers saving to a granter, with the number of changes and a reset when there are any', () => {
    const viewer = setup({ canEditPermissions: true });
    expect(viewer.byText('Сохранить права')).toBeNull();

    const granter = setup({
      canGrant: true,
      canEditPermissions: true,
      isPermissionsDirty: true,
      dirtyPermissionsCount: 3,
    });
    expect(buttonText(granter.byText('Сохранить права')!)).toContain('(3)');
    granter.byText('Сохранить права')!.click();
    granter.byText('Сбросить изменения')!.click();
    expect(granter.asked.save).toHaveBeenCalledTimes(1);
    expect(granter.asked.reset).toHaveBeenCalledTimes(1);

    const clean = setup({ canGrant: true, canEditPermissions: true });
    expect(clean.byText('Сбросить изменения')).toBeNull();
  });

  it('opens and closes a module from its header and grants a whole module at once', () => {
    const { host, byText, asked } = setup({ canEditPermissions: true });
    const headers = Array.from(host.querySelectorAll('.mod-toggle-btn')) as HTMLButtonElement[];

    expect(headers[0].getAttribute('aria-expanded')).toBe('true');
    expect(headers[0].getAttribute('aria-controls')).toBe('role-module-md');
    expect(host.querySelector('#role-module-md')?.getAttribute('aria-label')).toBe(
      'Права модуля Пользователи и безопасность',
    );
    expect(headers[1].getAttribute('aria-expanded')).toBe('false');
    headers[1].click();
    byText('✓ Выбрать все')!.click();

    expect(asked.expand).toHaveBeenCalledWith(tasks);
    expect(asked.moduleAll).toHaveBeenCalledWith({ mod: users, select: true });
  });

  it('shows a failed load with a way to reload the role', () => {
    const { host, byText, asked } = setup({ permissionsError: 'Не удалось загрузить права' });

    expect(host.querySelector('smt-alert')?.textContent).toContain('Не удалось загрузить права');
    expect(host.querySelector('[role="progressbar"]')).toBeNull();
    byText('Обновить')!.click();

    expect(asked.refresh).toHaveBeenCalledWith(analyst);
  });

  it('searches the forms, counts the matches and says when none match', () => {
    const found = setup({ matrixSearchQuery: 'польз', matchingFormsCount: 1 });
    expect(found.host.querySelector('.search-match-badge')?.textContent?.trim()).toBe('Найдено форм: 1');
    const field = found.host.querySelector('#permission-search') as HTMLInputElement;
    field.value = 'задач';
    field.dispatchEvent(new Event('input'));
    expect(found.asked.search).toHaveBeenLastCalledWith('задач');

    const none = setup({ matrixSearchQuery: 'xyz', visibleModuleGroups: [] });
    expect(none.host.querySelector('.no-forms-box')?.textContent).toContain('Формы не найдены по запросу «xyz»');
  });
});
