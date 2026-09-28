import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Role } from '@core/models/rbac.models';
import { buttonText } from '@testing/button-text';
import { UserFilterBarComponent } from './user-filter-bar.component';

describe('UserFilterBarComponent', () => {
  const role = (id: number, name: string): Role => ({
    id,
    name,
    state: 'A',
    orderNo: id,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
  });

  function setup(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(UserFilterBarComponent);
    fixture.componentRef.setInput('roles', [role(1, 'Администратор'), role(2, 'Аналитик')]);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    const component = fixture.componentInstance;
    const asked = {
      search: vi.fn(),
      typed: vi.fn(),
      cleared: vi.fn(),
      state: vi.fn(),
      menu: vi.fn(),
      resetExtra: vi.fn(),
      role: vi.fn(),
      twoFactor: vi.fn(),
      refresh: vi.fn(),
      clearState: vi.fn(),
      clearRole: vi.fn(),
      clear2fa: vi.fn(),
      resetAll: vi.fn(),
    };
    component.searchQueryChange.subscribe(asked.search);
    component.searchInput.subscribe(asked.typed);
    component.clearSearch.subscribe(asked.cleared);
    component.stateFilterChange.subscribe(asked.state);
    component.toggleFilterMenu.subscribe(asked.menu);
    component.resetExtraFilters.subscribe(asked.resetExtra);
    component.roleFilterChange.subscribe(asked.role);
    component.twoFactorFilterChange.subscribe(asked.twoFactor);
    component.refresh.subscribe(asked.refresh);
    component.clearStateFilter.subscribe(asked.clearState);
    component.clearRoleFilter.subscribe(asked.clearRole);
    component.clear2faFilter.subscribe(asked.clear2fa);
    component.resetAllFilters.subscribe(asked.resetAll);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const choose = (triggerId: string, label: string) => {
      (host.querySelector(`#${triggerId}`) as HTMLButtonElement).click();
      fixture.detectChanges();
      (Array.from(document.querySelectorAll('.smt-select__option')) as HTMLElement[])
        .find((option) => option.textContent?.includes(label))!
        .click();
      fixture.detectChanges();
    };
    return { fixture, host, choose, asked };
  }

  it('searches through a labelled field and reports typing and clearing', () => {
    const { fixture, host, asked } = setup({ searchQuery: 'ann' });
    const field = host.querySelector('#user-search') as HTMLInputElement;

    expect(host.querySelector('label[for="user-search"]')?.textContent?.trim()).toBe('Поиск пользователей');
    field.value = 'anna';
    field.dispatchEvent(new Event('input', { bubbles: true }));
    (host.querySelector('.smt-input__action') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(asked.search).toHaveBeenCalledWith('anna');
    expect(asked.typed).toHaveBeenCalled();
    expect(asked.cleared).toHaveBeenCalledTimes(1);
  });

  it('switches the status filter through a named radio group', () => {
    const { host, asked } = setup({ selectedState: 'A' });
    const group = host.querySelector('[role="radiogroup"]') as HTMLElement;
    const radios = Array.from(group.querySelectorAll('[role="radio"]')) as HTMLElement[];

    expect(group.getAttribute('aria-label')).toBe('Фильтр пользователей по статусу');
    expect(radios.map((radio) => radio.textContent?.trim())).toEqual(['Все', 'Активные', 'Заблокированные']);
    expect(radios[1].getAttribute('aria-checked')).toBe('true');
    radios[2].click();

    expect(asked.state).toHaveBeenCalledWith('P');
  });

  it('opens the extra filters as a named dialog and says whether it is open', () => {
    const closed = setup();
    const trigger = closed.host.querySelector('.filter-trigger-btn') as HTMLButtonElement;
    expect(trigger.getAttribute('aria-haspopup')).toBe('dialog');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(closed.host.querySelector('#user-extra-filters')).toBeNull();
    trigger.click();
    expect(closed.asked.menu).toHaveBeenCalledTimes(1);
    closed.fixture.destroy();

    const open = setup({ isFilterMenuOpen: true, hasExtraFilters: true });
    const panel = open.host.querySelector('#user-extra-filters') as HTMLElement;
    expect(open.host.querySelector('.filter-trigger-btn')?.getAttribute('aria-expanded')).toBe('true');
    expect(panel.getAttribute('role')).toBe('dialog');
    expect(panel.getAttribute('aria-label')).toBe('Дополнительные фильтры пользователей');
    (panel.querySelector('.reset-link') as HTMLButtonElement).click();
    expect(open.asked.resetExtra).toHaveBeenCalledTimes(1);
  });

  it('filters by role and by two-factor sign-in from labelled pickers', () => {
    const { host, choose, asked } = setup({ isFilterMenuOpen: true });

    expect(host.querySelector('label[for="user-role-filter"]')).not.toBeNull();
    expect(host.querySelector('label[for="user-2fa-filter"]')).not.toBeNull();
    choose('user-role-filter', 'Аналитик');
    choose('user-2fa-filter', 'Только с 2FA');

    expect(asked.role).toHaveBeenCalledWith(2);
    expect(asked.twoFactor).toHaveBeenCalledWith(true);
  });

  it('shows each active filter as a pill that clears it, and a reset of all', () => {
    const none = setup();
    expect(none.host.querySelector('.active-filters-bar')).toBeNull();
    none.fixture.destroy();

    const { host, asked } = setup({
      hasAnyActiveFilters: true,
      selectedState: 'P',
      selectedRoleId: 2,
      selectedRoleName: 'Аналитик',
      selected2fa: false,
    });
    const pills = Array.from(host.querySelectorAll('.filter-pill')) as HTMLElement[];
    expect(pills.map((pill) => pill.querySelector('span')?.textContent?.trim())).toEqual([
      'Статус: Заблокированные',
      'Фильтр по роли: Аналитик',
      '2FA: Отключена',
    ]);
    pills.forEach((pill) => (pill.querySelector('button') as HTMLButtonElement).click());
    (Array.from(host.querySelectorAll('button')) as HTMLButtonElement[])
      .find((button) => buttonText(button) === 'Сбросить все фильтры')!
      .click();

    expect(
      [asked.clearState, asked.clearRole, asked.clear2fa, asked.resetAll].map((fn) => fn.mock.calls.length),
    ).toEqual([1, 1, 1, 1]);
  });

  it('reloads the list from its named refresh button', () => {
    const { host, asked } = setup();

    (host.querySelector('button[aria-label="Обновить список пользователей"]') as HTMLButtonElement).click();

    expect(asked.refresh).toHaveBeenCalledTimes(1);
  });
});
