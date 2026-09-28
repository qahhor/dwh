import { DestroyRef, Injectable, OnDestroy, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, finalize, tap } from 'rxjs';
import { User } from '@core/models/auth.models';
import { CustomField } from '@core/models/custom-field.models';
import { QueryListMeta } from '@core/models/query-meta.models';
import { Role } from '@core/models/rbac.models';
import { CustomFieldsApi } from '@core/services/custom-fields.api';
import { I18nService } from '@core/services/i18n.service';
import { QueryMetaService, parseSort } from '@core/services/query-meta.service';
import { RolesApi } from '@core/services/roles.api';
import { ToastService } from '@core/services/toast.service';
import { ListViewState, ListViewsApi } from '@shared/list-views/list-views';
import { KeysetPager } from '@shared/paging/keyset-pager';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { OrderBy } from '@shared/ui-kit/components/table/table.types';
import { TableColumnStateStore } from '@shared/ui-kit/services/table-column-state.store';
import { problemText } from '@shared/ui/problem-text';
import { sortFromHeader } from '@shared/ui/registry-table-config';
import { UsersApi } from '../users.api';
import { UserDirectoryService } from './user-directory.service';
import { UserFilterService } from './user-filter.service';
import { UserFormsService } from './user-forms.service';

/**
 * The user list of the users screen: metadata, saved views, the keyset pager,
 * the quick filters and the row actions that reload it. Provided by the
 * screen, so it lives and dies with it.
 */
@Injectable()
export class UsersListFacade implements OnDestroy {
  readonly filters = inject(UserFilterService);
  private readonly forms = inject(UserFormsService);
  private readonly directory = inject(UserDirectoryService);
  private readonly usersApi = inject(UsersApi);
  private readonly rolesApi = inject(RolesApi);
  private readonly customFieldsApi = inject(CustomFieldsApi);
  private readonly queryMeta = inject(QueryMetaService);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly router = inject(Router, { optional: true });
  private readonly modal = inject(SMTModalService);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  /** Field metadata of the list (`query-meta/iam.users`), roadmap item 48. */
  readonly meta = signal<QueryListMeta | null>(null);
  readonly metaError = signal(false);
  readonly roles = signal<Role[]>([]);
  readonly customFields = signal<CustomField[]>([]);

  /** Sort, filter and columns of the list; saved views keep them under a name. */
  readonly views = new ListViewState('iam.users', inject(ListViewsApi), {
    defaultSort: () => {
      const meta = this.meta();
      return meta ? parseSort(meta.defaultSort) : null;
    },
    onApply: () => this.userPager.first(),
    columnsStore: inject(TableColumnStateStore),
  });

  /* A page at a time, sorted on the server. The pager cancels a superseded
     request, so a slower answer to an earlier search or filter never replaces
     the newer result. */
  readonly userPager = new KeysetPager<User>((cursor, limit) => this.fetchUsers(cursor, limit), {
    pageSize: 20,
    destroyRef: this.destroyRef,
    onLoaded: (rows) => {
      this.directory.remember(rows);
      this.directory.resolve(rows.map((user) => user.managerId));
    },
  });
  readonly users = this.userPager.items;
  readonly isLoading = this.userPager.loading;
  private exportFilters: Record<string, string> = {};
  private queryParamSubscription?: Subscription;
  private searchDebounceTimer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;

  /** A role link (`?roleId=`) filters the list; then roles, the first page and custom fields load. */
  init(): void {
    this.queryParamSubscription = this.route?.queryParamMap?.subscribe((params) => {
      const roleParam = params.get('roleId');
      if (roleParam && !isNaN(Number(roleParam))) {
        this.filters.selectedRoleId = Number(roleParam);
      }
    });
    this.rolesApi.list().subscribe({
      next: (res) => this.roles.set(res || []),
      error: () => {},
    });
    this.loadUsers(true);
    this.customFieldsApi.list('USER').subscribe((res) => {
      this.customFields.set(res || []);
    });
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.queryParamSubscription?.unsubscribe();
    clearTimeout(this.searchDebounceTimer ?? undefined);
  }

  /** The first page for the current filters; without `reset`, the page on screen again. The metadata comes first, once. */
  loadUsers(reset: boolean = false): void {
    if (this.destroyed) return;
    if (!this.meta()) {
      this.metaError.set(false);
      this.queryMeta
        .get('iam.users')
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (meta) => {
            this.meta.set(meta);
            this.views.load().subscribe(() => this.userPager.first());
          },
          error: () => this.metaError.set(true),
        });
      return;
    }
    if (reset) this.userPager.first();
    else this.userPager.reload();
  }

  /** A header click sorts the whole list on the server. */
  onSort(event: { column: string; sortBy: OrderBy } | undefined): void {
    if (!this.meta()) return;
    this.views.setSort(sortFromHeader(event));
    this.userPager.first();
  }

  /** The quick filters as export options; the same object while they stay, so the button is not re-rendered. */
  exportOptions(): Record<string, string> {
    const next: Record<string, string> = {};
    for (const [key, value] of Object.entries(this.flatFilters())) {
      if (value !== undefined) next[key] = String(value);
    }
    const same =
      Object.keys(next).length === Object.keys(this.exportFilters).length &&
      Object.entries(next).every(([key, value]) => this.exportFilters[key] === value);
    if (!same) this.exportFilters = next;
    return this.exportFilters;
  }

  resetExtraFilters(): void {
    this.filters.resetExtraFilters(() => this.loadUsers(true));
  }
  clearStateFilter(): void {
    this.filters.clearStateFilter(() => this.loadUsers(true));
  }
  clear2faFilter(): void {
    this.filters.clear2faFilter(() => this.loadUsers(true));
  }
  resetAllFilters(): void {
    this.filters.resetAllFilters(
      () => this.dropRoleParam(),
      () => this.loadUsers(true),
    );
  }
  setStateFilter(state: string): void {
    this.filters.setStateFilter(state, () => this.loadUsers(true));
  }
  onSearchInput(): void {
    clearTimeout(this.searchDebounceTimer ?? undefined);
    // The search text has already changed: an answer or a next page of the
    // old query must not land (or page) under it while the user types.
    this.userPager.invalidate();
    this.searchDebounceTimer = setTimeout(() => this.loadUsers(true), 250);
  }
  clearSearch(): void {
    this.filters.searchQuery = '';
    this.loadUsers(true);
  }
  selectedRoleName(): string {
    return this.filters.getSelectedRoleName(this.roles());
  }
  clearRoleFilter(): void {
    this.filters.clearRoleFilter(
      () => this.dropRoleParam(),
      () => this.loadUsers(true),
    );
  }

  /** Asks before deleting and anonymising a user; the dialog stays open until the server answers. */
  openDeleteConfirmModal(user: User): void {
    const t = (key: string, params?: Record<string, string>) => this.i18n.translate(key, params);
    this.modal
      .confirm({
        title: t('iam.udalenie_polzovatelya'),
        message: `${t('iam.delete_user_question', { name: user.name, login: user.login })}\n${t('iam.personalnye_dannye_budut_sterty_a_aktivnye_sessi')}`,
        yesLabel: t('common.delete'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () => {
          this.forms.isSubmitting.set(true);
          return this.usersApi.remove(user.id).pipe(
            tap(() => {
              this.toast.success(t('iam.polzovatel_uspeshno_udalen'));
              this.loadUsers();
            }),
            finalize(() => this.forms.isSubmitting.set(false)),
          );
        },
        actionError: problemText,
      })
      .subscribe();
  }

  toggleUserState(user: User, action: 'block' | 'unblock'): void {
    this.usersApi.setBlocked(user.id, action).subscribe({
      next: () => {
        this.toast.success(
          action === 'block'
            ? this.i18n.translate('iam.polzovatel_zablokirovan')
            : this.i18n.translate('iam.polzovatel_razblokirovan'),
        );
        this.loadUsers();
      },
    });
  }

  private dropRoleParam(): void {
    this.router?.navigate([], {
      relativeTo: this.route,
      queryParams: { roleId: null },
      queryParamsHandling: 'merge',
    });
  }

  /** The quick filters of the toolbar; the server keeps them beside the filter DSL (and in the cursor). */
  private flatFilters() {
    return {
      state: this.filters.selectedState || undefined,
      role_id: this.filters.selectedRoleId || undefined,
      is_2fa_enabled: this.filters.selected2fa !== null ? this.filters.selected2fa : undefined,
    };
  }

  private fetchUsers(cursor: string | null, limit: number) {
    return this.usersApi.page(
      this.flatFilters(),
      {
        sort: this.views.sort(),
        conditions: this.views.filter(),
        match: this.views.match(),
        search: this.filters.searchQuery,
      },
      cursor,
      limit,
    );
  }
}
