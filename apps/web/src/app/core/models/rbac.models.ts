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
  /** Set for a declared entity's right: the dictionary key of the form's name (ADR-0031, plan 10/10, item 5.0). */
  formNameKey?: string | null;
  action: string;
  actionName: string;
  /** Set for a declared entity's right: the dictionary key of the action's name. */
  actionNameKey?: string | null;
}

/** The catalog in the viewer's language: names that come as dictionary keys are translated, others stay. */
export function namedCatalog<T extends FormTreeItem>(items: readonly T[], translate: (key: string) => string): T[] {
  return items.map((item) =>
    item.formNameKey || item.actionNameKey
      ? {
          ...item,
          formName: item.formNameKey ? translate(item.formNameKey) : item.formName,
          actionName: item.actionNameKey ? translate(item.actionNameKey) : item.actionName,
        }
      : item,
  );
}

export interface PermissionPair {
  formCode: string;
  action: string;
}
