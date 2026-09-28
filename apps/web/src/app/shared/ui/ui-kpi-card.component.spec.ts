import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { UiKpiCardComponent } from './ui-kpi-card.component';

@Component({
  imports: [UiKpiCardComponent],
  template: `
    <ui-kpi-card
      label="Просрочено"
      [value]="value()"
      [previous]="previous()"
      goodWhen="down"
      icon="warning"
      tone="danger"
      [alert]="alert()"
    >
      Требуют внимания
    </ui-kpi-card>
  `,
})
class HostComponent {
  readonly value = signal<number | string>(12);
  readonly previous = signal<number | null>(10);
  readonly alert = signal(true);
}

describe('ui-kpi-card', () => {
  function render() {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const card = () => fixture.nativeElement.querySelector('.kpi') as HTMLElement;
    return { fixture, card };
  }

  it('reads as one sentence: the figure and its change against the period before', () => {
    const { card } = render();

    expect(card().getAttribute('role')).toBe('group');
    expect(card().getAttribute('aria-label')).toMatch(/^Просрочено: 12, /);
    // More overdue tasks is bad when down is good.
    expect(card().querySelector('.kpi__change--bad')).not.toBeNull();
  });

  it('shows its icon in its tone, the note below and the alert frame', () => {
    const { card } = render();

    expect(card().querySelector('.kpi__icon--danger')?.textContent?.trim()).toBe('warning');
    expect(card().querySelector('.kpi__meta')?.textContent?.trim()).toBe('Требуют внимания');
    expect(card().classList).toContain('kpi--alert');
  });

  it('shows a text value as it is and compares only numbers', () => {
    const { fixture, card } = render();
    fixture.componentInstance.value.set('85%');
    fixture.componentInstance.alert.set(false);
    fixture.detectChanges();

    expect(card().querySelector('.kpi__value')?.textContent?.trim()).toBe('85%');
    expect(card().querySelector('[data-testid="kpi-change"]')).toBeNull();
    expect(card().classList).not.toContain('kpi--alert');
  });
});
