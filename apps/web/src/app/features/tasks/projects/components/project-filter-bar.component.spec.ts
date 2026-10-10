import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ProjectFilterBarComponent } from './project-filter-bar.component';

function render(inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({ imports: [ProjectFilterBarComponent] });
  const fixture = TestBed.createComponent(ProjectFilterBarComponent);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return fixture;
}

const el = (fixture: ComponentFixture<ProjectFilterBarComponent>) => fixture.nativeElement as HTMLElement;

const stateRadios = (fixture: ComponentFixture<ProjectFilterBarComponent>) => [
  ...el(fixture).querySelectorAll<HTMLElement>(
    '[role="radiogroup"][aria-label="Фильтр проектов по статусу"] [role="radio"]',
  ),
];

describe('ProjectFilterBarComponent', () => {
  it('shows the search text it was given in a labelled field', () => {
    const fixture = render({ searchQuery: 'склад' });
    const search = el(fixture).querySelector('#project-search') as HTMLInputElement;

    expect(search.value).toBe('склад');
    expect(search.getAttribute('aria-label')).toBe('Поиск проектов');
  });

  it('reports every edit of the search, and an emptied field as a cleared search', () => {
    const fixture = render();
    const search = el(fixture).querySelector('#project-search') as HTMLInputElement;
    const changed = vi.fn();
    const cleared = vi.fn();
    fixture.componentInstance.searchChange.subscribe(changed);
    fixture.componentInstance.clearSearch.subscribe(cleared);

    search.value = 'ск';
    search.dispatchEvent(new Event('input'));
    search.value = 'скл';
    search.dispatchEvent(new Event('input'));
    expect(changed.mock.calls).toEqual([['ск'], ['скл']]);

    search.value = '';
    search.dispatchEvent(new Event('input'));
    expect(cleared).toHaveBeenCalledTimes(1);
    expect(changed).toHaveBeenCalledTimes(2);
  });

  it('offers all, active and archived with the selected state checked', () => {
    const fixture = render({ selectedState: 'P' });
    const radios = stateRadios(fixture);

    expect(radios.map((radio) => radio.textContent?.trim())).toEqual(['Все', 'Активные', 'Архив']);
    expect(radios.map((radio) => radio.getAttribute('aria-checked'))).toEqual(['false', 'false', 'true']);
  });

  it('reports the state a person picks by its code', () => {
    const fixture = render();
    const picked = vi.fn();
    fixture.componentInstance.stateChange.subscribe(picked);

    stateRadios(fixture)[1].click();
    expect(picked).toHaveBeenCalledWith('A');
  });
});
