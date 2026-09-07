import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SearchResult } from '../../core/models/search.models';
import { CommandPaletteService } from '../../core/services/command-palette.service';
import { CommandPaletteComponent } from './command-palette.component';

const metadata = { foundHits: 0, hasMore: false, source: 'TYPESENSE' as const, degraded: false };

describe('CommandPaletteComponent', () => {
  async function createFixture(initiallyOpen = true) {
    const isOpen = signal(initiallyOpen);
    const service = {
      isOpen,
      open: vi.fn(() => isOpen.set(true)),
      close: vi.fn(() => isOpen.set(false)),
      toggle: vi.fn(() => isOpen.update(value => !value)),
      search: vi.fn((_query: string) => of<SearchResult>({ ...metadata, query: '', totalHits: 0, hits: [] }))
    };
    await TestBed.configureTestingModule({
      imports: [CommandPaletteComponent],
      providers: [
        { provide: CommandPaletteService, useValue: service },
        { provide: Router, useValue: { navigate: vi.fn() } }
      ]
    }).compileComponents();
    return { fixture: TestBed.createComponent(CommandPaletteComponent), service };
  }

  it('exposes a named modal combobox and listbox options', async () => {
    const { fixture } = await createFixture();
    fixture.detectChanges();
    fixture.componentInstance.results.set([{
      entityType: 'TASK',
      id: '42',
      title: 'Проверить отчёт',
      description: 'Финальная проверка',
      targetUrl: '/tasks/42'
    }]);
    fixture.detectChanges();

    const dialog = fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
    const input = fixture.nativeElement.querySelector('[role="combobox"]') as HTMLInputElement;
    const listbox = fixture.nativeElement.querySelector('[role="listbox"]') as HTMLElement;
    const option = fixture.nativeElement.querySelector('[role="option"]') as HTMLButtonElement;

    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-labelledby')).not.toBeNull();
    expect(input.getAttribute('aria-controls')).toBe(listbox.id);
    expect(input.getAttribute('aria-expanded')).toBe('true');
    expect(input.getAttribute('aria-activedescendant')).toBe(option.id);
    expect(option.getAttribute('aria-selected')).toBe('true');
    expect(option.type).toBe('button');
  });

  it('restores focus after Escape closes the palette', async () => {
    const trigger = document.createElement('button');
    trigger.textContent = 'Открыть поиск';
    document.body.appendChild(trigger);
    trigger.focus();

    const { fixture, service } = await createFixture(false);
    fixture.detectChanges();
    service.open();
    fixture.detectChanges();
    await Promise.resolve();

    expect(document.activeElement).toBe(fixture.nativeElement.querySelector('input'));

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    await Promise.resolve();

    expect(service.close).toHaveBeenCalled();
    expect(document.activeElement).toBe(trigger);
    trigger.remove();
  });

  it('shows a recoverable inline error and can retry the same query', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, service } = await createFixture();
      service.search
        .mockReturnValueOnce(throwError(() => ({ detail: 'Поиск временно недоступен' })))
        .mockReturnValueOnce(of({
          ...metadata,
          query: 'Тест',
          totalHits: 1,
          hits: [{
            entityType: 'TASK',
            id: '42',
            title: 'Тестовая задача',
            description: 'Результат повторного запроса',
            targetUrl: '/tasks/items/42'
          }]
        }));
      fixture.detectChanges();

      fixture.componentInstance.searchQuery = 'Тест';
      fixture.componentInstance.onSearchChange('Тест');
      await vi.advanceTimersByTimeAsync(121);
      fixture.detectChanges();

      const error = fixture.nativeElement.querySelector('.palette-error[role="alert"]') as HTMLElement;
      expect(error.textContent).toContain('Поиск временно недоступен');
      expect(fixture.componentInstance.isLoading()).toBe(false);

      (fixture.nativeElement.querySelector('.palette-retry') as HTMLButtonElement).click();
      await vi.advanceTimersByTimeAsync(121);
      fixture.detectChanges();

      expect(service.search).toHaveBeenCalledTimes(2);
      expect(fixture.nativeElement.querySelector('.result-title')?.textContent).toContain('Тестовая задача');
      expect(fixture.nativeElement.querySelector('.palette-error')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('removes old options immediately when the query changes', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, service } = await createFixture();
      service.search.mockReturnValue(of({ ...metadata, query: 'old', totalHits: 1, hits: [{
        entityType: 'USER', id: '7', title: 'Old result', description: '', targetUrl: '/iam/users/7'
      }] }));
      fixture.detectChanges();
      fixture.componentInstance.searchQuery = 'old';
      fixture.componentInstance.onSearchChange('old');
      await vi.advanceTimersByTimeAsync(121);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[role="option"]')).not.toBeNull();

      fixture.componentInstance.searchQuery = 'new';
      fixture.componentInstance.onSearchChange('new');
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('[role="option"]')).toBeNull();
      expect(fixture.nativeElement.querySelector('[role="status"]')?.textContent).toContain('Поиск');
    } finally {
      vi.useRealTimers();
    }
  });

  it.each(['new', ' ', ''])('cancels the old request immediately for the next input %j', async (query) => {
    vi.useFakeTimers();
    try {
      const { fixture, service } = await createFixture();
      const pending = new Subject<SearchResult>();
      service.search.mockReturnValue(pending.asObservable());
      fixture.detectChanges();
      fixture.componentInstance.onSearchChange('old');
      await vi.advanceTimersByTimeAsync(121);
      expect(pending.observed).toBe(true);

      fixture.componentInstance.onSearchChange(query);

      expect(pending.observed).toBe(false);
      fixture.destroy();
    } finally {
      vi.useRealTimers();
    }
  });

  it('cancels a closed search so its response cannot populate a reopened empty palette', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, service } = await createFixture();
      const pending = new Subject<SearchResult>();
      service.search.mockReturnValue(pending.asObservable());
      fixture.detectChanges();
      fixture.componentInstance.searchQuery = 'old';
      fixture.componentInstance.onSearchChange('old');
      await vi.advanceTimersByTimeAsync(121);

      service.close();
      fixture.detectChanges();
      expect(pending.observed).toBe(false);
      service.open();
      fixture.detectChanges();
      pending.next({ ...metadata, query: 'old', totalHits: 1, hits: [{
        entityType: 'USER', id: '7', title: 'Late result', description: '', targetUrl: '/iam/users/7'
      }] });
      fixture.detectChanges();

      expect(fixture.componentInstance.searchQuery).toBe('');
      expect(fixture.componentInstance.isLoading()).toBe(false);
      expect(fixture.nativeElement.querySelector('[role="option"]')).toBeNull();
      expect(fixture.nativeElement.querySelector('.palette-hint')).not.toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('does not retain an error when reopened with the header button', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, service } = await createFixture();
      service.search.mockReturnValue(throwError(() => ({ detail: 'Temporary failure' })));
      fixture.detectChanges();
      fixture.componentInstance.searchQuery = 'old';
      fixture.componentInstance.onSearchChange('old');
      await vi.advanceTimersByTimeAsync(121);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();

      service.close();
      fixture.detectChanges();
      service.open();
      fixture.detectChanges();

      expect(fixture.componentInstance.searchQuery).toBe('');
      expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  it('cancels an active request when the shell is destroyed', async () => {
    vi.useFakeTimers();
    try {
      const { fixture, service } = await createFixture();
      const pending = new Subject<SearchResult>();
      service.search.mockReturnValue(pending.asObservable());
      fixture.detectChanges();
      fixture.componentInstance.onSearchChange('old');
      await vi.advanceTimersByTimeAsync(121);
      fixture.destroy();

      expect(pending.observed).toBe(false);
      expect(service.isOpen()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('provides a real close button for touch and keyboard users', async () => {
    const { fixture, service } = await createFixture();
    fixture.detectChanges();
    const close = fixture.nativeElement.querySelector('.palette-close') as HTMLButtonElement | null;

    expect(close).not.toBeNull();
    expect(close?.getAttribute('aria-label')).toBe('Закрыть поиск');
    close?.click();
    fixture.detectChanges();
    expect(service.isOpen()).toBe(false);
  });

  it('leaves Enter on a focused result to the result button instead of opening another option', async () => {
    const { fixture } = await createFixture();
    fixture.detectChanges();
    fixture.componentInstance.results.set([
      { entityType: 'TASK', id: '1', title: 'Task', description: '', targetUrl: '/tasks/1' },
      { entityType: 'USER', id: '7', title: 'Person', description: '', targetUrl: '/iam/users/7' }
    ]);
    fixture.detectChanges();
    const option = fixture.nativeElement.querySelectorAll('[role="option"]')[1] as HTMLButtonElement;
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    option.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
    expect(TestBed.inject(Router).navigate).not.toHaveBeenCalled();
    option.click();
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/iam/users', '7']);
  });

  it('supports the physical search shortcut on a non-Latin keyboard layout without repeating it', async () => {
    const { fixture, service } = await createFixture(false);
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'л', code: 'KeyK', ctrlKey: true }));
    fixture.detectChanges();
    expect(service.isOpen()).toBe(true);

    const repeatedShortcut = new KeyboardEvent('keydown', { key: 'л', code: 'KeyK', ctrlKey: true, repeat: true, cancelable: true });
    document.dispatchEvent(repeatedShortcut);
    fixture.detectChanges();
    expect(service.isOpen()).toBe(true);
    expect(repeatedShortcut.defaultPrevented).toBe(true);
  });
});
