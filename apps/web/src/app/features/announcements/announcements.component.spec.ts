import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { AnnouncementAdminRecord, AnnouncementsComponent } from './announcements.component';
import { inScreen } from '../../../testing/in-screen';

describe('AnnouncementsComponent', () => {
  const draft: AnnouncementAdminRecord = {
    id: 7,
    titleJson: { ru: 'Плановые работы' },
    bodyJson: { ru: 'Сервис будет недоступен пять минут.' },
    bannerType: 'WARNING',
    state: 'DRAFT',
    createdBy: 1,
    createdAt: '2026-09-02T08:00:00Z',
    modifiedAt: '2026-09-02T08:00:00Z',
    publishedAt: null,
    archivedAt: null,
    lockVersion: 3
  };

  const published: AnnouncementAdminRecord = {
    id: 8,
    titleJson: { ru: 'Релиз обновлен', en: 'Release updated' },
    bodyJson: { ru: 'Добавлены новые отчеты.', en: 'New reports added.' },
    bannerType: 'INFO',
    state: 'PUBLISHED',
    createdBy: 1,
    createdAt: '2026-09-03T08:00:00Z',
    modifiedAt: '2026-09-03T08:00:00Z',
    publishedAt: '2026-09-03T08:00:00Z',
    archivedAt: null,
    lockVersion: 1
  };

  const archived: AnnouncementAdminRecord = {
    id: 9,
    titleJson: { ru: 'Архивное сообщение' },
    bodyJson: { ru: 'Старое сообщение.' },
    bannerType: 'CRITICAL',
    state: 'ARCHIVED',
    createdBy: 1,
    createdAt: '2026-09-01T08:00:00Z',
    modifiedAt: '2026-09-01T09:00:00Z',
    publishedAt: '2026-09-01T08:00:00Z',
    archivedAt: '2026-09-01T09:00:00Z',
    lockVersion: 2
  };

  async function createFixture(options: {
    records?: AnnouncementAdminRecord[];
    getError?: boolean;
    putError?: boolean;
  } = {}) {
    const api = {
      get: vi.fn(() => options.getError
        ? throwError(() => ({ status: 503 }))
        : of(options.records ?? [draft])),
      post: vi.fn(() => of(draft)),
      put: vi.fn(() => options.putError
        ? throwError(() => ({ status: 409, code: 'CONFLICT', detail: 'stale' }))
        : of({ ...draft, lockVersion: draft.lockVersion + 1 }))
    };
    const permissions = {
      canCreate: vi.fn(() => true),
      canUpdate: vi.fn(() => true),
      hasPermission: vi.fn(() => true)
    };
    await TestBed.configureTestingModule({
      imports: [AnnouncementsComponent],
      providers: [
        { provide: ApiService, useValue: api },
        { provide: PermissionService, useValue: permissions },
        { provide: ToastService, useValue: { success: vi.fn(), error: vi.fn() } }
      ]
    }).compileComponents();
    const fixture = TestBed.createComponent(AnnouncementsComponent);
    fixture.detectChanges();
    return { fixture, api };
  }

  it('opens an accessible draft form and keeps invalid actions disabled', async () => {
    const invalidDraft = {
      ...draft,
      titleJson: { ru: '' },
      bodyJson: { ru: '' }
    };
    const { fixture } = await createFixture({ records: [invalidDraft] });

    const publish = inScreen(fixture.nativeElement).querySelector('.publish-action') as HTMLButtonElement;
    expect(publish.disabled).toBe(true);

    (inScreen(fixture.nativeElement).querySelector('button[aria-label="Создать объявление"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(inScreen(fixture.nativeElement).querySelector('[role="dialog"]')).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('label[for="announcement-title-ru"]')).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('label[for="announcement-body-ru"]')).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('label[for="announcement-banner-type"]')).not.toBeNull();
    expect(inScreen(fixture.nativeElement).querySelector('#announcement-banner-type')?.getAttribute('role')).toBe('combobox');
    expect((inScreen(fixture.nativeElement).querySelector('[data-testid="save-draft"]') as HTMLButtonElement).disabled).toBe(true);
  });

  it('surfaces optimistic-lock conflicts and offers a refresh', async () => {
    const { fixture, api } = await createFixture({ putError: true });
    (inScreen(fixture.nativeElement).querySelector('.edit-action') as HTMLButtonElement).click();
    fixture.detectChanges();
    (inScreen(fixture.nativeElement).querySelector('[data-testid="save-draft"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.put).toHaveBeenCalledWith('/announcements/7', expect.objectContaining({ lockVersion: 3 }));
    expect(inScreen(fixture.nativeElement).querySelector('[role="alert"]')?.textContent).toContain('объявление уже изменено другим пользователем');
    expect(inScreen(fixture.nativeElement).querySelector('button[aria-label="Обновить список объявлений"]')).not.toBeNull();
  });

  it('requires confirmation before publishing or archiving', async () => {
    const publishFixture = await createFixture();
    (inScreen(publishFixture.fixture.nativeElement).querySelector('.publish-action') as HTMLButtonElement).click();
    publishFixture.fixture.detectChanges();
    await publishFixture.fixture.whenStable();
    expect(document.querySelector('[role="alertdialog"]')?.textContent).toContain('Опубликовать объявление?');
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());

    TestBed.resetTestingModule();
    const archiveFixture = await createFixture({ records: [{
      ...draft,
      state: 'PUBLISHED',
      publishedAt: '2026-09-02T09:00:00Z'
    }] });
    (inScreen(archiveFixture.fixture.nativeElement).querySelector('.archive-action') as HTMLButtonElement).click();
    archiveFixture.fixture.detectChanges();
    await archiveFixture.fixture.whenStable();
    const archiveDialog = document.querySelector('[role="alertdialog"]');
    expect(archiveDialog?.textContent).toContain('Архивировать объявление?');
    expect([...archiveDialog!.querySelectorAll('button')].at(-1)?.classList).toContain('smt-modal-button--danger');
    document.querySelectorAll('.cdk-overlay-container').forEach(node => node.remove());
  });

  it('provides distinct empty and recoverable error states', async () => {
    const empty = await createFixture({ records: [] });
    expect(inScreen(empty.fixture.nativeElement).querySelector('[data-testid="announcements-empty"]')?.textContent).toContain('Объявлений пока нет');

    TestBed.resetTestingModule();
    const failed = await createFixture({ getError: true });
    expect(inScreen(failed.fixture.nativeElement).querySelector('[data-testid="announcements-load-error"][role="alert"]')?.textContent).toContain('Не удалось загрузить объявления');
    expect(inScreen(failed.fixture.nativeElement).querySelector('button[aria-label="Повторить загрузку объявлений"]')).not.toBeNull();
  });

  it('filters announcements by status and search text', async () => {
    const { fixture } = await createFixture({ records: [draft, published, archived] });
    const comp = fixture.componentInstance;

    expect(comp.filteredAnnouncements()).toHaveLength(3);

    comp.setStatusFilter('PUBLISHED');
    expect(comp.filteredAnnouncements()).toHaveLength(1);
    expect(comp.filteredAnnouncements()[0].id).toBe(8);

    comp.setStatusFilter('DRAFT');
    expect(comp.filteredAnnouncements()).toHaveLength(1);
    expect(comp.filteredAnnouncements()[0].id).toBe(7);

    comp.setStatusFilter('ARCHIVED');
    expect(comp.filteredAnnouncements()).toHaveLength(1);
    expect(comp.filteredAnnouncements()[0].id).toBe(9);

    comp.setStatusFilter('ALL');
    comp.searchQuery.set('Релиз');
    expect(comp.filteredAnnouncements()).toHaveLength(1);
    expect(comp.filteredAnnouncements()[0].id).toBe(8);

    comp.resetFilters();
    expect(comp.statusFilter()).toBe('ALL');
    expect(comp.searchQuery()).toBe('');
    expect(comp.filteredAnnouncements()).toHaveLength(3);
  });

  it('highlights the active published announcement with an active badge', async () => {
    const { fixture } = await createFixture({ records: [draft, published, archived] });
    fixture.detectChanges();

    const activeBadge = inScreen(fixture.nativeElement).querySelector('.active-badge');
    expect(activeBadge).not.toBeNull();
    expect(activeBadge.textContent).toContain('Активно');
  });
});
