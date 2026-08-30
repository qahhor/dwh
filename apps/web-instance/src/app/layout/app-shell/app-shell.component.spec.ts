import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../core/services/auth.service';
import { CommandPaletteService } from '../../core/services/command-palette.service';
import { I18nService } from '../../core/services/i18n.service';
import { NotificationService } from '../../core/services/notification.service';
import { PermissionService } from '../../core/services/permission.service';
import { ThemeService } from '../../core/services/theme.service';
import { AppShellComponent } from './app-shell.component';

describe('AppShellComponent', () => {
  const authService = {
    currentUser: signal({ name: 'Иван Иванов', login: 'ivan' }),
    logout: vi.fn()
  };
  const permissionService = { canView: vi.fn(() => true) };
  const themeService = {
    currentTheme: signal('light'),
    toggleTheme: vi.fn()
  };
  const i18nService = {
    currentLang: signal('ru'),
    setLanguage: vi.fn(),
    translate: (key: string) => key
  };
  const notificationService = {
    unreadCount: signal(3),
    activeAnnouncement: signal(null),
    fetchUnreadCount: vi.fn(() => of(3)),
    fetchActiveAnnouncement: vi.fn(() => of(null)),
    connectSse: vi.fn(),
    disconnectSse: vi.fn(),
    dismissAnnouncement: vi.fn(() => of(undefined))
  };
  const paletteService = {
    isOpen: signal(false),
    open: vi.fn(),
    close: vi.fn(),
    toggle: vi.fn(),
    search: vi.fn(() => of({ query: '', totalHits: 0, hits: [] }))
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    await TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: PermissionService, useValue: permissionService },
        { provide: ThemeService, useValue: themeService },
        { provide: I18nService, useValue: i18nService },
        { provide: NotificationService, useValue: notificationService },
        { provide: CommandPaletteService, useValue: paletteService }
      ]
    }).compileComponents();
  });

  it('offers a skip link and a named main landmark', () => {
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('a.skip-link[href="#main-content"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('main#main-content')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('nav[aria-label="Основная навигация"]')).not.toBeNull();
  });

  it('names icon-only shell actions without relying on title', () => {
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('button[aria-label="Переключить тему"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('button[aria-label="Открыть уведомления"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('button[aria-label="Выйти из системы"]')).not.toBeNull();
  });

  it('exposes language selection and unread count to assistive technology', () => {
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();

    const languageButtons = Array.from(fixture.nativeElement.querySelectorAll('.lang-btn')) as HTMLButtonElement[];
    expect(languageButtons.every(button => button.type === 'button')).toBe(true);
    expect(fixture.nativeElement.querySelector('.lang-btn[aria-pressed="true"]')?.textContent).toContain('RU');
    expect(fixture.nativeElement.querySelector('.notif-btn .sr-only')?.textContent).toContain('3');
  });
});
