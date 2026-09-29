import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { User } from '@core/models/auth.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { QueryMetaService } from '@core/services/query-meta.service';
import { ToastService } from '@core/services/toast.service';
import { ListViewsApi } from '@shared/list-views/list-views';
import { OrderBy } from '@shared/ui-kit/components/table/table.types';
import { translateTest } from '@testing/i18n-test.stub';
import { UserDirectoryService } from './user-directory.service';
import { UsersListFacade } from './users-list.facade';

const META = {
  code: 'iam.users',
  defaultSort: 'name',
  defaultLimit: 20,
  maxLimit: 200,
  maxConditions: 20,
  maxInValues: 100,
  fields: [],
} as QueryListMeta;

const user = (id: number, name: string, state: User['state'] = 'A'): User => ({
  id,
  name,
  login: `u${id}`,
  email: '',
  state,
  language: 'ru',
  timezone: 'UTC',
  attributes: {},
  is2faEnabled: false,
  forcePasswordChange: false,
  createdAt: '',
  modifiedAt: '',
});
const page = (items: User[], nextCursor: string | null = null) => of({ items, nextCursor, hasMore: !!nextCursor });

describe('UsersListFacade', () => {
  /** A started list: the metadata, the saved views and the first (empty) page have answered. */
  function setup() {
    const get = vi.fn((path: string, _params?: unknown, _options?: unknown): Observable<unknown> =>
      path === '/iam/users' ? page([]) : path === '/iam/roles' ? of([{ id: 10, name: 'Менеджер' }]) : of([]),
    );
    const post = vi.fn((_path: string) => of({}));
    TestBed.configureTestingModule({
      providers: [
        UsersListFacade,
        UserDirectoryService,
        { provide: ApiService, useValue: { get, post, delete: vi.fn(() => of({})) } },
        { provide: I18nService, useValue: { translate: translateTest, currentLang: signal('ru') } },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } },
        { provide: QueryMetaService, useValue: { get: () => of(META) } },
        { provide: ListViewsApi, useValue: { list: () => of([]), create: vi.fn(), update: vi.fn(), remove: vi.fn() } },
      ],
    });
    const facade = TestBed.inject(UsersListFacade);
    const beforeInit = get.mock.calls.length;
    facade.init();
    TestBed.tick();
    return { facade, get, post, beforeInit };
  }
  const ids = (facade: UsersListFacade) => facade.users().map((item) => item.id);

  it('asks for roles and custom fields only once the screen starts, and sorts by the default field', () => {
    const { facade, get, beforeInit } = setup();

    expect(beforeInit).toBe(0);
    expect(get).toHaveBeenCalledWith('/iam/roles');
    expect(get).toHaveBeenCalledWith('/custom-fields', { entityType: 'USER' });
    expect(get).toHaveBeenCalledWith('/iam/users', expect.objectContaining({ sort: 'name', limit: 20 }));
    expect(facade.roles().map((role) => role.name)).toEqual(['Менеджер']);
    expect(facade.selectedRoleName()).toBe('');
  });

  it('pages forward with the cursor the server returned and back without asking for a new one', () => {
    const { facade, get } = setup();

    get.mockReturnValueOnce(page([user(1, 'Пользователь 1')], 'cursor_abc'));
    facade.loadUsers(true);
    expect(get).toHaveBeenLastCalledWith('/iam/users', expect.objectContaining({ limit: 20, cursor: undefined }));
    expect(facade.userPager.canGoForward()).toBe(true);

    get.mockReturnValueOnce(page([user(2, 'Пользователь 2')]));
    facade.userPager.next();
    expect(get).toHaveBeenLastCalledWith('/iam/users', expect.objectContaining({ cursor: 'cursor_abc', limit: 20 }));
    expect(ids(facade)).toEqual([2]);
    expect(facade.userPager.page()).toBe(2);
    expect(facade.userPager.canGoForward()).toBe(false);

    get.mockReturnValueOnce(page([user(1, 'Пользователь 1')], 'cursor_abc'));
    facade.userPager.previous();
    expect(get).toHaveBeenLastCalledWith('/iam/users', expect.objectContaining({ cursor: undefined }));
    expect(facade.userPager.page()).toBe(1);
  });

  it('never lets a pending page of the old filter replace the new result', () => {
    const { facade, get } = setup();
    const pendingNext = new Subject<unknown>();
    const pendingFilter = new Subject<unknown>();

    get.mockReturnValueOnce(page([user(1, 'Первый')], 'c2'));
    facade.loadUsers(true);
    get.mockReturnValueOnce(pendingNext);
    facade.userPager.next();
    get.mockReturnValueOnce(pendingFilter);
    facade.filters.selectedState = 'P';
    facade.loadUsers(true);

    pendingFilter.next({ items: [user(9, 'Заблокированный')], nextCursor: null, hasMore: false });
    pendingFilter.complete();
    pendingNext.next({ items: [user(2, 'Второй')], nextCursor: null, hasMore: false });
    pendingNext.complete();

    expect(ids(facade)).toEqual([9]);
    expect(facade.userPager.page()).toBe(1);
  });

  it('keeps the page on screen after blocking a user, and steps back when its last row is gone', () => {
    const { facade, get, post } = setup();
    get.mockReturnValueOnce(page([user(1, 'Первый')], 'c2'));
    facade.loadUsers(true);
    get.mockReturnValueOnce(page([user(2, 'Второй')]));
    facade.userPager.next();

    get.mockReturnValueOnce(page([user(2, 'Второй', 'P')]));
    facade.toggleUserState(user(2, 'Второй'), 'block');
    expect(post).toHaveBeenCalledWith('/iam/users/2/block');
    expect(get).toHaveBeenLastCalledWith('/iam/users', expect.objectContaining({ cursor: 'c2' }));
    expect(facade.userPager.page()).toBe(2);

    // With a state filter the blocked user leaves the page, which is now empty.
    get.mockReturnValueOnce(page([]));
    get.mockReturnValueOnce(page([user(1, 'Первый')], 'c2'));
    facade.toggleUserState(user(2, 'Второй'), 'block');
    expect(facade.userPager.page()).toBe(1);
    expect(ids(facade)).toEqual([1]);
  });

  it('sorts the whole list on the server and hands the quick filters to the server export', () => {
    const { facade, get } = setup();
    facade.filters.selectedState = 'A';
    facade.filters.selected2fa = true;

    facade.onSort({ column: 'createdAt', sortBy: OrderBy.Desc });

    expect(get).toHaveBeenLastCalledWith(
      '/iam/users',
      expect.objectContaining({ sort: '-createdAt', state: 'A', is2faEnabled: true, cursor: undefined }),
    );
    const options = facade.exportOptions();
    expect(options).toEqual({ state: 'A', is2faEnabled: 'true' });
    expect(facade.exportOptions()).toBe(options);
  });

  it('does not page the new search text from the old query while the user is still typing', () => {
    const { facade, get } = setup();
    get.mockReturnValueOnce(page([user(1, 'Первый')], 'old-cursor'));
    facade.loadUsers(true);
    expect(facade.userPager.canGoForward()).toBe(true);

    vi.useFakeTimers();
    try {
      facade.filters.searchQuery = 'ann';
      facade.onSearchInput();
      const calls = get.mock.calls.length;
      facade.userPager.next();
      expect(get).toHaveBeenCalledTimes(calls);

      get.mockReturnValueOnce(page([user(4, 'Анна')]));
      vi.advanceTimersByTime(250);
      expect(get).toHaveBeenLastCalledWith('/iam/users', expect.objectContaining({ q: 'ann', cursor: undefined }));
      expect(ids(facade)).toEqual([4]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows the answer to the latest search even when an earlier one answers last', () => {
    const { facade, get } = setup();
    const early = new Subject<unknown>();
    const late = new Subject<unknown>();
    facade.filters.searchQuery = 'ab';
    get.mockReturnValueOnce(early);
    facade.loadUsers(true);
    facade.filters.searchQuery = 'abc';
    get.mockReturnValueOnce(late);
    facade.loadUsers(true);

    late.next({ items: [user(3, 'abc')], nextCursor: null, hasMore: false });
    late.complete();
    early.next({ items: [user(4, 'ab')], nextCursor: null, hasMore: false });
    early.complete();

    expect(ids(facade)).toEqual([3]);
  });

  it('clears one quick filter or all of them, each time from the first page', () => {
    const { facade, get } = setup();
    facade.filters.selectedRoleId = 10;
    facade.filters.selected2fa = true;
    facade.filters.selectedState = 'A';
    expect(facade.selectedRoleName()).toBe('Менеджер');

    facade.clear2faFilter();
    expect(facade.filters.selected2fa).toBeNull();
    facade.clearStateFilter();
    expect(facade.filters.selectedState).toBe('');
    expect(get).toHaveBeenLastCalledWith('/iam/users', expect.objectContaining({ roleId: 10, cursor: undefined }));

    facade.resetAllFilters();
    expect(facade.filters.selectedRoleId).toBeNull();
    expect(facade.filters.hasAnyActiveFilters()).toBe(false);
    expect((get.mock.lastCall?.[1] as Record<string, unknown>)['roleId']).toBeUndefined();
  });
});
