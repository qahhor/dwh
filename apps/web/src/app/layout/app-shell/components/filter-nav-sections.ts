import { NavItem, NavSection } from '../app-shell.models';

/** A menu search narrows existing links; it never grants access or changes their targets. */
export function filterNavSections(
  sections: readonly NavSection[],
  query: string,
  translate: (key: string) => string,
): NavSection[] {
  const needle = query.trim().toLocaleLowerCase();
  const matches = (text: string) => text.toLocaleLowerCase().includes(needle);
  const filterItems = (items: readonly NavItem[], ancestorMatches: boolean): NavItem[] =>
    items.flatMap((item) => {
      if (!item.permission()) return [];
      const nameMatches = ancestorMatches || matches(item.label || translate(item.labelKey || item.titleKey || ''));
      if (item.children?.length) {
        const children = filterItems(item.children, nameMatches);
        return children.length ? [{ ...item, children }] : [];
      }
      return nameMatches ? [item] : [];
    });
  return sections.flatMap((section) => {
    const items = filterItems(section.items, matches(translate(section.titleKey)));
    return items.length ? [{ ...section, items }] : [];
  });
}

export function countNavLinks(sections: readonly NavSection[]): number {
  const countItems = (items: readonly NavItem[]): number =>
    items.reduce((total, item) => total + (item.children?.length ? countItems(item.children) : 1), 0);
  return sections.reduce((total, section) => total + countItems(section.items), 0);
}
