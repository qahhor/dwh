import { SearchCategory } from '@core/models/search.models';

export const RECENT_SEARCHES_STORAGE_KEY = 'smartupcms_recent_searches';
export const MAX_RECENT_SEARCHES = 6;

/** The icon of an entity without a menu item of its own. */
export const DEFAULT_CATEGORY_ICON = 'manage_search';

export interface CategoryItem {
  value: string;
  label: string;
  icon: string;
}

/** A category of the palette: the entity's code, the dictionary key of its name (or the code) and its icon. */
export function categoryItem(category: SearchCategory): CategoryItem {
  return {
    value: category.code,
    label: category.labelKey ?? category.code,
    icon: category.icon ?? DEFAULT_CATEGORY_ICON,
  };
}
