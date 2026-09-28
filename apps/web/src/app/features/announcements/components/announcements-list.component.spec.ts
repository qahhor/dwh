import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { AnnouncementAdminRecord } from '../announcements.models';
import { AnnouncementsListComponent } from './announcements-list.component';

const record = (overrides: Partial<AnnouncementAdminRecord>): AnnouncementAdminRecord => ({
  id: 1,
  titleJson: { ru: 'Плановые работы' },
  bodyJson: { ru: 'Сервис будет недоступен пять минут.' },
  bannerType: 'INFO',
  state: 'DRAFT',
  createdBy: 1,
  createdAt: '2026-09-02T08:00:00Z',
  modifiedAt: '2026-09-02T08:00:00Z',
  publishedAt: null,
  archivedAt: null,
  lockVersion: 1,
  ...overrides,
});

const DRAFT = record({ id: 7, bannerType: 'WARNING' });
const PUBLISHED = record({
  id: 8,
  state: 'PUBLISHED',
  titleJson: { ru: 'Релиз обновлён', en: 'Release updated' },
  publishedAt: '2026-09-03T08:00:00Z',
});
const ARCHIVED = record({ id: 9, state: 'ARCHIVED', bannerType: 'CRITICAL', archivedAt: '2026-09-04T08:00:00Z' });

function render(
  options: {
    items?: AnnouncementAdminRecord[];
    activeId?: number | null;
    canUpdate?: boolean;
    canPublish?: boolean;
    canArchive?: boolean;
  } = {},
) {
  const fixture = TestBed.createComponent(AnnouncementsListComponent);
  fixture.componentRef.setInput('items', options.items ?? [DRAFT, PUBLISHED, ARCHIVED]);
  fixture.componentRef.setInput('activeId', options.activeId ?? null);
  fixture.componentRef.setInput('canUpdate', options.canUpdate ?? true);
  fixture.componentRef.setInput('canPublish', options.canPublish ?? true);
  fixture.componentRef.setInput('canArchive', options.canArchive ?? true);
  const asked: [string, number][] = [];
  const component = fixture.componentInstance;
  component.edit.subscribe((item) => asked.push(['edit', item.id]));
  component.publish.subscribe((item) => asked.push(['publish', item.id]));
  component.archive.subscribe((item) => asked.push(['archive', item.id]));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const cards = () => [...host.querySelectorAll('article.announcement-card')] as HTMLElement[];
  const text = (card: HTMLElement, selector: string) =>
    card.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();
  return { fixture, host, cards, text, asked };
}

describe('AnnouncementsListComponent', () => {
  it('shows each announcement with its state, level, number and title', () => {
    const { cards, text } = render();

    expect(cards().map((card) => card.getAttribute('data-state'))).toEqual(['DRAFT', 'PUBLISHED', 'ARCHIVED']);
    expect(cards().map((card) => text(card, '.state-badge'))).toEqual([
      'edit_note Черновик',
      'check_circle Опубликовано',
      'archive Архив',
    ]);
    expect(cards().map((card) => text(card, '.type-badge'))).toEqual([
      'warning Предупреждение',
      'info Информация',
      'error Критическое',
    ]);
    expect(text(cards()[0], '.id-tag')).toBe('№7');
    expect(text(cards()[1], 'h2')).toBe('Релиз обновлён');
  });

  it('marks the one announcement people see now as active', () => {
    const { cards } = render({ activeId: 8 });

    expect(cards().map((card) => !!card.querySelector('.active-badge'))).toEqual([false, true, false]);
  });

  it('offers edit and publish on a draft and archive on a published one, and says which was asked', () => {
    const { cards, asked } = render();

    (cards()[0].querySelector('.edit-action') as HTMLButtonElement).click();
    (cards()[0].querySelector('.publish-action') as HTMLButtonElement).click();
    (cards()[1].querySelector('.archive-action') as HTMLButtonElement).click();

    expect(asked).toEqual([
      ['edit', 7],
      ['publish', 7],
      ['archive', 8],
    ]);
    expect(cards()[2].querySelector('.card-actions button')).toBeNull();
  });

  it('shows no action the person has no right to', () => {
    const { host } = render({ canUpdate: false, canPublish: false, canArchive: false });

    expect(host.querySelector('.card-actions button')).toBeNull();
  });

  it('blocks publishing a draft without a title or text and says why', () => {
    const empty = record({ id: 11, titleJson: { ru: '' }, bodyJson: { ru: 'Текст' } });
    const { cards, text } = render({ items: [empty] });

    const publish = cards()[0].querySelector('.publish-action') as HTMLButtonElement;
    expect(publish.disabled).toBe(true);
    expect(publish.getAttribute('aria-describedby')).toBe('invalid-draft-11');
    expect(text(cards()[0], '#invalid-draft-11')).toBe('Заполните RU-заголовок и текст');
    expect(text(cards()[0], 'h2')).toBe('Без заголовка');
  });

  it('shows the text in the language of the interface, falling back to Russian', () => {
    const english = record({ id: 12, titleJson: { ru: 'Русский', en: 'English' }, bodyJson: { ru: 'Только RU' } });
    const { cards, text } = render({ items: [english] });

    expect(text(cards()[0], 'h2')).toBe('Русский');
    expect(text(cards()[0], '.announcement-body')).toBe('Только RU');
  });
});
