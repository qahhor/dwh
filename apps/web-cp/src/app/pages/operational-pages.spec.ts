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

  function create(component: Type<unknown>): ComponentFixture<unknown> {
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

    for (const fixture of [clients, announcements]) {
      const controls = Array.from(fixture.nativeElement.querySelectorAll('form input, form select, form textarea')) as Array<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>;
      expect(controls.length).toBeGreaterThan(0);
      for (const control of controls) {
        expect(control.id).not.toBe('');
        expect(fixture.nativeElement.querySelector(`label[for="${control.id}"]`)).not.toBeNull();
      }
    }

    expect(clients.nativeElement.querySelector('#cp-client-code').required).toBe(true);
    expect(announcements.nativeElement.querySelector('#cp-announcement-title').required).toBe(true);
  });
});
