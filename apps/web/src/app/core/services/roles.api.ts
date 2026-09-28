import { Injectable, inject } from '@angular/core';
import { Observable, catchError } from 'rxjs';
import { FormTreeItem, PermissionPair, Role } from '../models/rbac.models';
import { ApiService } from './api.service';

/** Roles and their permissions: the roles screen edits them, the user screens pick from them. */
@Injectable({ providedIn: 'root' })
export class RolesApi {
  private readonly api = inject(ApiService);

  /** Every role; a viewer without the RBAC right gets the roles list of user administration instead. */
  list(): Observable<Role[]> {
    return this.api.get<Role[]>('/rbac/roles').pipe(catchError(() => this.api.get<Role[]>('/iam/roles')));
  }

  /** How many users hold each role, by role id. */
  userCounts(): Observable<Record<number, number>> {
    return this.api.get<Record<number, number>>('/iam/roles/user-counts');
  }

  /** The forms and their actions, the rows of the permission matrix. */
  forms(): Observable<FormTreeItem[]> {
    return this.api.get<FormTreeItem[]>('/rbac/forms');
  }

  /** A role's permissions as `form:action`; the screen shows its own error. */
  permissions(roleId: number): Observable<string[]> {
    return this.api.get<string[]>(`/rbac/roles/${roleId}/permissions`, undefined, { notifyError: false });
  }

  savePermissions(roleId: number, pairs: PermissionPair[]): Observable<unknown> {
    return this.api.put(`/rbac/roles/${roleId}/permissions`, pairs);
  }
}
