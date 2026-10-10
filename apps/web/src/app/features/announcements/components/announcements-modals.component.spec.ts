import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { I18nService } from '@core/services/i18n.service';
import { tickInZone } from '@shared/ui-kit/testing/zone-tick';
import { inScreen } from '@testing/in-screen';
import { AnnouncementBannerType } from '../announcements.models';
import { AnnouncementsModalsComponent } from './announcements-modals.component';

@Component({
  imports: [AnnouncementsModalsComponent],
  template: `
    <app-announcements-modals
      [isEditorOpen]="open()"
      [isSaving]="saving()"
      [draftTitles]="titles()"
      [draftBodies]="bodies()"
      [bannerType]="bannerType()"
      [editingId]="editingId()"
      [draftErrors]="draftErrors()"
      (draftTitlesChange)="titles.set($event)"
      (draftBodiesChange)="bodies.set($event)"
      (bannerTypeChange)="bannerType.set($event)"
      (saveDraft)="saves = saves + 1"
      (closeEditor)="closes = closes + 1"
    />
  `,
})
class Host {
  readonly open = signal(true);
  readonly saving = signal(false);
  readonly titles = signal<Record<string, string>>({});
  readonly bodies = signal<Record<string, string>>({});
  readonly bannerType = signal<AnnouncementBannerType>('INFO');
  readonly editingId = signal<number | null>(null);
  readonly draftErrors = signal<Record<string, string>>({});
  saves = 0;
  closes = 0;
}

async function render(setup: (host: Host) => void = () => undefined) {
  const fixture = TestBed.createComponent(Host);
  setup(fixture.componentInstance);
  document.body.appendChild(fixture.nativeElement);
  const settle = async () => {
    tickInZone();
    await fixture.whenStable();
    fixture.detectChanges();
  };
  await settle();
  const screen = inScreen(fixture.nativeElement);
  const save = () => screen.querySelector('[data-testid="form-submit"]') as HTMLButtonElement;
  const type = async (selector: string, text: string) => {
    const field = screen.querySelector(selector) as HTMLInputElement | HTMLTextAreaElement;
    field.value = text;
    field.dispatchEvent(new Event('input'));
    await settle();
  };
  return { fixture, host: fixture.componentInstance, settle, screen, save, type };
}

describe('AnnouncementsModalsComponent', () => {
  it('names the dialog after creating a new announcement or editing one', async () => {
    const created = await render();
    expect(created.screen.querySelector('[role="dialog"] .smt-modal__title')?.textContent).toBe('Новое объявление');
    created.fixture.destroy();

    const edited = await render((host) => host.editingId.set(7));
    expect(edited.screen.querySelector('[role="dialog"] .smt-modal__title')?.textContent).toBe(
      'Редактирование объявления',
    );
  });

  it('keeps Save enabled; a save without the Russian title and text names them and focuses the first', async () => {
    const { host, screen, settle, save, type } = await render();
    const error = (id: string) =>
      screen.querySelector(`smt-control:has(#${id}) .smt-control__error`)?.textContent?.trim();
    expect(save().disabled).toBe(false);
    expect(error('announcement-title-ru')).toBeUndefined();

    save().click();
    await settle();
    expect(host.saves).toBe(0);
    expect(error('announcement-title-ru')).toBe('Обязательное поле');
    expect(error('announcement-body-ru')).toBe('Обязательное поле');
    expect(document.activeElement?.id).toBe('announcement-title-ru');

    await type('#announcement-title-ru', 'Плановые работы');
    await type('#announcement-body-ru', 'Сервис будет недоступен.');
    expect(error('announcement-title-ru')).toBeUndefined();
    save().click();
    await settle();
    expect(host.saves).toBe(1);
  });

  it('shows the server refusal of a field under it', async () => {
    const { host, screen, settle } = await render((host) => host.titles.set({ ru: 'Релиз' }));
    host.draftErrors.set({ bodyRu: 'Слишком длинный текст' });
    await settle();
    expect(
      screen.querySelector('smt-control:has(#announcement-body-ru) .smt-control__error')?.textContent?.trim(),
    ).toBe('Слишком длинный текст');
  });

  it('asks to save a valid draft once, and not while a save is running', async () => {
    const { host, settle, save } = await render((host) => {
      host.titles.set({ ru: 'Плановые работы' });
      host.bodies.set({ ru: 'Сервис будет недоступен.' });
    });

    save().click();
    await settle();
    expect(host.saves).toBe(1);

    host.saving.set(true);
    await settle();
    expect(save().disabled).toBe(true);
    save().click();
    await settle();
    expect(host.saves).toBe(1);
  });

  it('counts the characters of the Russian title against the limit', async () => {
    const { screen, type } = await render();

    await type('#announcement-title-ru', 'Релиз');

    expect(screen.querySelector('.char-count')?.textContent).toContain('5 / 10 000');
  });

  it('writes a translation into its own language, keeping the Russian text', async () => {
    TestBed.inject(I18nService).languages.update((languages) => [
      ...languages,
      { ...languages[0], code: 'en', name: 'English', builtin: false },
    ]);
    const { host, screen, settle, type } = await render((host) => host.titles.set({ ru: 'Релиз' }));
    const tabs = screen.querySelectorAll('[role="dialog"] [role="tab"]') as HTMLButtonElement[];
    expect(tabs.map((tab) => tab.textContent?.trim())).toEqual(['Русский *', 'English']);

    tabs[1].click();
    await settle();
    expect(screen.querySelector('#announcement-title-ru')).toBeNull();
    await type('#announcement-title-other', 'Release');

    expect(host.titles()).toEqual({ ru: 'Релиз', en: 'Release' });
  });

  it('asks to close from the cancel button', async () => {
    const { host, screen, settle } = await render();

    (screen.querySelector('[data-testid="form-cancel"]') as HTMLButtonElement).click();
    await settle();

    expect(host.closes).toBe(1);
  });
});
