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
});
