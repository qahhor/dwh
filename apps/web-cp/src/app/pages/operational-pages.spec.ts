import { signal, Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { CpApiService } from '../core/cp-api.service';
import { AnnouncementsComponent } from './announcements.component';
import { BackupsComponent } from './backups.component';
import { ClientsComponent } from './clients.component';
import { FleetComponent } from './fleet.component';

describe('Control Plane operational pages', () => {
  const api = {
    user: signal({ login: 'admin', name: 'Администратор', roles: ['cp-admin'] }),
    fleet: vi.fn().mockResolvedValue({ items: [], total: 0, problems: 0, heartbeatTimeoutMinutes: 10 }),
    clients: vi.fn().mockResolvedValue([]),
    backupChecks: vi.fn().mockResolvedValue([]),
    backupReports: vi.fn().mockResolvedValue([]),
    announcements: vi.fn().mockResolvedValue([]),
    createClient: vi.fn(),
    registerInstance: vi.fn(),
    createAnnouncement: vi.fn(),
    publishAnnouncement: vi.fn(),
    archiveAnnouncement: vi.fn()
  };

  async function configure() {
    await TestBed.configureTestingModule({
      imports: [FleetComponent, ClientsComponent, BackupsComponent, AnnouncementsComponent],
      providers: [{ provide: CpApiService, useValue: api }]
    }).compileComponents();
  }

  function create<T>(component: Type<T>): ComponentFixture<T> {
    const fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    return fixture;
  }

  it('puts every operational table in a named keyboard-scrollable region', async () => {
    await configure();
    const pages: Type<unknown>[] = [FleetComponent, ClientsComponent, BackupsComponent, AnnouncementsComponent];
    for (const component of pages) {
      const fixture = create(component);
      const region = fixture.nativeElement.querySelector('.table-scroll[role="region"]') as HTMLElement;
      const table = region?.querySelector('table') as HTMLTableElement;

      expect(region?.tabIndex).toBe(0);
      expect(region?.getAttribute('aria-label')).toBeTruthy();
      expect(table?.getAttribute('aria-label')).toBeTruthy();
      fixture.destroy();
    }
  });

  it('labels management forms explicitly and marks required controls', async () => {
    await configure();
    const clients = create(ClientsComponent);
    const announcements = create(AnnouncementsComponent);
    await Promise.all([clients.whenStable(), announcements.whenStable()]);
    clients.componentInstance.openCreateClientModal();
    announcements.componentInstance.showModal = true;
    clients.changeDetectorRef.markForCheck();
    announcements.changeDetectorRef.markForCheck();
    clients.detectChanges();
    announcements.detectChanges();

    for (const [page, fixture] of [['clients', clients], ['announcements', announcements]] as const) {
      const controls = Array.from(fixture.nativeElement.querySelectorAll('form input, form select, form textarea')) as Array<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>;
      expect(controls.length, `${page} modal form controls`).toBeGreaterThan(0);
      for (const control of controls) {
        expect(control.id).not.toBe('');
        expect(fixture.nativeElement.querySelector(`label[for="${control.id}"]`)).not.toBeNull();
      }
    }

    expect(clients.nativeElement.querySelector('#new-client-code').required).toBe(true);
    expect(announcements.nativeElement.querySelector('#ann-title').required).toBe(true);
  });

  it('registers an explicit managed placement and removes enrollment secret on dismiss', async () => {
    const enrollment = {
      instanceId: 42,
      enrollmentToken: 'one-time-enrollment-secret',
      expiresAt: '2026-09-01T00:15:00Z'
    };
    api.clients.mockResolvedValueOnce([{
      id: 7,
      code: 'alpha',
      name: 'Alpha',
      resourceProfile: 'S',
      createdAt: '2026-09-01T00:00:00Z'
    }]);
    api.registerInstance.mockResolvedValueOnce(enrollment);
    await configure();
    const clients = create(ClientsComponent);
    await clients.whenStable();
    clients.componentInstance.openRegisterModal();
    clients.componentInstance.instClient = 'alpha';
    clients.componentInstance.instUrl = 'https://alpha.invalid';

    await clients.componentInstance.registerInstance();

    expect(api.registerInstance).toHaveBeenCalledWith({
      clientCode: 'alpha',
      environment: 'production',
      url: 'https://alpha.invalid',
      deploymentMode: 'MANAGED_CLOUD',
      jurisdiction: 'EU',
      cloudProvider: 'HETZNER',
      storageProvider: 'CLOUDFLARE_R2',
      edgeProvider: 'CLOUDFLARE',
      supportTier: 'MANAGED_995'
    });
    clients.detectChanges();
    expect(clients.nativeElement.textContent).toContain('Одноразовый enrollment-токен');
    expect(clients.nativeElement.textContent).toContain('one-time-enrollment-secret');
    expect(clients.nativeElement.textContent).toContain('Действует до');

    clients.componentInstance.dismissIssuedEnrollment();
    clients.detectChanges();
    expect(clients.nativeElement.textContent).not.toContain('one-time-enrollment-secret');
  });

  it('separates backup artifact state from restore verification without exposing links', async () => {
    api.backupReports.mockResolvedValueOnce([
      {
        backupId: '11111111-1111-1111-1111-111111111111',
        instanceId: 42,
        clientCode: 'alpha',
        artifactStatus: 'UPLOADED',
        checksumSha256: null,
        durationSec: 17,
        reasonCode: null,
        completedAt: '2026-09-01T08:00:00Z',
        receivedAt: '2026-09-01T08:00:05Z',
        verifiedAt: null
      },
      {
        backupId: '22222222-2222-2222-2222-222222222222',
        instanceId: 43,
        clientCode: 'beta',
        artifactStatus: 'VERIFIED',
        checksumSha256: 'a'.repeat(64),
        durationSec: 21,
        reasonCode: null,
        completedAt: '2026-09-01T09:00:00Z',
        receivedAt: '2026-09-01T09:00:05Z',
        verifiedAt: '2026-09-01T09:00:06Z'
      },
      {
        backupId: '33333333-3333-3333-3333-333333333333',
        instanceId: 44,
        clientCode: 'gamma',
        artifactStatus: 'FAILED',
        checksumSha256: null,
        durationSec: 8,
        reasonCode: 'upload_timeout',
        completedAt: '2026-09-01T10:00:00Z',
        receivedAt: '2026-09-01T10:00:05Z',
        verifiedAt: null
      }
    ]);
    await configure();
    const backups = create(BackupsComponent);
    await backups.whenStable();
    backups.detectChanges();

    const artifactSection = backups.nativeElement.querySelector(
      '[data-testid="backup-artifact-reports"]') as HTMLElement;
    const restoreSection = backups.nativeElement.querySelector(
      '[data-testid="restore-verification-checks"]') as HTMLElement;

    expect(artifactSection).not.toBeNull();
    expect(restoreSection).not.toBeNull();
    expect(artifactSection.textContent).toContain('Загружен, не проверен');
    expect(artifactSection.textContent).toContain('Проверен');
    expect(artifactSection.textContent).toContain('Ошибка загрузки');
    expect(artifactSection.textContent).toContain('upload_timeout');
    expect(artifactSection.querySelectorAll('a')).toHaveLength(0);
    expect(artifactSection.innerHTML).not.toContain('href=');
  });

  it('makes backup filters and sortable columns keyboard-operable', async () => {
    await configure();
    const backups = create(BackupsComponent);
    await backups.whenStable();
    backups.detectChanges();

    const filterButtons = Array.from(
      backups.nativeElement.querySelectorAll('.clickable-tile')) as HTMLButtonElement[];
    const sortButtons = Array.from(
      backups.nativeElement.querySelectorAll('th.sortable-th button')) as HTMLButtonElement[];

    expect(filterButtons).toHaveLength(3);
    expect(filterButtons.every(button => button.tagName === 'BUTTON')).toBe(true);
    expect(filterButtons.every(button => button.type === 'button')).toBe(true);
    expect(filterButtons.map(button => button.getAttribute('aria-pressed')))
      .toEqual(['true', 'false', 'false']);
    expect(sortButtons).toHaveLength(4);
    expect(sortButtons.every(button => button.type === 'button')).toBe(true);
  });
});
