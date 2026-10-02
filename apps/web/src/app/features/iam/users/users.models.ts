/** One effective right of a user and where it comes from: a role or a personal grant (FR-PERM-5). */
export interface EffectivePermissionItem {
  form: string;
  action: string;
  source: 'role' | 'personal';
}

export interface PersonalGrant {
  form: string;
  action: string;
}

export interface EffectivePermissionsResponse {
  items: EffectivePermissionItem[];
}

export interface PersonalPermissionsResponse {
  grants: PersonalGrant[];
}

/** The answer to a change of personal rights: the rights version and the user's new revision (plan item 3.6). */
export interface PermissionsSaved {
  permissionsVersion: number;
  revision: number;
}
