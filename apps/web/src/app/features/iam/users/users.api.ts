import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { FormTreeItem } from '@core/models/rbac.models';
import { ApiService } from '@core/services/api.service';
import {
  EffectivePermissionsResponse,
  PersonalGrant,
  PersonalPermissionsResponse,
  PermissionsSaved,
} from './users.models';

/**
 * The rights of a user (`md.assignments`): the effective ones and the personal grants. The accounts themselves are the
 * user entity of the general runtime (`/entities/md.users`, ADR-0032 8).
 */
@Injectable({ providedIn: 'root' })
export class UsersApi {
  private readonly api = inject(ApiService);

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
    return this.api.get<FormTreeItem[]>('/iam/forms');
  }
}
