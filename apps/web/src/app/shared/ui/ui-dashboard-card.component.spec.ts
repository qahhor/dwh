import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { UiDashboardCardComponent } from './ui-dashboard-card.component';

@Component({
  imports: [UiDashboardCardComponent],
  template: `
    <ui-dashboard-card
      title="Загрузки за неделю"
      [subtitle]="subtitle()"
      [loading]="loading()"
      [failed]="failed()"
      [empty]="empty()"
      [errorText]="errorText()"
      [emptyText]="emptyText()"
      (retry)="retries = retries + 1"
    >
      <button cardActions type="button" class="action">Все загрузки</button>
      <p class="data">12 пакетов</p>
    </ui-dashboard-card>
  `,
})
class HostComponent {
  readonly subtitle = signal('');
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly empty = signal(false);
  readonly errorText = signal('');
  readonly emptyText = signal('');
  retries = 0;
}

describe('ui-dashboard-card', () => {
  function render(setup: (host: HostComponent) => void = () => undefined) {
    const fixture = TestBed.createComponent(HostComponent);
    setup(fixture.componentInstance);
    fixture.detectChanges();
    const card = () => fixture.nativeElement.querySelector('section') as HTMLElement;
    const state = (name: string) => card().querySelector(`[data-testid="dash-card-${name}"]`) as HTMLElement | null;
    return { fixture, card, state };
  }

  it('is a region named by its title, with its actions in the header and the data in the body', () => {
    const { card } = render((host) => host.subtitle.set('Последние 7 дней'));
    const title = card().querySelector('h2')!;

    expect(card().getAttribute('aria-labelledby')).toBe(title.id);
    expect(title.textContent).toBe('Загрузки за неделю');
    expect(card().querySelector('.dash-card__subtitle')?.textContent).toBe('Последние 7 дней');
    expect(card().querySelector('header .action')).not.toBeNull();
    expect(card().querySelector('.data')?.textContent).toBe('12 пакетов');
    expect(card().hasAttribute('aria-busy')).toBe(false);
  });

  it('announces loading and marks itself busy instead of showing the data', () => {
    const { card, state } = render((host) => host.loading.set(true));

    expect(card().getAttribute('aria-busy')).toBe('true');
    expect(state('loading')?.getAttribute('role')).toBe('status');
    expect(state('loading')?.textContent).toContain(PACKAGED_RUSSIAN['common.loading']);
    expect(card().querySelector('.data')).toBeNull();
    expect(card().querySelector('header .action')).not.toBeNull();
  });

  it('reports a failure as an alert, before loading, with a retry', () => {
    const { fixture, state } = render((host) => {
      host.failed.set(true);
      host.loading.set(true);
    });
    const alert = state('error')!;

    expect(alert.getAttribute('role')).toBe('alert');
    expect(alert.textContent).toContain(PACKAGED_RUSSIAN['ui.dashboard.load_error']);
    expect(state('loading')).toBeNull();

    alert.querySelector('button')!.click();
    expect(fixture.componentInstance.retries).toBe(1);
  });

  it('says when there is nothing to show, in its own words when given', () => {
    const { fixture, card, state } = render((host) => host.empty.set(true));
    expect(state('empty')?.textContent?.trim()).toBe(PACKAGED_RUSSIAN['ui.dashboard.empty']);
    expect(card().querySelector('.data')).toBeNull();

    fixture.componentInstance.emptyText.set('Пакетов за неделю нет');
    fixture.detectChanges();
    expect(state('empty')?.textContent?.trim()).toBe('Пакетов за неделю нет');
  });

  it('shows its own failure text when given', () => {
    const { state } = render((host) => {
      host.failed.set(true);
      host.errorText.set('Сервис загрузок недоступен');
    });

    expect(state('error')?.textContent).toContain('Сервис загрузок недоступен');
  });
});
