import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { CustomFieldsToolbarComponent } from './custom-fields-toolbar.component';

describe('CustomFieldsToolbarComponent', () => {
  function setup(
    inputs: { selectedEntity?: string; searchQuery?: string; entityCounts?: Record<string, number> } = {},
  ) {
    const fixture = TestBed.createComponent(CustomFieldsToolbarComponent);
    fixture.componentRef.setInput('selectedEntity', inputs.selectedEntity ?? 'ALL');
    fixture.componentRef.setInput('searchQuery', inputs.searchQuery ?? '');
    fixture.componentRef.setInput('entityCounts', inputs.entityCounts ?? {});
    const entity = vi.fn();
    const search = vi.fn();
    fixture.componentInstance.entityChange.subscribe(entity);
    fixture.componentInstance.searchQueryChange.subscribe(search);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const chips = () => Array.from(host.querySelectorAll('[role="radio"]')) as HTMLElement[];
    return { fixture, host, chips, entity, search };
  }

  it('offers one named chip per entity type with the number of its fields', () => {
    const { host, chips } = setup({ entityCounts: { ALL: 5, USER: 3, TASK: 2 } });

    expect(host.querySelector('[role="radiogroup"]')?.getAttribute('aria-label')).toBe('Фильтр по типу сущности');
    expect(chips()).toHaveLength(5);
    const labels = chips().map((chip) => chip.querySelector('.smt-radio-group__label')?.textContent?.trim());
    expect(labels[0]).toMatch(/5$/);
    expect(labels[1]).toMatch(/3$/);
    // A type without fields shows zero rather than nothing.
    expect(labels[2]).toMatch(/0$/);
  });

  it('marks the chosen entity and asks for another on a click', () => {
    const { chips, entity } = setup({ selectedEntity: 'USER' });

    expect(chips().map((chip) => chip.getAttribute('aria-checked'))).toEqual([
      'false',
      'true',
      'false',
      'false',
      'false',
    ]);
    chips()[3].click();

    expect(entity).toHaveBeenCalledWith('TASK');
  });

  it('shows the query it was given in a labelled search field and reports typing', () => {
    const { host, search } = setup({ searchQuery: 'inn' });
    const field = host.querySelector('input[type="search"]') as HTMLInputElement;

    expect(field.value).toBe('inn');
    expect(field.getAttribute('aria-label')).toBe('Поиск по коду или названию…');
    field.value = 'budget';
    field.dispatchEvent(new Event('input'));

    expect(search).toHaveBeenLastCalledWith('budget');
  });

  it('empties the query when the clear button is pressed', () => {
    const { fixture, host, search } = setup({ searchQuery: 'inn' });

    (host.querySelector('.smt-input__action') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(search).toHaveBeenLastCalledWith('');
  });
});
