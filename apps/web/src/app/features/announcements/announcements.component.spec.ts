import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/services/api.service';
import { PermissionService } from '../../core/services/permission.service';
import { ToastService } from '../../core/services/toast.service';
import { AnnouncementAdminRecord, AnnouncementsComponent } from './announcements.component';

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

    const publish = fixture.nativeElement.querySelector('.publish-action') as HTMLButtonElement;
    expect(publish.disabled).toBe(true);

    (fixture.nativeElement.querySelector('button[aria-label="Создать объявление"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="dialog"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="announcement-title-ru"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="announcement-body-ru"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('label[for="announcement-banner-type"]')).not.toBeNull();
    expect((fixture.nativeElement.querySelector('[data-testid="save-draft"]') as HTMLButtonElement).disabled).toBe(true);
  });

  it('surfaces optimistic-lock conflicts and offers a refresh', async () => {
    const { fixture, api } = await createFixture({ putError: true });
    (fixture.nativeElement.querySelector('.edit-action') as HTMLButtonElement).click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-testid="save-draft"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(api.put).toHaveBeenCalledWith('/announcements/7', expect.objectContaining({ lockVersion: 3 }));
    expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('объявление уже изменено другим пользователем');
    expect(fixture.nativeElement.querySelector('button[aria-label="Обновить список объявлений"]')).not.toBeNull();
  });

  it('requires confirmation before publishing or archiving', async () => {
    const publishFixture = await createFixture();
    (publishFixture.fixture.nativeElement.querySelector('.publish-action') as HTMLButtonElement).click();
    publishFixture.fixture.detectChanges();
    expect(publishFixture.fixture.nativeElement.querySelector('[role="dialog"]')?.textContent).toContain('Опубликовать объявление?');

    TestBed.resetTestingModule();
    const archiveFixture = await createFixture({ records: [{
      ...draft,
      state: 'PUBLISHED',
      publishedAt: '2026-09-02T09:00:00Z'
    }] });
    (archiveFixture.fixture.nativeElement.querySelector('.archive-action') as HTMLButtonElement).click();
    archiveFixture.fixture.detectChanges();
    expect(archiveFixture.fixture.nativeElement.querySelector('[role="dialog"]')?.textContent).toContain('Архивировать объявление?');
  });

  it('provides distinct empty and recoverable error states', async () => {
    const empty = await createFixture({ records: [] });
    expect(empty.fixture.nativeElement.querySelector('[data-testid="announcements-empty"]')?.textContent).toContain('Объявлений пока нет');

    TestBed.resetTestingModule();
    const failed = await createFixture({ getError: true });
    expect(failed.fixture.nativeElement.querySelector('[data-testid="announcements-load-error"][role="alert"]')?.textContent).toContain('Не удалось загрузить объявления');
    expect(failed.fixture.nativeElement.querySelector('button[aria-label="Повторить загрузку объявлений"]')).not.toBeNull();
  });
});
