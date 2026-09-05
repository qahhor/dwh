import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { User } from '../../core/models/auth.models';
import { UiUserMultiSelectComponent } from './ui-user-multi-select.component';

const user: User = {
  id: 1,
  name: 'Иван Иванов',
  login: 'ivan',
  email: 'ivan@example.com',
  state: 'A',
  language: 'ru',
  timezone: 'Asia/Tashkent',
  attributes: {},
  is2faEnabled: false,
  forcePasswordChange: false,
  createdAt: '2026-08-30T00:00:00Z',
  modifiedAt: '2026-08-30T00:00:00Z'
};

describe('UiUserMultiSelectComponent', () => {
  it('uses a named trigger and multi-select listbox', async () => {
    await TestBed.configureTestingModule({ imports: [UiUserMultiSelectComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiUserMultiSelectComponent);
    fixture.componentInstance.users = [user];
    fixture.componentInstance.selectedUserIds = [user.id];
    fixture.detectChanges();

    const trigger = fixture.nativeElement.querySelector('.add-user-btn') as HTMLButtonElement;
    const popupId = trigger.getAttribute('aria-controls');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(trigger.getAttribute('aria-haspopup')).toBe('listbox');
    expect(fixture.nativeElement.querySelector('button[aria-label="Удалить Иван Иванов"]')).not.toBeNull();

    trigger.click();
    fixture.detectChanges();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(fixture.nativeElement.querySelector(`#${popupId}[role="listbox"][aria-multiselectable="true"]`)).not.toBeNull();
  });

  it('closes on Escape and returns focus to the add-user trigger', async () => {
    await TestBed.configureTestingModule({ imports: [UiUserMultiSelectComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiUserMultiSelectComponent);
    fixture.componentInstance.users = [user];
    fixture.detectChanges();
    const trigger = fixture.nativeElement.querySelector('.add-user-btn') as HTMLButtonElement;
    trigger.click();
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(trigger);
  });

  it('emits remote searches and exposes non-option loading and paging controls', async () => {
    await TestBed.configureTestingModule({ imports: [UiUserMultiSelectComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiUserMultiSelectComponent);
    fixture.componentRef.setInput('remoteSearch', true);
    fixture.componentRef.setInput('loading', true);
    fixture.componentRef.setInput('hasMore', true);
    const searches: string[] = [];
    let loadMore = 0;
    const remote = fixture.componentInstance;
    remote.searchChange.subscribe(query => searches.push(query));
    remote.loadMore.subscribe(() => loadMore++);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.add-user-btn') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(searches).toEqual(['']);
    const input = fixture.nativeElement.querySelector('.search-input') as HTMLInputElement;
    input.value = 'user501';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();

    const listbox = fixture.nativeElement.querySelector('[role="listbox"]') as HTMLElement;
    const status = fixture.nativeElement.querySelector('.remote-loading[role="status"]') as HTMLElement;
    fixture.componentRef.setInput('loading', false);
    fixture.detectChanges();
    const more = fixture.nativeElement.querySelector('button.remote-load-more') as HTMLButtonElement;
    more.click();

    expect(searches.at(-1)).toBe('user501');
    expect(loadMore).toBe(1);
    expect(listbox.contains(status)).toBe(false);
    expect(listbox.contains(more)).toBe(false);
  });

  it('shows an empty hint only after a successful empty remote lookup or for local empty users', async () => {
    await TestBed.configureTestingModule({ imports: [UiUserMultiSelectComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiUserMultiSelectComponent);
    fixture.componentRef.setInput('remoteSearch', true);
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.add-user-btn') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.remote-loading')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.no-options')).toBeNull();

    fixture.componentRef.setInput('loading', false);
    fixture.componentRef.setInput('loadError', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.remote-error')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.no-options')).toBeNull();

    fixture.componentRef.setInput('loadError', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.no-options')).not.toBeNull();

    fixture.componentRef.setInput('remoteSearch', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.no-options')).not.toBeNull();
  });
});
