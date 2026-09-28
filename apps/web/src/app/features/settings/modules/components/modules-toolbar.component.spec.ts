import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ModuleFilterTab } from '../modules.models';
import { ModulesToolbarComponent } from './modules-toolbar.component';

function render(searchQuery = '', filterTab: ModuleFilterTab = 'all') {
  const fixture = TestBed.createComponent(ModulesToolbarComponent);
  fixture.componentRef.setInput('searchQuery', searchQuery);
  fixture.componentRef.setInput('filterTab', filterTab);
  fixture.componentRef.setInput('totalCount', 12);
  fixture.componentRef.setInput('activeCount', 9);
  fixture.componentRef.setInput('systemCount', 5);
  fixture.componentRef.setInput('customCount', 7);
  const searches: string[] = [];
  const tabs: ModuleFilterTab[] = [];
  fixture.componentInstance.searchChange.subscribe((query) => searches.push(query));
  fixture.componentInstance.filterTabChange.subscribe((tab) => tabs.push(tab));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const tabButtons = () => Array.from(host.querySelectorAll('[role="tab"]')) as HTMLButtonElement[];
  const search = () => host.querySelector('input[type="search"]') as HTMLInputElement;
  return { fixture, host, tabButtons, search, searches, tabs };
}

describe('ModulesToolbarComponent', () => {
  it('offers the four filters with their counts in a named tab list, the current one chosen', () => {
    const { host, tabButtons } = render('', 'system');

    expect(host.querySelector('[role="tablist"]')?.getAttribute('aria-label')).toBe('Фильтр модулей');
    expect(tabButtons().map((tab) => tab.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      'Все 12',
      'Активные 9',
      'Системные 5',
      'Расширения 7',
    ]);
    expect(tabButtons().map((tab) => tab.getAttribute('aria-selected'))).toEqual(['false', 'false', 'true', 'false']);
  });

  it('asks for another filter when a tab is chosen', () => {
    const { tabButtons, tabs } = render();

    tabButtons()[3].click();

    expect(tabs).toEqual(['custom']);
  });

  it('shows the query in a named search field and sends what is typed', () => {
    const { search, searches } = render('iam');

    expect(search().value).toBe('iam');
    expect(search().getAttribute('aria-label')).toBe('Поиск модуля по названию или коду...');
    search().value = 'notes';
    search().dispatchEvent(new Event('input'));

    expect(searches).toEqual(['notes']);
  });

  it('sends an empty query when the search is cleared', () => {
    const { host, searches } = render('iam');

    (host.querySelector('.smt-input__action') as HTMLButtonElement).click();

    expect(searches).toEqual(['']);
  });
});
