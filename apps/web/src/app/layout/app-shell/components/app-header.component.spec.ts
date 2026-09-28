import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '@core/services/auth.service';
import { CommandPaletteService } from '@core/services/command-palette.service';
import { I18nService } from '@core/services/i18n.service';
import { NotificationService } from '@core/services/notification.service';
import { ThemeService } from '@core/services/theme.service';
import { PACKAGED_RUSSIAN } from '@core/i18n/packaged-russian';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { AppHeaderComponent, type LanguageChangeRequest } from './app-header.component';

describe('AppHeaderComponent', () => {
  const authService = { isLoggingOut: signal(false), logout: vi.fn() };
  const themeService = { currentTheme: signal<'light' | 'dark'>('light'), toggleTheme: vi.fn() };
  const notificationService = {
    unreadCount: signal(0),
    activeAnnouncement: signal<{ title: string; body: string } | null>(null),
  };
  const paletteService = { isOpen: signal(false), open: vi.fn() };

  beforeEach(() => {
    vi.clearAllMocks();
    authService.isLoggingOut.set(false);
    notificationService.unreadCount.set(0);
    notificationService.activeAnnouncement.set(null);
    paletteService.isOpen.set(false);
    TestBed.configureTestingModule({
      imports: [AppHeaderComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: ThemeService, useValue: themeService },
        { provide: NotificationService, useValue: notificationService },
        { provide: CommandPaletteService, useValue: paletteService },
      ],
    });
  });

  function render(inputs: Record<string, unknown> = {}) {
    const fixture = TestBed.createComponent(AppHeaderComponent);
    for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;
    const button = (label: string) =>
      element.querySelector(`button[aria-label="${PACKAGED_RUSSIAN[label]}"]`) as HTMLButtonElement | null;
    return { fixture, element, button };
  }

  it('opens and closes the mobile menu with a button that says what it controls', () => {
    const { fixture, button } = render({ sidebarId: 'shell-nav' });
    const toggles: unknown[] = [];
    fixture.componentInstance.toggleMobileMenu.subscribe(() => toggles.push(true));
    const menu = button('layout.app_shell.otkryt_menyu_navigacii')!;

    expect(menu.getAttribute('aria-controls')).toBe('shell-nav');
    expect(menu.getAttribute('aria-expanded')).toBe('false');
    menu.click();
    expect(toggles).toHaveLength(1);

    fixture.componentRef.setInput('isMobileMenuOpen', true);
    fixture.detectChanges();
    expect(menu.getAttribute('aria-expanded')).toBe('true');
    expect(menu.textContent?.trim()).toBe('close');
  });

  it('opens the command palette from the search button and reports it as expanded', () => {
    const { fixture, button } = render();
    paletteService.open.mockImplementation(() => paletteService.isOpen.set(true));
    const search = button('layout.app_shell.otkryt_globalnyy_poisk')!;

    expect(search.getAttribute('aria-haspopup')).toBe('dialog');
    expect(search.getAttribute('aria-keyshortcuts')).toBe('Control+K Meta+K');
    search.click();
    fixture.detectChanges();

    expect(paletteService.open).toHaveBeenCalledTimes(1);
    expect(search.getAttribute('aria-expanded')).toBe('true');
  });

  it('shows the bell only with the read permission and names the unread count', () => {
    const hidden = render();
    expect(hidden.button('layout.app_shell.otkryt_uvedomleniya')).toBeNull();

    notificationService.unreadCount.set(4);
    const { element, button } = render({ canReadNotifications: true });
    const bell = button('layout.app_shell.otkryt_uvedomleniya')!;

    expect(bell.getAttribute('aria-describedby')).toBe('header-unread-count');
    expect(element.querySelector('#header-unread-count')?.textContent).toContain('4');
  });

  it('logs out once and disables every action while the logout runs', () => {
    const { fixture, element, button } = render({ canReadNotifications: true });
    const logouts: unknown[] = [];
    fixture.componentInstance.logout.subscribe(() => logouts.push(true));

    button('layout.app_shell.vyyti_iz_sistemy')!.click();
    expect(logouts).toHaveLength(1);
    expect(authService.logout).toHaveBeenCalledTimes(1);

    authService.isLoggingOut.set(true);
    fixture.detectChanges();
    const actions = Array.from(
      element.querySelectorAll('.topbar-right button, .palette-trigger'),
    ) as HTMLButtonElement[];
    expect(actions.length).toBeGreaterThan(3);
    expect(actions.every((action) => action.disabled)).toBe(true);
    expect(button('layout.app_shell.vyyti_iz_sistemy')!.getAttribute('aria-busy')).toBe('true');
  });

  it('shows the active announcement to those who may read it and asks to dismiss it', () => {
    notificationService.activeAnnouncement.set({ title: 'Плановые работы', body: 'В субботу с 22:00' });
    expect(render().element.querySelector('.announcement-banner')).toBeNull();

    const { fixture, element, button } = render({ canReadAnnouncements: true });
    const dismissals: unknown[] = [];
    fixture.componentInstance.dismissAnnouncement.subscribe(() => dismissals.push(true));
    const banner = element.querySelector('.announcement-banner')!;
    expect(banner.getAttribute('role')).toBe('status');
    expect(banner.textContent).toContain('Плановые работы');
    expect(banner.textContent).toContain('В субботу с 22:00');

    button('layout.app_shell.zakryt_obyavlenie')!.click();
    expect(dismissals).toHaveLength(1);

    fixture.componentRef.setInput('isDismissingAnnouncement', true);
    fixture.detectChanges();
    expect(button('layout.app_shell.zakryt_obyavlenie')!.disabled).toBe(true);
  });

  it('hands a picked language to the shell with a way to show the current one again', () => {
    const i18n = TestBed.inject(I18nService);
    i18n.languages.set([
      { code: 'ru', name: 'Русский' },
      { code: 'en', name: 'English' },
    ] as ReturnType<typeof i18n.languages>);
    const { fixture } = render();
    const requests: LanguageChangeRequest[] = [];
    fixture.componentInstance.changeLanguage.subscribe((request) => requests.push(request));
    const picker = fixture.debugElement.query(By.css('smt-select.lang-select'))
      .componentInstance as SMTSelectComponent<string>;

    expect(picker.options().map((option) => option.label)).toEqual(['RU — Русский', 'EN — English']);
    picker.pick(picker.options().find((option) => option.id === 'en')!);
    fixture.detectChanges();
    expect(requests.map((request) => request.code)).toEqual(['en']);

    requests[0].revert();
    fixture.detectChanges();
    expect(picker.value()).toBe('ru');
    expect(requests).toHaveLength(1);
  });
});
