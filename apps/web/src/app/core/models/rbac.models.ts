export interface Role {
  id: number;
  name: string;
  pcode?: string;
  state: 'A' | 'P';
  orderNo: number;
  createdAt: string;
  modifiedAt: string;
  usersCount?: number;
  /** What a change of the record names in If-Match (plan item 3.6). */
  revision?: number;
}

export interface FormTreeItem {
  formCode: string;
  module: string;
  formName: string;
  action: string;
  actionName: string;
}

export interface PermissionPair {
  formCode: string;
  action: string;
}
