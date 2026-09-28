import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { StorageStats } from '../files.models';
import { FilesMetricsCardsComponent } from './files-metrics-cards.component';

const MB = 1024 * 1024;
const GB = 1024 * MB;

const STATS: StorageStats = {
  companyQuotaBytes: 10 * GB,
  companyUsedBytes: 9.5 * GB,
  companyAvailableBytes: 0.5 * GB,
  userQuotaBytes: 1 * GB,
  userUsedBytes: 800 * MB,
  userAvailableBytes: 224 * MB,
  totalFilesCount: 120,
  userFilesCount: 14,
};

function render(stats: StorageStats | null) {
  const fixture = TestBed.createComponent(FilesMetricsCardsComponent);
  fixture.componentRef.setInput('stats', stats);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const card = (name: 'company' | 'user') => host.querySelector(`.${name}-card`) as HTMLElement;
  const text = (root: HTMLElement, selector: string) =>
    [...root.querySelectorAll(selector)].map((node) => node.textContent?.replace(/\s+/g, ' ').trim());
  return { fixture, host, card, text };
}

describe('FilesMetricsCardsComponent', () => {
  it('shows the used space against the quota for the company and for the person', () => {
    const { card, text } = render(STATS);

    expect(text(card('company'), '.metric-values span')).toEqual(['9.5 GB', 'из', '10 GB']);
    expect(text(card('user'), '.metric-values span')).toEqual(['800 MB', 'из', '1 GB']);
    expect(text(card('company'), '.metric-footer span')).toEqual(['Свободно: 512 MB', 'Всего файлов: 120']);
    expect(text(card('user'), '.metric-footer span')).toEqual(['Свободно: 224 MB', 'Моих файлов: 14']);
  });

  it('reads each quota as a named progress bar with its percentage', () => {
    const { card } = render(STATS);

    const company = card('company').querySelector('[role="progressbar"]') as HTMLElement;
    const user = card('user').querySelector('[role="progressbar"]') as HTMLElement;
    expect(company.getAttribute('aria-label')).toBe('Использование хранилища компании');
    expect(company.getAttribute('aria-valuenow')).toBe('95');
    expect(user.getAttribute('aria-label')).toBe('Использование персональной квоты');
    expect(user.getAttribute('aria-valuenow')).toBe('78');
  });

  it('warns from three quarters of the quota and alarms from nine tenths', () => {
    const { card } = render(STATS);

    expect(card('company').querySelector('.percent-badge')?.classList).toContain('danger');
    expect(card('user').querySelector('.percent-badge')?.classList).toContain('warning');
    expect(card('user').querySelector('.percent-badge')?.classList).not.toContain('danger');
  });

  it('never shows more than a full quota and counts no quota as nothing used', () => {
    const { card } = render({ ...STATS, companyUsedBytes: 12 * GB, userQuotaBytes: 0, userUsedBytes: 0 });

    expect(card('company').querySelector('.percent-badge')?.textContent?.trim()).toBe('100%');
    expect(card('user').querySelector('.percent-badge')?.textContent?.trim()).toBe('0%');
    expect(card('user').querySelector('.metric-values .quota-val')?.textContent).toBe('0 B');
  });

  it('shows nothing until the figures arrive', () => {
    const { host } = render(null);

    expect(host.querySelector('.metric-card')).toBeNull();
  });
});
