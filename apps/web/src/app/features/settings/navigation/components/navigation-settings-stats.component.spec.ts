import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { NavigationSettingsStatsComponent } from './navigation-settings-stats.component';

describe('NavigationSettingsStatsComponent', () => {
  function cards(counts: Record<string, number> = {}) {
    const fixture = TestBed.createComponent(NavigationSettingsStatsComponent);
    for (const [name, value] of Object.entries(counts)) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    return (Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.kpi')) as HTMLElement[]).map((card) =>
      card.getAttribute('aria-label'),
    );
  }

  it('shows each menu item count under its own label', () => {
    expect(cards({ totalCount: 8, activeCount: 6, embeddedCount: 3, externalCount: 4 })).toEqual([
      'Всего пунктов: 8',
      'Активных: 6',
      'Встроенных отчетов: 3',
      'Внешних ссылок: 4',
    ]);
  });

  it('shows zeros before the counts arrive', () => {
    expect(cards()).toEqual(['Всего пунктов: 0', 'Активных: 0', 'Встроенных отчетов: 0', 'Внешних ссылок: 0']);
  });
});
