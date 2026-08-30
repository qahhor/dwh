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
});
