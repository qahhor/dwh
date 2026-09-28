import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { InstalledModule } from '../modules.models';
import { ModulesTableComponent } from './modules-table.component';

const NOTES: InstalledModule = {
  code: 'notes',
  name: 'Notes',
  description: 'Personal and team notes',
  version: '1.2.0',
  isSystem: false,
  status: 'ACTIVE',
  isActive: true,
};
const IAM: InstalledModule = {
  code: 'iam',
  name: 'IAM',
  version: '1.0.0',
  isSystem: true,
  status: 'ACTIVE',
  isActive: true,
};
const REPORTS: InstalledModule = {
  code: 'reports',
  name: 'Reports',
  version: '0.9.0',
  isSystem: false,
  status: 'DISABLED',
  isActive: false,
};

function render(
  options: { modules?: InstalledModule[]; canManage?: boolean; loading?: boolean; toggling?: string | null } = {},
) {
  const fixture = TestBed.createComponent(ModulesTableComponent);
  fixture.componentRef.setInput('modules', options.modules ?? [NOTES, IAM, REPORTS]);
  fixture.componentRef.setInput('isLoading', options.loading ?? false);
  fixture.componentRef.setInput('canManage', options.canManage ?? true);
  fixture.componentRef.setInput('togglingCode', options.toggling ?? null);
  const toggles: Array<{ code: string; enabled: boolean }> = [];
  fixture.componentInstance.toggle.subscribe(({ module, enabled }) => toggles.push({ code: module.code, enabled }));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const rows = () => Array.from(host.querySelectorAll('[role="rowgroup"] > [role="row"]')) as HTMLElement[];
  const headers = () =>
    (Array.from(host.querySelectorAll('[role="columnheader"]')) as HTMLElement[]).map((header) =>
      header.textContent?.trim(),
    );
  const switchOf = (row: number) => rows()[row].querySelector('[role="switch"]') as HTMLButtonElement;
  return { fixture, host, rows, headers, switchOf, toggles };
}

describe('ModulesTableComponent', () => {
  it('shows one row per module with its name, description, code, version, type and status', () => {
    const { host, rows } = render();

    expect(host.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Модули системы');
    expect(rows()).toHaveLength(3);
    const notes = rows()[0].textContent!;
    expect(notes).toContain('Notes');
    expect(notes).toContain('Personal and team notes');
    expect(notes).toContain('notes');
    expect(notes).toContain('v1.2.0');
    expect(notes).toContain('Расширение');
    expect(notes).toContain('Активен');
    expect(rows()[1].textContent).toContain('Системный');
    expect(rows()[2].textContent).toContain('Отключен');
  });

  it('offers the on/off column only to someone who may manage modules', () => {
    const managed = render();
    expect(managed.headers()).toContain('Действие');
    expect(managed.switchOf(0)).not.toBeNull();

    const viewed = render({ canManage: false });
    expect(viewed.headers()).not.toContain('Действие');
    expect(viewed.host.querySelector('[role="switch"]')).toBeNull();
  });

  it('names each switch after its module and asks to turn it off or on', () => {
    const { switchOf, toggles } = render();

    expect(switchOf(0).getAttribute('aria-label')).toBe('Отключить модуль Notes');
    expect(switchOf(2).getAttribute('aria-label')).toBe('Включить модуль Reports');
    switchOf(0).click();
    switchOf(2).click();

    expect(toggles).toEqual([
      { code: 'notes', enabled: false },
      { code: 'reports', enabled: true },
    ]);
  });

  it('locks the switch of a system module, saying why, and of the module being switched', () => {
    const { rows, switchOf } = render({ toggling: 'reports' });

    expect(switchOf(1).disabled).toBe(true);
    expect(rows()[1].querySelector('[title="Системный модуль платформы не может быть отключен"]')).not.toBeNull();
    expect(switchOf(2).disabled).toBe(true);
    expect(switchOf(0).disabled).toBe(false);
  });

  it('says nothing was found when there are no modules', () => {
    const { host, rows } = render({ modules: [] });

    expect(rows()).toHaveLength(0);
    expect(host.textContent).toContain('Модули не найдены');
    expect(host.textContent).toContain('По вашему запросу не найдено установленных модулей');
  });
});
