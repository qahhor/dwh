import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { CustomNavigationItem } from '@core/models/navigation.models';
import { NavigationSettingsTableComponent } from './navigation-settings-table.component';

const SALES: CustomNavigationItem = {
  id: 1,
  code: 'superset-sales',
  title: 'Sales report',
  sectionId: 'workspace',
  icon: 'analytics',
  targetType: 'EMBEDDED_IFRAME',
  url: 'https://bi.example.test/dashboard/1/',
  openInIframe: true,
  sortOrder: 10,
  state: 'A',
};
const WIKI: CustomNavigationItem = {
  id: 2,
  code: 'wiki',
  title: 'Wiki',
  sectionId: 'partners',
  icon: 'menu_book',
  targetType: 'EXTERNAL_LINK',
  url: 'https://wiki.example.test/',
  openInIframe: false,
  sortOrder: 20,
  state: 'P',
};

function render(items: CustomNavigationItem[] = [SALES, WIKI], searchQuery = '') {
  const fixture = TestBed.createComponent(NavigationSettingsTableComponent);
  fixture.componentRef.setInput('items', items);
  fixture.componentRef.setInput('searchQuery', searchQuery);
  const asked: string[] = [];
  const component = fixture.componentInstance;
  component.toggleItem.subscribe((item) => asked.push(`toggle:${item.id}`));
  component.previewItem.subscribe((item) => asked.push(`preview:${item.id}`));
  component.editItem.subscribe((item) => asked.push(`edit:${item.id}`));
  component.deleteItem.subscribe((item) => asked.push(`delete:${item.id}`));
  component.searchQueryChange.subscribe((query) => asked.push(`search:${query}`));
  component.clearSearch.subscribe(() => asked.push('clear'));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const rows = () => Array.from(host.querySelectorAll('.nav-table [role="rowgroup"] > [role="row"]')) as HTMLElement[];
  const named = (label: string) => host.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement;
  return { fixture, host, rows, named, asked };
}

describe('NavigationSettingsTableComponent', () => {
  it('shows one row per menu item with its title, code, type, section and link', () => {
    const { host, rows } = render();

    expect(host.querySelector('[role="table"]')?.getAttribute('aria-label')).toBe('Пункты меню');
    expect(rows()).toHaveLength(2);
    const sales = rows()[0].textContent!;
    expect(sales).toContain('Sales report');
    expect(sales).toContain('superset-sales');
    expect(sales).toContain('Встроенный фрейм');
    expect(sales).toContain('Рабочее пространство');
    expect(sales).toContain('https://bi.example.test/dashboard/1/');
    expect(rows()[1].textContent).toContain('Внешняя ссылка');
  });

  it('shows a section it does not know by its code', () => {
    const { rows } = render();

    expect(rows()[1].querySelector('.badge-section')?.textContent?.trim()).toBe('partners');
  });

  it('shows each item as active or blocked on a named toggle that asks to flip it', () => {
    const { named, asked } = render();

    const active = named('Активен: «Sales report»');
    const blocked = named('Активен: «Wiki»');
    expect(active.getAttribute('aria-pressed')).toBe('true');
    expect(active.textContent?.trim()).toBe('Активен');
    expect(blocked.getAttribute('aria-pressed')).toBe('false');
    expect(blocked.textContent?.trim()).toBe('Заблокирован');

    blocked.click();
    expect(asked).toEqual(['toggle:2']);
  });

  it('names every row action after its item and asks for it with that item', () => {
    const { named, asked } = render();

    named('Открыть «Sales report»').click();
    named('Изменить «Wiki»').click();
    named('Удалить «Sales report»').click();

    expect(asked).toEqual(['preview:1', 'edit:2', 'delete:1']);
  });

  it('sends a typed search, and says when the search is cleared', () => {
    const { host, asked } = render([SALES, WIKI], 'sal');
    const search = host.querySelector('input[type="search"]') as HTMLInputElement;

    expect(search.getAttribute('aria-label')).toBe('Поиск по названию, коду или URL…');
    search.value = 'wiki';
    search.dispatchEvent(new Event('input'));
    (host.querySelector('.smt-input__action') as HTMLButtonElement).click();

    expect(asked).toEqual(['search:wiki', 'search:', 'clear']);
  });

  it('says there is nothing to show when there are no items', () => {
    const { host, rows } = render([]);

    expect(rows()).toHaveLength(0);
    expect(host.textContent).toContain('Нет данных для отображения');
  });
});
