import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ToastService } from '../../core/services/toast.service';
import { UiToastContainerComponent } from './ui-toast.component';

describe('UiToastContainerComponent', () => {
  it('announces an error and names its dismiss action', async () => {
    await TestBed.configureTestingModule({ imports: [UiToastContainerComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiToastContainerComponent);
    TestBed.inject(ToastService).show('error', 'Сохранение не выполнено', undefined, 0);
    fixture.detectChanges();

    const toast = fixture.nativeElement.querySelector('.toast-error') as HTMLElement;
    const close = fixture.nativeElement.querySelector('.toast-close') as HTMLButtonElement;
    expect(toast.getAttribute('role')).toBe('alert');
    expect(toast.getAttribute('aria-live')).toBe('assertive');
    expect(close.type).toBe('button');
    expect(close.getAttribute('aria-label')).toBe('Закрыть уведомление');
  });

  it('announces non-error feedback without interrupting the user', async () => {
    await TestBed.configureTestingModule({ imports: [UiToastContainerComponent] }).compileComponents();
    const fixture = TestBed.createComponent(UiToastContainerComponent);
    TestBed.inject(ToastService).show('success', 'Сохранено', undefined, 0);
    fixture.detectChanges();

    const toast = fixture.nativeElement.querySelector('.toast-success') as HTMLElement;
    expect(toast.getAttribute('role')).toBe('status');
    expect(toast.getAttribute('aria-live')).toBe('polite');
  });
});
