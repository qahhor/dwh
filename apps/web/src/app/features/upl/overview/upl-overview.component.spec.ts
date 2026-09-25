import { TestBed } from '@angular/core/testing';
import { Subject, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { UplOverview, UplOverviewApi } from './overview-api';
import { UplOverviewComponent } from './upl-overview.component';

const overview = (days: number, uploads = 12): UplOverview => ({
  days,
  generatedAt: '2026-09-25T09:30:00Z',
  totals: { uploads, received: 1, verified: 2, rejected: 3, applied: 6, rowsApplied: 12345 }
});

async function render(get: ReturnType<typeof vi.fn>) {
  await TestBed.configureTestingModule({
    imports: [UplOverviewComponent],
    providers: [{ provide: UplOverviewApi, useValue: { get } }]
  }).compileComponents();
  const fixture = TestBed.createComponent(UplOverviewComponent);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('UplOverviewComponent', () => {
  it('shows the uploads of the last 30 days by status in a named widget', async () => {
    const get = vi.fn(() => of(overview(30)));
    const { host } = await render(get);

    expect(get).toHaveBeenCalledWith(30);
    const card = host.querySelector('[data-testid="overview-totals"] section') as HTMLElement;
    expect(document.getElementById(card.getAttribute('aria-labelledby')!)?.textContent).toBe('Загрузки за период');
    const tiles = [...card.querySelectorAll('.overview__tile')]
      .map(tile => `${tile.querySelector('dt')?.textContent?.trim()} ${tile.querySelector('dd')?.textContent?.trim()}`);
    expect(tiles[0]).toBe('Всего загрузок 12');
    expect(tiles[3]).toBe('Отклонено 3');
    expect(tiles[4]).toContain('12');
    expect(host.querySelector('[data-testid="overview-stamp"]')?.textContent).toContain('Данные на');
  });

  it('switches the period, marks the pressed one and keeps only the latest answer', async () => {
    const slow = new Subject<UplOverview>();
    const get = vi.fn()
      .mockReturnValueOnce(of(overview(30)))
      .mockReturnValueOnce(slow)
      .mockReturnValueOnce(of(overview(90, 40)));
    const { fixture, host } = await render(get);
    const buttons = () => [...host.querySelectorAll<HTMLButtonElement>('[data-testid="overview-period"]')];

    buttons()[0].click();
    fixture.detectChanges();
    buttons()[2].click();
    fixture.detectChanges();
    slow.next(overview(7, 1));
    fixture.detectChanges();

    expect(get.mock.calls.map(call => call[0])).toEqual([30, 7, 90]);
    expect(buttons().map(button => button.getAttribute('aria-pressed'))).toEqual(['false', 'false', 'true']);
    expect(host.querySelector('.overview__tile')?.textContent).toContain('40');
  });

  it('keeps a failure inside its widget with a retry, and says when there were no uploads', async () => {
    const get = vi.fn()
      .mockReturnValueOnce(throwError(() => ({ status: 503 })))
      .mockReturnValueOnce(of(overview(30, 0)));
    const { fixture, host } = await render(get);

    const error = host.querySelector('[data-testid="dash-card-error"]') as HTMLElement;
    expect(error.getAttribute('role')).toBe('alert');
    (error.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="dash-card-empty"]')?.textContent).toContain('За период загрузок не было');
  });
});
