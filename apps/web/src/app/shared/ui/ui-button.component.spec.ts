import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiButtonComponent } from './ui-button.component';

describe('UiButtonComponent', () => {
  it('exposes loading and full-width state on the native button', async () => {
    await TestBed.configureTestingModule({ imports: [UiButtonComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiButtonComponent);
    fixture.componentInstance.loading = true;
    fixture.componentInstance.fullWidth = true;
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('aria-busy')).toBe('true');
    expect(button.classList.contains('btn-full-width')).toBe(true);
    expect(button.querySelector('.sr-only')?.textContent).toContain('Выполняется');
  });

  it('provides an accessible name for icon-only buttons', async () => {
    await TestBed.configureTestingModule({ imports: [UiButtonComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiButtonComponent);
    fixture.componentInstance.icon = 'close';
    fixture.componentInstance.ariaLabel = 'Закрыть';
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(button.getAttribute('aria-label')).toBe('Закрыть');
  });
});
