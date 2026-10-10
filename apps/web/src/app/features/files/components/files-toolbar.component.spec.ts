import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { FilesToolbarComponent } from './files-toolbar.component';

function render(options: { scope?: 'all' | 'mine'; search?: string } = {}) {
  const fixture = TestBed.createComponent(FilesToolbarComponent);
  fixture.componentRef.setInput('scope', options.scope ?? 'all');
  fixture.componentRef.setInput('searchQuery', options.search ?? '');
  const component = fixture.componentInstance;
  const asked: string[] = [];
  component.scopeChange.subscribe((scope) => asked.push(`scope:${scope}`));
  component.searchQueryChange.subscribe((text) => asked.push(`text:${text}`));
  component.searchSubmit.subscribe(() => asked.push('search'));
  component.clear.subscribe(() => asked.push('clear'));
  component.refresh.subscribe(() => asked.push('refresh'));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const radios = () => [...host.querySelectorAll('[role="radio"]')] as HTMLElement[];
  const field = () => host.querySelector('#file-search') as HTMLInputElement;
  return { fixture, host, radios, field, asked };
}

describe('FilesToolbarComponent', () => {
  it('offers the two scopes as a named group with the chosen one checked', () => {
    const { host, radios } = render({ scope: 'mine' });

    expect(host.querySelector('[role="radiogroup"]')?.getAttribute('aria-label')).toBe('Область файлов');
    expect(radios()).toHaveLength(2);
    expect(radios()[0].textContent).toContain('Все файлы компании');
    expect(radios()[1].textContent).toContain('Мои файлы');
    expect(radios().map((radio) => radio.getAttribute('aria-checked'))).toEqual(['false', 'true']);
  });

  it('asks for the scope a person picks', () => {
    const { fixture, radios, asked } = render();

    radios()[1].click();
    fixture.detectChanges();

    expect(asked).toEqual(['scope:mine']);
  });

  it('passes the typed text on and searches on Enter', () => {
    const { field, host, asked } = render();
    expect(host.querySelector('#file-search')?.getAttribute('aria-label')).toBe('Поиск файлов');

    field().value = 'отчёт';
    field().dispatchEvent(new Event('input'));
    field().dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', bubbles: true }));

    expect(asked).toEqual(['text:отчёт', 'search']);
  });

  it('clears the search from the clear button of the box', () => {
    const { host, asked } = render({ search: 'отчёт' });

    (host.querySelector('.search-field .smt-input__action') as HTMLButtonElement).click();

    expect(asked).toContain('clear');
  });

  it('refreshes the list from its named button', () => {
    const { host, asked } = render();

    (host.querySelector('button[aria-label="Обновить список файлов"]') as HTMLButtonElement).click();

    expect(asked).toEqual(['refresh']);
  });
});
