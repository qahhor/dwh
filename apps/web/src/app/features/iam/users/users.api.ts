import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { KeysetPage } from '@core/models/common.models';
import { ListQuery } from '@core/models/query-meta.models';
import { FormTreeItem } from '@core/models/rbac.models';
import { User } from '@core/models/auth.models';
import { ApiService } from '@core/services/api.service';
import { toQueryParams } from '@core/services/query-meta.service';
import {
  EffectivePermissionsResponse,
  PersonalGrant,
  PersonalPermissionsResponse,
  PermissionsSaved,
} from './users.models';

/** User administration: the list, one user, blocking, deleting and the personal permissions. */
@Injectable({ providedIn: 'root' })
export class UsersApi {
  private readonly api = inject(ApiService);

  /** A page of users: the screen's own filters, then the view's filter, order and search. */
  page(
    filters: Record<string, unknown>,
    query: ListQuery,
    cursor: string | null,
    limit: number,
  ): Observable<KeysetPage<User>> {
    return this.api.get<KeysetPage<User>>('/iam/users', {
      limit,
      cursor: cursor ?? undefined,
      ...filters,
      ...toQueryParams(query),
    });
  }

  /** One user for the card; the card shows a failure itself. */
  get(id: number | string): Observable<User> {
    return this.api.get<User>(`/iam/users/${id}`, undefined, { notifyError: false });
  }

  setBlocked(id: number, action: 'block' | 'unblock'): Observable<unknown> {
    return this.api.post(`/iam/users/${id}/${action}`);
  }

  /** The confirmation shows the failure, so no general error toast. */
  remove(id: number): Observable<unknown> {
    return this.api.delete(`/iam/users/${id}`, { notifyError: false });
  }

  /** What the user may do, from roles and personal grants together. */
  effectivePermissions(userId: number): Observable<EffectivePermissionsResponse> {
    return this.api.get<EffectivePermissionsResponse>(`/iam/users/${userId}/effective-permissions`);
  }

  personalPermissions(userId: number): Observable<PersonalPermissionsResponse> {
    return this.api.get<PersonalPermissionsResponse>(`/iam/users/${userId}/permissions`);
  }

  /**
   * The personal rights are part of the user: they are saved from the user's revision (plan item 3.6), and the answer
   * names the new one. The panel shows a failure itself.
   */
  savePersonalPermissions(userId: number, grants: PersonalGrant[], revision: number | undefined) {
    return this.api.put<PermissionsSaved>(
      `/iam/users/${userId}/permissions`,
      { grants },
      { notifyError: false, ifMatch: revision },
    );
  }

  /** The forms and actions a personal grant can name. */
  permissionForms(): Observable<FormTreeItem[]> {
    return this.api.get<FormTreeItem[]>('/iam/roles/forms');
  }
}
