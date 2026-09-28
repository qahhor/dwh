import { TestBed } from '@angular/core/testing';
import { NEVER, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { User } from '@core/models/auth.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { ToastService } from '@core/services/toast.service';
import { KeysetPager, KeysetResponse } from '@shared/paging/keyset-pager';
import { buttonText } from '@testing/button-text';
import { metaField } from '@testing/registry-meta';
import { UserTableViewComponent } from './user-table-view.component';

/** What `query-meta/iam.users` answers. */
const USERS_META: QueryListMeta = {
  code: 'iam.users',
  defaultSort: 'name',
  defaultLimit: 20,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [
    metaField('name', 'iam.users.col.name', 'text', { sortable: true }),
    metaField('email', 'iam.users.col.email', 'text', { sortable: true }),
    metaField('state', 'iam.users.col.state', 'enum', { enumValues: ['A', 'P'], enumLabelPrefix: 'iam.users.state.' }),
    metaField('is2faEnabled', 'iam.users.col.two_factor', 'boolean'),
    metaField('createdAt', 'iam.users.col.created_at', 'instant', { sortable: true }),
  ],
} as QueryListMeta;

describe('UserTableViewComponent', () => {
  const person = (id: number, name: string, login: string, extra: Partial<User> = {}): User => ({
    id,
    name,
    login,
    email: `${login}@example.test`,
    state: 'A',
    language: 'ru',
    timezone: 'Asia/Tashkent',
    attributes: {},
    is2faEnabled: false,
    forcePasswordChange: false,
    createdAt: '2026-08-30T00:00:00Z',
    modifiedAt: '2026-08-30T00:00:00Z',
    ...extra,
  });
  const anna = person(7, 'Анна Иванова', 'anna', { is2faEnabled: true, phone: '+998901234567', managerId: 3 });
  const bobur = person(8, 'Бобур Алиев', 'bobur', { state: 'P' });
  const admin = person(1, 'Администратор', 'admin');

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: ApiService, useValue: { get: vi.fn(() => of({ items: [] })) } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    });
  });

  function setup(
    options: {
      users?: User[];
      meta?: QueryListMeta | null;
      loading?: boolean;
      rights?: Partial<Record<'canUpdateUser' | 'canBlockUser' | 'canUnblockUser' | 'canDeleteUser', boolean>>;
    } = {},
  ) {
    const fixture = TestBed.createComponent(UserTableViewComponent);
    const answer = of<KeysetResponse<User>>({ items: options.users ?? [anna, bobur], nextCursor: null });
    const pager = new KeysetPager<User>(() => (options.loading ? NEVER : answer), { pageSize: 20 });
    fixture.componentRef.setInput('pager', pager);
    fixture.componentRef.setInput('meta', options.meta === undefined ? USERS_META : options.meta);
    fixture.componentRef.setInput('getUserRoleNames', (user: User) => (user.id === 7 ? ['Аналитик', 'Менеджер'] : []));
    fixture.componentRef.setInput('getManagerName', (user: User) => (user.managerId ? 'Бахтиёр Каримов' : null));
    for (const [name, value] of Object.entries(options.rights ?? {})) fixture.componentRef.setInput(name, value);
    const component = fixture.componentInstance;
    const asked = { view: vi.fn(), edit: vi.fn(), toggle: vi.fn(), remove: vi.fn() };
    component.viewUser.subscribe(asked.view);
    component.editUser.subscribe(asked.edit);
    component.toggleState.subscribe(asked.toggle);
    component.deleteUser.subscribe(asked.remove);
    fixture.detectChanges();
    pager.first();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const rows = () => Array.from(host.querySelectorAll('.smt-data-row')) as HTMLElement[];
    const button = (label: string) => host.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
    const settle = async () => {
      await fixture.whenStable();
      fixture.detectChanges();
    };
    return { fixture, host, rows, button, settle, asked };
  }

  it('shows no table until the list of fields is known', () => {
    const { host } = setup({ meta: null });

    expect(host.querySelector('ui-server-table')).toBeNull();
    expect(host.querySelector('.table-container')?.getAttribute('aria-label')).toBe('Таблица пользователей');
  });

  it('shows each user with contacts, roles, manager, two-factor state and status', () => {
    const { rows } = setup();

    expect(rows()).toHaveLength(2);
    const [first, second] = rows();
    expect(first.textContent).toContain('@anna');
    expect(first.textContent).toContain('+998901234567');
    expect(Array.from(first.querySelectorAll('.role-pill')).map((pill) => pill.textContent)).toEqual([
      'Аналитик',
      'Менеджер',
    ]);
    expect(first.querySelector('.manager-text')?.textContent).toBe('Бахтиёр Каримов');
    expect(first.querySelector('[role="img"]')?.getAttribute('aria-label')).toBe('2FA включена');
    expect(first.querySelector('.status-indicator')?.textContent?.trim()).toBe('Активен');
    // No roles and no manager read as dashes.
    expect(second.querySelectorAll('.muted-dash')).toHaveLength(2);
    expect(second.querySelector('[role="img"]')?.getAttribute('aria-label')).toBe('2FA выключена');
    expect(second.querySelector('.status-indicator')?.textContent?.trim()).toBe('Отключен');
  });

  it('opens a profile from the name or the view button, and offers editing only with the right', () => {
    const viewer = setup();
    viewer.button('Открыть профиль пользователя Анна Иванова')!.click();
    viewer.button('Просмотреть пользователя Бобур Алиев')!.click();
    expect(viewer.asked.view.mock.calls).toEqual([[anna], [bobur]]);
    expect(viewer.button('Редактировать пользователя Анна Иванова')).toBeNull();
    viewer.fixture.destroy();

    const editor = setup({ rights: { canUpdateUser: true } });
    editor.button('Редактировать пользователя Анна Иванова')!.click();
    expect(editor.asked.edit).toHaveBeenCalledWith(anna);
  });

  it('offers blocking an active user and unblocking a blocked one, each only with its right', () => {
    const viewer = setup();
    expect(viewer.host.querySelector('[data-testid="user-more-actions"]')).toBeNull();
    viewer.fixture.destroy();

    const blocker = setup({ rights: { canBlockUser: true } });
    expect(blocker.button('Ещё действия: Анна Иванова')).not.toBeNull();
    expect(blocker.button('Ещё действия: Бобур Алиев')).toBeNull();
    blocker.fixture.destroy();

    const unblocker = setup({ rights: { canUnblockUser: true } });
    expect(unblocker.button('Ещё действия: Анна Иванова')).toBeNull();
    expect(unblocker.button('Ещё действия: Бобур Алиев')).not.toBeNull();
  });

  it('asks the page to block or delete from the row menu, and never offers deleting the built-in admin', async () => {
    const { button, settle, asked } = setup({
      users: [anna, admin],
      rights: { canBlockUser: true, canDeleteUser: true },
    });
    const open = async (name: string) => {
      button(`Ещё действия: ${name}`)!.click();
      await settle();
      return Array.from(document.querySelectorAll('[role="menuitem"]')) as HTMLElement[];
    };

    const items = await open('Анна Иванова');
    expect(items.map(buttonText)).toEqual(['Заблокировать', 'Удалить']);
    items[1].click();
    await settle();
    expect(asked.remove).toHaveBeenCalledWith(anna);

    const adminItems = await open('Администратор');
    expect(adminItems.map(buttonText)).toEqual(['Заблокировать']);
    adminItems[0].click();
    await settle();
    expect(asked.toggle).toHaveBeenCalledWith({ user: admin, action: 'block' });
  });

  it('marks the region busy while a page loads and says when no users match', () => {
    const loading = setup({ loading: true });
    expect(loading.host.querySelector('.table-container')?.getAttribute('aria-busy')).toBe('true');
    loading.fixture.destroy();

    const empty = setup({ users: [] });
    expect(empty.host.querySelector('.table-container')?.getAttribute('aria-busy')).toBe('false');
    expect(empty.host.querySelector('.empty-text')?.textContent?.trim()).toBe('Пользователи не найдены');
  });
});
