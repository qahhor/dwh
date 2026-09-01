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
});
