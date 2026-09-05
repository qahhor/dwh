import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiSearchableSelectComponent } from './ui-searchable-select.component';

describe('UiSearchableSelectComponent', () => {
  it('exposes its popup state and searchable listbox relationship', async () => {
    await TestBed.configureTestingModule({ imports: [UiSearchableSelectComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiSearchableSelectComponent);
    fixture.componentInstance.options = [{ id: 1, label: 'Первый вариант' }];
    fixture.detectChanges();

    const trigger = fixture.nativeElement.querySelector('.select-trigger') as HTMLButtonElement;
    const popupId = trigger.getAttribute('aria-controls');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(trigger.getAttribute('aria-haspopup')).toBe('listbox');
    expect(popupId).toBeTruthy();

    trigger.click();
    fixture.detectChanges();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector(`#${popupId}[role="listbox"]`)).not.toBeNull();
    expect(fixture.nativeElement.querySelector('input[aria-label="Поиск по вариантам"]')).not.toBeNull();
  });

  it('closes on Escape and returns focus to the trigger', async () => {
    await TestBed.configureTestingModule({ imports: [UiSearchableSelectComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiSearchableSelectComponent);
    fixture.detectChanges();
    const trigger = fixture.nativeElement.querySelector('.select-trigger') as HTMLButtonElement;
    trigger.click();
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(trigger);
  });

  it('emits remote searches and keeps retry and load-more actions outside the listbox', async () => {
    await TestBed.configureTestingModule({ imports: [UiSearchableSelectComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiSearchableSelectComponent);
    fixture.componentRef.setInput('remoteSearch', true);
    fixture.componentRef.setInput('loadError', true);
    fixture.componentRef.setInput('hasMore', true);
    const searches: string[] = [];
    let retries = 0;
    let loadMore = 0;
    const remote = fixture.componentInstance;
    remote.searchChange.subscribe(query => searches.push(query));
    remote.retry.subscribe(() => retries++);
    remote.loadMore.subscribe(() => loadMore++);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.select-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(searches).toEqual(['']);
    const input = fixture.nativeElement.querySelector('.search-input') as HTMLInputElement;
    input.value = '501';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    const listbox = fixture.nativeElement.querySelector('[role="listbox"]') as HTMLElement;
    const retry = fixture.nativeElement.querySelector('button.remote-retry') as HTMLButtonElement;
    retry.click();
    fixture.componentRef.setInput('loadError', false);
    fixture.detectChanges();
    const more = fixture.nativeElement.querySelector('button.remote-load-more') as HTMLButtonElement;
    more.click();

    expect(searches.at(-1)).toBe('501');
    expect(retries).toBe(1);
    expect(loadMore).toBe(1);
    expect(listbox.contains(retry)).toBe(false);
    expect(listbox.contains(more)).toBe(false);
  });

  it('shows an empty hint only after a successful empty remote lookup or for local empty options', async () => {
    await TestBed.configureTestingModule({ imports: [UiSearchableSelectComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiSearchableSelectComponent);
    fixture.componentRef.setInput('remoteSearch', true);
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.select-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.remote-loading')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.no-results-hint')).toBeNull();

    fixture.componentRef.setInput('loading', false);
    fixture.componentRef.setInput('loadError', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.remote-error')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.no-results-hint')).toBeNull();

    fixture.componentRef.setInput('loadError', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.no-results-hint')).not.toBeNull();

    fixture.componentRef.setInput('remoteSearch', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.no-results-hint')).not.toBeNull();
  });
});
