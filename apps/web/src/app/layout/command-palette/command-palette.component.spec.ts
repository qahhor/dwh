import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SearchResult } from '../../core/models/search.models';
import { CommandPaletteService } from '../../core/services/command-palette.service';
import { CommandPaletteComponent } from './command-palette.component';

describe('CommandPaletteComponent', () => {
  async function createFixture(initiallyOpen = true) {
    const isOpen = signal(initiallyOpen);
    const service = {
      isOpen,
      open: vi.fn(() => isOpen.set(true)),
      close: vi.fn(() => isOpen.set(false)),
      toggle: vi.fn(() => isOpen.update(value => !value)),
      search: vi.fn((_query: string) => of<SearchResult>({ query: '', totalHits: 0, hits: [] }))
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
});
