import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LiveAnnouncerService } from '../../core/services/live-announcer.service';
import { MAX_VISIBLE_TOASTS, ToastService } from '../../core/services/toast.service';
import { UiToastContainerComponent } from './ui-toast.component';

describe('UiToastContainerComponent', () => {
  let announce: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    announce = vi.fn();
    await TestBed.configureTestingModule({
      imports: [UiToastContainerComponent],
      providers: [{ provide: LiveAnnouncerService, useValue: { announce } }],
    }).compileComponents();
  });

  afterEach(() => vi.useRealTimers());

  function render() {
    const fixture = TestBed.createComponent(UiToastContainerComponent);
    return { fixture, toasts: TestBed.inject(ToastService) };
  }

  it('announces an error through its alert role only, and names its dismiss action', () => {
    const { fixture, toasts } = render();
    toasts.show('error', 'Сохранение не выполнено', 'Ошибка', 0);
    fixture.detectChanges();

    const toast = fixture.nativeElement.querySelector('.toast-error') as HTMLElement;
    const close = fixture.nativeElement.querySelector('.toast-close') as HTMLButtonElement;
    expect(toast.getAttribute('role')).toBe('alert');
    expect(announce).not.toHaveBeenCalled();
    expect(toast.textContent).toContain('Сохранение не выполнено');
    expect(close.type).toBe('button');
    expect(close.getAttribute('aria-label')).toBe('Закрыть уведомление');
  });

  it('announces other feedback politely, with its title, and keeps live roles off the toast', () => {
    const { fixture, toasts } = render();
    toasts.show('success', 'Сохранено', 'Настройки', 0);
    fixture.detectChanges();

    const toast = fixture.nativeElement.querySelector('.toast-success') as HTMLElement;
    expect(announce).toHaveBeenCalledWith('Настройки. Сохранено');
    expect(toast.hasAttribute('role')).toBe(false);
    expect(toast.hasAttribute('aria-live')).toBe(false);
  });

  it('holds the dismiss countdown while hovered or focused and continues with the time left', () => {
    vi.useFakeTimers();
    const { fixture, toasts } = render();
    toasts.show('info', 'Импорт запущен', undefined, 1000);
    fixture.detectChanges();
    const toast = fixture.nativeElement.querySelector('.toast-info') as HTMLElement;

    vi.advanceTimersByTime(600);
    toast.dispatchEvent(new Event('mouseenter'));
    vi.advanceTimersByTime(5000);
    expect(toasts.toasts()).toHaveLength(1);

    toast.dispatchEvent(new Event('mouseleave'));
    vi.advanceTimersByTime(300);
    expect(toasts.toasts()).toHaveLength(1);
    vi.advanceTimersByTime(150);
    expect(toasts.toasts()).toHaveLength(0);

    toasts.show('info', 'Снова', undefined, 1000);
    fixture.detectChanges();
    const again = fixture.nativeElement.querySelector('.toast-info') as HTMLElement;
    again.dispatchEvent(new FocusEvent('focusin'));
    vi.advanceTimersByTime(5000);
    expect(toasts.toasts()).toHaveLength(1);
  });

  it('restarts a repeated message instead of stacking a copy', () => {
    vi.useFakeTimers();
    const { toasts } = render();

    const first = toasts.show('warning', 'Сервер недоступен', 'Связь', 1000);
    vi.advanceTimersByTime(800);
    const second = toasts.show('warning', 'Сервер недоступен', 'Связь', 1000);
    vi.advanceTimersByTime(800);

    expect(second).toBe(first);
    expect(toasts.toasts()).toHaveLength(1);
    expect(announce).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(250);
    expect(toasts.toasts()).toHaveLength(0);
  });

  it(`keeps at most ${MAX_VISIBLE_TOASTS} toasts, dropping the oldest`, () => {
    const { toasts } = render();

    for (let index = 1; index <= MAX_VISIBLE_TOASTS + 2; index++) {
      toasts.show('info', `Сообщение ${index}`, undefined, 0);
    }

    expect(toasts.toasts().map(toast => toast.message)).toEqual(
      Array.from({ length: MAX_VISIBLE_TOASTS }, (_, i) => `Сообщение ${i + 3}`)
    );
  });
});
