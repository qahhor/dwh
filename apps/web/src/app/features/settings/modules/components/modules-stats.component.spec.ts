import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ModulesStatsComponent } from './modules-stats.component';

describe('ModulesStatsComponent', () => {
  it('shows each module count under its own label', () => {
    const fixture = TestBed.createComponent(ModulesStatsComponent);
    fixture.componentRef.setInput('totalCount', 12);
    fixture.componentRef.setInput('activeCount', 9);
    fixture.componentRef.setInput('systemCount', 5);
    fixture.componentRef.setInput('customCount', 7);
    fixture.detectChanges();

    const cards = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.kpi')) as HTMLElement[];
    expect(cards.map((card) => card.getAttribute('aria-label'))).toEqual([
      'Всего модулей: 12',
      'Активных: 9',
      'Системных: 5',
      'Расширений: 7',
    ]);
  });
});
