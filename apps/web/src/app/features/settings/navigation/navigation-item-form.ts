import { SchemaPathTree, required } from '@angular/forms/signals';
import { CustomNavigationItem, NavigationTargetType } from '@core/models/navigation.models';

/** What the menu item dialog edits. */
export interface NavigationItemForm {
  title: string;
  code: string;
  targetType: NavigationTargetType;
  sectionId: string;
  /** Null while the person has emptied the order field. */
  sortOrder: number | null;
  url: string;
  icon: string;
  /** `form.action` pair the item is limited to; null shows it to everyone. */
  requiredPermission: string | null;
}

/** The fields the server may name in a refusal, as the dialog calls them. */
export const NAVIGATION_ITEM_FIELDS = ['title', 'code', 'sortOrder', 'url', 'requiredPermission'] as const;

/** A new item: an embedded page in the custom section, ordered after the others. */
export function blankNavigationItem(sortOrder: number): NavigationItemForm {
  return {
    title: '',
    code: '',
    targetType: 'EMBEDDED_IFRAME',
    sectionId: 'custom',
    sortOrder,
    url: '',
    icon: 'analytics',
    requiredPermission: null,
  };
}

export function navigationItemForm(item: CustomNavigationItem): NavigationItemForm {
  return {
    title: item.title,
    code: item.code,
    targetType: item.targetType,
    sectionId: item.sectionId,
    sortOrder: item.sortOrder,
    url: item.url,
    icon: item.icon,
    requiredPermission: item.requiredPermission ?? null,
  };
}

/** Title, code and address are required: smt-control marks them "*" and shows the error on blur and on save. */
export function navigationItemRules(path: SchemaPathTree<NavigationItemForm>): void {
  required(path.title);
  required(path.code);
  required(path.url);
}
