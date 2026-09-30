import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { FormTreeItem, PermissionPair, Role } from '../models/rbac.models';
import { ApiService } from './api.service';

/** Roles and their permissions: the roles screen edits them, the user screens pick from them. */
@Injectable({ providedIn: 'root' })
export class RolesApi {
  private readonly api = inject(ApiService);

  /** Every role; a viewer without the RBAC right gets the roles list of user administration instead. */
  list(): Observable<Role[]> {
    return this.api.get<Role[]>('/iam/roles');
  }

  /** How many users hold each role, by role id. */
  userCounts(): Observable<Record<number, number>> {
    return this.api.get<Record<number, number>>('/iam/roles/user-counts');
  }

  /** The forms and their actions, the rows of the permission matrix. */
  forms(): Observable<FormTreeItem[]> {
    return this.api.get<FormTreeItem[]>('/iam/forms');
  }

  /** A role's permissions as `form:action`; the screen shows its own error. */
  permissions(roleId: number): Observable<string[]> {
    return this.api.get<string[]>(`/iam/roles/${roleId}/permissions`, undefined, { notifyError: false });
  }

  /** The matrix is part of the role: it is saved from the role's revision (plan item 3.6); the screen shows a failure. */
  savePermissions(roleId: number, pairs: PermissionPair[], revision: number | undefined): Observable<unknown> {
    return this.api.put(`/iam/roles/${roleId}/permissions`, pairs, { notifyError: false, ifMatch: revision });
  }
}
