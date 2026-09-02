import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiModalComponent } from './ui-modal.component';

describe('UiModalComponent', () => {
  it('labels the dialog with its visible title', async () => {
    await TestBed.configureTestingModule({ imports: [UiModalComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiModalComponent);
    fixture.componentRef.setInput('isOpen', true);
    fixture.componentRef.setInput('title', 'Восстановление пароля');
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
    const title = fixture.nativeElement.querySelector('h3') as HTMLElement;
    expect(title.id).not.toBe('');
    expect(dialog.getAttribute('aria-labelledby')).toBe(title.id);
  });

  it('does not close a non-dismissible dialog on Escape', async () => {
    await TestBed.configureTestingModule({ imports: [UiModalComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiModalComponent);
    fixture.componentRef.setInput('isOpen', true);
    fixture.componentRef.setInput('dismissible', false);
    let closes = 0;
    fixture.componentInstance.close.subscribe(() => closes++);
    fixture.detectChanges();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(closes).toBe(0);
  });

  it('locks background scrolling only while open', async () => {
    await TestBed.configureTestingModule({ imports: [UiModalComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiModalComponent);

    fixture.componentRef.setInput('isOpen', true);
    fixture.detectChanges();
    expect(document.body.classList.contains('modal-open')).toBe(true);

    fixture.componentRef.setInput('isOpen', false);
    fixture.detectChanges();
    expect(document.body.classList.contains('modal-open')).toBe(false);
  });
});
