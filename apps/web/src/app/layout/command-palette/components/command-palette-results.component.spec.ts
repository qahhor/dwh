import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import type { SearchHit, SearchResult } from '@core/models/search.models';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { translateTest } from '@testing/i18n-test.stub';
import { CommandPaletteResultsComponent } from './command-palette-results.component';

const HITS: SearchHit[] = [
  { entityType: 'TASK', id: '1', title: 'Сверка остатков', description: 'Склад №2', targetUrl: '/tasks/1' },
  { entityType: 'USER', id: '2', title: 'Иван Иванов', description: '', targetUrl: '/iam/users/2' },
  { entityType: 'NOTE', id: '3', title: 'Итоги встречи', description: 'Черновик', targetUrl: '/notes/3' },
];

function meta(overrides: Partial<SearchResult> = {}): SearchResult {
  return {
    query: 'св',
    totalHits: 3,
    foundHits: 3,
    hasMore: false,
    source: 'TYPESENSE',
    degraded: false,
    hits: HITS,
    ...overrides,
  };
}

describe('CommandPaletteResultsComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [CommandPaletteResultsComponent] });
  });

  function render(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(CommandPaletteResultsComponent);
    const defaults: Record<string, unknown> = {
      isLoading: false,
      errorMessage: '',
      retrySeconds: 0,
      searchQuery: 'св',
      results: [],
      recentSearches: [],
      selectedIndex: -1,
      listboxId: 'palette-list',
      validQuery: true,
    };
    for (const [name, value] of Object.entries({ ...defaults, ...inputs })) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    return { fixture, element };
  }

  it('lists the hits as options of a named listbox, the selected one marked, and picks one on click', () => {
    const { fixture, element } = render({ results: HITS, selectedIndex: 1, metadata: meta() });
    const picked: SearchHit[] = [];
    fixture.componentInstance.selectHit.subscribe((hit) => picked.push(hit));
    const listbox = element.querySelector('[role="listbox"]')!;
    const options = Array.from(listbox.querySelectorAll('[role="option"]')) as HTMLButtonElement[];

    expect(listbox.id).toBe('palette-list');
    expect(listbox.getAttribute('aria-label')).toBe(PACKAGED_RUSSIAN['search.results']);
    expect(options.map((option) => option.id)).toEqual([
      'palette-list-option-0',
      'palette-list-option-1',
      'palette-list-option-2',
    ]);
    expect(options.map((option) => option.getAttribute('aria-selected'))).toEqual(['false', 'true', 'false']);
    expect(options.map((option) => option.querySelector('.result-badge')?.textContent?.trim())).toEqual([
      PACKAGED_RUSSIAN['tasks.common.task'],
      PACKAGED_RUSSIAN['analytics.dashboard.employee'],
      PACKAGED_RUSSIAN['search.entity.note'],
    ]);
    expect(options[0].textContent).toContain('Склад №2');

    options[2].click();
    expect(picked).toEqual([HITS[2]]);
  });

  it('says how many hits came back, and when the count is unknown or more exist', () => {
    const found = render({ results: HITS, metadata: meta({ foundHits: 40, hasMore: true }) }).element;
    expect(found.querySelector('.palette-count')?.textContent).toContain(
      translateTest('search.returned_found', { returned: 3, found: 40 }),
    );
    expect(found.querySelector('.palette-count')?.textContent).toContain(PACKAGED_RUSSIAN['search.has_more']);

    const unknown = render({ results: HITS, metadata: meta({ foundHits: null, degraded: true }) }).element;
    expect(unknown.querySelector('.palette-count')?.textContent).toContain(
      translateTest('search.returned', { returned: 3 }),
    );
    expect(unknown.querySelector('.palette-degraded')?.getAttribute('role')).toBe('status');
  });

  it('announces loading, then an empty result naming the query', () => {
    const { fixture, element } = render({ isLoading: true });
    expect(element.querySelector('.palette-loading')?.getAttribute('role')).toBe('status');
    expect(element.querySelector('.palette-empty')).toBeNull();

    fixture.componentRef.setInput('isLoading', false);
    fixture.componentRef.setInput('metadata', meta({ totalHits: 0, foundHits: 0, hits: [] }));
    fixture.detectChanges();
    expect(element.querySelector('.palette-loading')).toBeNull();
    expect(element.querySelector('.palette-empty')?.textContent?.trim()).toBe(
      translateTest('layout.command_palette.nothing_found_for', { query: 'св' }),
    );
  });

  it('shows an error as an alert whose retry waits for the countdown and a valid query', () => {
    const { fixture, element } = render({ errorMessage: 'Сервис поиска недоступен', retrySeconds: 5 });
    const retries: unknown[] = [];
    fixture.componentInstance.retry.subscribe(() => retries.push(true));
    const alert = element.querySelector('[role="alert"]')!;
    const retry = alert.querySelector('button') as HTMLButtonElement;

    expect(alert.textContent).toContain('Сервис поиска недоступен');
    expect(alert.textContent).toContain(translateTest('search.retry_countdown', { seconds: 5 }));
    expect(retry.disabled).toBe(true);

    fixture.componentRef.setInput('retrySeconds', 0);
    fixture.componentRef.setInput('validQuery', false);
    fixture.detectChanges();
    expect(retry.disabled).toBe(true);

    fixture.componentRef.setInput('validQuery', true);
    fixture.detectChanges();
    retry.click();
    expect(retries).toHaveLength(1);
  });

  it('offers the recent searches below a short query and clears them on request', () => {
    const { fixture, element } = render({ searchQuery: ' я ', recentSearches: ['отчёт', 'склад'] });
    const events: string[] = [];
    fixture.componentInstance.selectRecent.subscribe((query) => events.push(`recent:${query}`));
    fixture.componentInstance.clearRecent.subscribe(() => events.push('clear'));
    const chips = Array.from(element.querySelectorAll('.recent-chip')) as HTMLButtonElement[];

    expect(element.querySelector('.palette-hint')?.textContent).toContain(
      PACKAGED_RUSSIAN['layout.command_palette.min_query_hint'],
    );
    expect(chips.map((chip) => chip.textContent?.trim())).toEqual([
      expect.stringContaining('отчёт'),
      expect.stringContaining('склад'),
    ]);

    chips[1].click();
    (element.querySelector('.recent-clear-btn') as HTMLButtonElement).click();
    expect(events).toEqual(['recent:склад', 'clear']);

    fixture.componentRef.setInput('searchQuery', 'ск');
    fixture.detectChanges();
    expect(element.querySelector('.palette-hint')).toBeNull();
  });
});
