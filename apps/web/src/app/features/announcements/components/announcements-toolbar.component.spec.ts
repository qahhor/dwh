import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { AnnouncementsToolbarComponent } from './announcements-toolbar.component';

function render(options: { filter?: 'ALL' | 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'; search?: string } = {}) {
  const fixture = TestBed.createComponent(AnnouncementsToolbarComponent);
  fixture.componentRef.setInput('totalCount', 6);
  fixture.componentRef.setInput('publishedCount', 3);
  fixture.componentRef.setInput('draftCount', 2);
  fixture.componentRef.setInput('archivedCount', 1);
  fixture.componentRef.setInput('statusFilter', options.filter ?? 'ALL');
  fixture.componentRef.setInput('searchQuery', options.search ?? '');
  const filter = vi.fn();
  const search = vi.fn();
  const cleared = vi.fn();
  fixture.componentInstance.filterChange.subscribe(filter);
  fixture.componentInstance.searchChange.subscribe(search);
  fixture.componentInstance.searchClear.subscribe(cleared);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const tabs = () => [...host.querySelectorAll('[role="tab"]')] as HTMLButtonElement[];
  const field = () => host.querySelector('.search-box input') as HTMLInputElement;
  const type = (text: string) => {
    field().value = text;
    field().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  return { fixture, host, tabs, field, type, filter, search, cleared };
}

describe('AnnouncementsToolbarComponent', () => {
  it('offers a named tab per state with its count and marks the chosen one', () => {
    const { host, tabs } = render({ filter: 'DRAFT' });

    expect(host.querySelector('[role="tablist"]')?.getAttribute('aria-label')).toBe('Все');
    expect(tabs().map((tab) => tab.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      'Все 6',
      'Опубликованные 3',
      'Черновики 2',
      'В архиве 1',
    ]);
    expect(tabs().map((tab) => tab.getAttribute('aria-selected'))).toEqual(['false', 'false', 'true', 'false']);
  });

  it('asks for the state a person picks', () => {
    const { tabs, filter } = render();

    tabs()[3].click();

    expect(filter).toHaveBeenCalledWith('ARCHIVED');
  });

  it('passes the typed text on as the search', () => {
    const { field, type, search, cleared } = render();
    expect(field().getAttribute('aria-label')).toBe('Поиск по объявлениям…');

    type('релиз');

    expect(search).toHaveBeenCalledWith('релиз');
    expect(cleared).not.toHaveBeenCalled();
  });

  it('clears the search when the box is emptied by typing', () => {
    const { type, search, cleared } = render({ search: 'релиз' });

    type('');

    expect(cleared).toHaveBeenCalledTimes(1);
    expect(search).not.toHaveBeenCalled();
  });

  it('clears the search from the clear button of the box', () => {
    const { host, search, cleared } = render({ search: 'релиз' });

    (host.querySelector('.search-box .smt-input__action') as HTMLButtonElement).click();

    expect(cleared).toHaveBeenCalledTimes(1);
    expect(search).not.toHaveBeenCalled();
  });
});
