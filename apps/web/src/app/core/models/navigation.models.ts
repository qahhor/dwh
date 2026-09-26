export type NavigationTargetType = 'INTERNAL_ROUTE' | 'EXTERNAL_LINK' | 'EMBEDDED_IFRAME';

export interface CustomNavigationItem {
  id: number;
  code: string;
  title: string;
  titleKey?: string | null;
  sectionId: string;
  parentId?: number | null;
  icon: string;
  targetType: NavigationTargetType;
  url: string;
  openInIframe: boolean;
  requiredPermission?: string | null;
  sortOrder: number;
  state: 'A' | 'P';
  createdAt?: string;
  modifiedAt?: string;
}

/** A catalog pair a menu item can be limited to, with its names. */
export interface NavigationPermissionChoice {
  permission: string;
  formName: string;
  actionName: string;
}

export interface CreateNavigationItemPayload {
  code: string;
  title: string;
  titleKey?: string | null;
  sectionId?: string;
  parentId?: number | null;
  icon?: string;
  targetType?: NavigationTargetType;
  url: string;
  openInIframe?: boolean;
  requiredPermission?: string | null;
  sortOrder?: number;
}

export interface UpdateNavigationItemPayload {
  code?: string;
  title?: string;
  titleKey?: string | null;
  sectionId?: string;
  parentId?: number | null;
  icon?: string;
  targetType?: NavigationTargetType;
  url?: string;
  openInIframe?: boolean;
  requiredPermission?: string | null;
  sortOrder?: number;
  state?: 'A' | 'P';
}

/** A side-menu item a declared entity brings (`GET /entities/menu`, roadmap item 57). */
export interface EntityMenuItem {
  code: string;
  /** The entity's right; the item shows with `form.view`. */
  form: string;
  route: string;
  labelKey: string;
  icon: string;
  /** The menu section: `workspace`, `iam` or `administration`. */
  section: string;
  order: number;
  /** The installed module whose switch hides the item; none — always on. */
  module: string | null;
}
