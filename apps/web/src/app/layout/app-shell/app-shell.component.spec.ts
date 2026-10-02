import { signal } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { BehaviorSubject, of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SMTSelectComponent } from '@shared/ui-kit/components/forms/select';
import { AuthService } from '@core/services/auth.service';
import { IdleLockService } from '@core/services/idle-lock.service';
import { CommandPaletteService } from '@core/services/command-palette.service';
import { I18nService } from '@core/services/i18n.service';
import { NotificationService } from '@core/services/notification.service';
import { PermissionService } from '@core/services/permission.service';
import { ThemeService } from '@core/services/theme.service';
import { ModuleService } from '@core/services/module.service';
import { NavigationService } from '@core/services/navigation.service';
import { translateTest } from '@testing/i18n-test.stub';
import { COLLAPSED_SECTIONS_KEY, COLLAPSED_STATE_KEY } from './app-shell.models';
import { AppShellComponent } from './app-shell.component';

/*
 * The shell's own contract: it wires the header, the sidebar and the palette to its
 * services, drives the mobile drawer and its focus, and closes the rail flyouts. The
 * children, the navigation model and the flyout timing have their own specs.
 */
describe('AppShellComponent', () => {
  const viewport = new BehaviorSubject({ matches: false, breakpoints: {} });
  const paletteService = {
    isOpen: signal(false),
    open: vi.fn(() => paletteService.isOpen.set(true)),
    close: vi.fn(() => paletteService.isOpen.set(false)),
    toggle: vi.fn(() => paletteService.isOpen.update((open) => !open)),
    search: vi.fn(() => of({ query: '', totalHits: 0, hits: [] })),
    categories: vi.fn(() => of([])),
  };
  const languages = ['ru', 'uz', 'en', 'de', 'tr'].map((code) => ({ code, name: code.toUpperCase() }));

  beforeEach(async () => {
    localStorage.removeItem(COLLAPSED_STATE_KEY);
    localStorage.removeItem(COLLAPSED_SECTIONS_KEY);
    paletteService.isOpen.set(false);
    await TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [
        provideRouter([]),
        { provide: BreakpointObserver, useValue: { observe: () => viewport.asObservable() } },
        {
          provide: AuthService,
          useValue: {
            currentUser: signal({ name: 'Иван Иванов', login: 'ivan' }),
            isLoggingOut: signal(false),
            logout: vi.fn(),
          },
        },
        // The idle lock has its own spec; here it only has to stay quiet.
        { provide: IdleLockService, useValue: { warningSeconds: signal(null), keepWorking: () => undefined } },
        {
          provide: PermissionService,
          useValue: { canView: () => true, canUpdate: () => true, hasPermissionKey: () => true },
        },
        { provide: ThemeService, useValue: { currentTheme: signal('light'), toggleTheme: vi.fn() } },
        {
          provide: I18nService,
          useValue: {
            currentLang: signal('ru'),
            isLoading: signal(false),
            languages: signal(languages),
            setLanguage: vi.fn(() => of(undefined)),
            translate: translateTest,
          },
        },
        {
          provide: NotificationService,
          useValue: {
            unreadCount: signal(3),
            activeAnnouncement: signal(null),
            fetchUnreadCount: () => of(3),
            fetchActiveAnnouncement: () => of(null),
            connectSse: vi.fn(),
            disconnectSse: vi.fn(),
            resetSession: vi.fn(),
          },
        },
        { provide: CommandPaletteService, useValue: paletteService },
        {
          provide: ModuleService,
          useValue: { isModuleActive: () => true, getActiveCustomModules: () => [], loadActiveModules: () => of([]) },
        },
        {
          provide: NavigationService,
          useValue: {
            activeItems: signal([]),
            entityItems: signal([]),
            loadActiveItems: () => of([]),
            loadEntityItems: () => of([]),
          },
        },
      ],
    }).compileComponents();
  });

  function create(mobile = false) {
    viewport.next({ matches: mobile, breakpoints: {} });
    const fixture = TestBed.createComponent(AppShellComponent);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const find = <T extends HTMLElement = HTMLElement>(selector: string) => host.querySelector<T>(selector);
    return { fixture, host, find, shell: fixture.componentInstance };
  }

  async function settle(fixture: ComponentFixture<AppShellComponent>) {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  /** Opens the mobile drawer from the header button that has the focus. */
  async function openDrawer() {
    const view = create(true);
    const opener = view.find<HTMLButtonElement>('.mobile-menu-btn')!;
    opener.focus();
    opener.click();
    await settle(view.fixture);
    return { ...view, opener };
  }

  const keydown = (key: string, init: KeyboardEventInit = {}) =>
    new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });

  it('renders the named landmarks, the brand, the named shell actions and the unread count', () => {
    const { fixture, host, find } = create();

    expect(find('a.skip-link[href="#main-content"]')).not.toBeNull();
    expect(find('main#main-content')).not.toBeNull();
    expect(find('nav[aria-label="Основная навигация"]')).not.toBeNull();
    expect(host.textContent).toContain('SmartupCMS');
    expect(find('a[href="/announcements"]')).not.toBeNull();
    expect(find('a[href="/system"]')).not.toBeNull();
    for (const label of ['Переключить тему', 'Открыть уведомления', 'Выйти из системы']) {
      expect(find(`button[aria-label="${label}"]`)).not.toBeNull();
    }
    const selector = find('#app-language-selector')!;
    expect(selector.getAttribute('role')).toBe('combobox');
    expect(selector.getAttribute('aria-label')).toBe('Язык интерфейса');
    const picker = fixture.debugElement.query(By.css('smt-select.lang-select'))
      .componentInstance as SMTSelectComponent<string>;
    expect(picker.options().map((option) => option.id)).toEqual(['ru', 'uz', 'en', 'de', 'tr']);
    expect(find('.notif-btn .sr-only')?.textContent).toContain('3');
  });

  it('focuses this page content and cancels base-relative navigation when skipping the shell', () => {
    const { find } = create();
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    find('.skip-link')!.dispatchEvent(click);

    expect(click.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(find('main'));
  });

  it('excludes the closed mobile sidebar from interaction but keeps desktop navigation available', () => {
    const { fixture, find } = create(true);
    const sidebar = find('.sidebar')!;
    expect(sidebar.hasAttribute('inert')).toBe(true);
    expect(sidebar.getAttribute('aria-hidden')).toBe('true');

    viewport.next({ matches: false, breakpoints: {} });
    fixture.detectChanges();
    expect(sidebar.hasAttribute('inert')).toBe(false);
    expect(sidebar.getAttribute('aria-hidden')).toBeNull();
    expect(sidebar.getAttribute('aria-modal')).toBeNull();
  });

  it('opens mobile navigation with focus inside and an inactive background', async () => {
    const { find, opener } = await openDrawer();
    const sidebar = find('.sidebar')!;

    expect(sidebar.contains(document.activeElement)).toBe(true);
    expect(sidebar.getAttribute('role')).toBe('dialog');
    expect(sidebar.getAttribute('aria-modal')).toBe('true');
    expect(sidebar.hasAttribute('inert')).toBe(false);
    expect(find('.main-wrapper')!.hasAttribute('inert')).toBe(true);
    expect(opener.getAttribute('aria-expanded')).toBe('true');
    expect(opener.getAttribute('aria-controls')).toBe(sidebar.id);
  });

  it.each(['button', 'Escape', 'backdrop'])(
    'closes mobile navigation via %s and restores focus without collapsing desktop',
    async (via) => {
      const { fixture, find, shell, opener } = await openDrawer();
      if (via === 'button') find('.mobile-drawer-close')?.click();
      if (via === 'backdrop') find('.mobile-drawer-backdrop')?.click();
      if (via === 'Escape') document.activeElement?.dispatchEvent(keydown('Escape'));
      await settle(fixture);

      expect(find('.sidebar.mobile-open')).toBeNull();
      expect(shell.nav.isCollapsed()).toBe(false);
      expect(document.activeElement).toBe(opener);
      expect(find('.main-wrapper')!.hasAttribute('inert')).toBe(false);
      expect(opener.getAttribute('aria-expanded')).toBe('false');
    },
  );

  it('closes the drawer without taking the focus back when one of its links is followed', async () => {
    const { fixture, find, shell } = await openDrawer();
    find('.sidebar a[href="/tasks"]')!.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    fixture.detectChanges();

    expect(shell.isMobileMenuOpen()).toBe(false);
    expect(find('.sidebar.mobile-open')).toBeNull();
  });

  it('clears the mobile overlay when resizing to desktop and does not reopen it on return', async () => {
    const { fixture, find } = await openDrawer();

    viewport.next({ matches: false, breakpoints: {} });
    await settle(fixture);
    expect(find('.mobile-drawer-backdrop')).toBeNull();
    expect(find('.main-wrapper')!.hasAttribute('inert')).toBe(false);
    expect(document.activeElement).toBe(find('main'));

    viewport.next({ matches: true, breakpoints: {} });
    fixture.detectChanges();
    expect(find('.sidebar.mobile-open')).toBeNull();
  });

  it('hands mobile drawer focus to search without competing dialogs and returns it to the visible opener', async () => {
    const { fixture, host, find, opener } = await openDrawer();
    fixture.autoDetectChanges();

    const shortcut = keydown('k', { code: 'KeyK', ctrlKey: true });
    document.activeElement?.dispatchEvent(shortcut);
    await settle(fixture);
    expect(shortcut.defaultPrevented).toBe(true);
    expect(find('.sidebar.mobile-open')).toBeNull();
    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(1);
    expect(document.activeElement).toBe(find('.palette-input'));

    document.activeElement?.dispatchEvent(keydown('Escape'));
    await settle(fixture);
    expect(host.querySelectorAll('[role="dialog"]')).toHaveLength(0);
    expect(document.activeElement).toBe(opener);
  });

  it('folds the rail from its toggle into named category buttons and unfolds it again', () => {
    const { fixture, host, find, shell } = create();
    expect(host.querySelectorAll('.nav-section-title').length).toBeGreaterThan(0);
    expect(host.querySelectorAll('.rail-category-btn')).toHaveLength(0);

    find('.toggle-btn')!.click();
    fixture.detectChanges();
    expect(shell.nav.isCollapsed()).toBe(true);
    expect(find('.sidebar')!.classList.contains('collapsed')).toBe(true);
    expect(host.querySelectorAll('.nav-section-title')).toHaveLength(0);
    expect(host.querySelectorAll('.rail-category-btn').length).toBeGreaterThan(0);
    expect(localStorage.getItem(COLLAPSED_STATE_KEY)).toBe('true');

    find('.toggle-btn')!.click();
    fixture.detectChanges();
    expect(shell.nav.isCollapsed()).toBe(false);
    expect(host.querySelectorAll('.rail-category-btn')).toHaveLength(0);
  });

  it('folds and unfolds a navigation section from its header', () => {
    const { fixture, find } = create();
    const header = find<HTMLButtonElement>('.nav-section-header')!;
    const content = find('#section-content-workspace')!;
    expect(header.getAttribute('aria-expanded')).toBe('true');

    header.click();
    fixture.detectChanges();
    expect(header.getAttribute('aria-expanded')).toBe('false');
    expect(content.classList.contains('collapsed')).toBe(true);

    header.click();
    fixture.detectChanges();
    expect(header.getAttribute('aria-expanded')).toBe('true');
    expect(content.classList.contains('collapsed')).toBe(false);
  });

  it('opens a section flyout from the folded rail and closes it with Escape or a click outside', () => {
    const { fixture, host, find, shell } = create();
    shell.nav.isCollapsed.set(true);
    fixture.detectChanges();
    const flyout = () => find('.rail-flyout-popover:not(.profile-flyout)');
    const rail = () => Array.from(host.querySelectorAll<HTMLButtonElement>('.rail-category-btn'));
    const open = (label: string) => {
      rail()
        .find((button) => button.getAttribute('aria-label') === label)!
        .click();
      fixture.detectChanges();
    };
    const iam = shell.nav.navSections().find((section) => section.id === 'iam')!;

    open('Команда и доступ');
    expect(flyout()?.textContent).toContain('Команда и доступ');
    expect(flyout()?.querySelectorAll('.flyout-item')).toHaveLength(iam.items.length);
    open('Администрирование');
    expect(shell.flyout.hoveredFlyoutSection()?.id).toBe('administration');

    host.dispatchEvent(keydown('Escape'));
    fixture.detectChanges();
    expect(flyout()).toBeNull();

    open('Команда и доступ');
    document.body.click();
    fixture.detectChanges();
    expect(flyout()).toBeNull();
  });

  it('shows the profile menu over the folded rail footer and closes it when a page is followed', () => {
    const { fixture, find, shell } = create();
    shell.nav.isCollapsed.set(true);
    fixture.detectChanges();

    find('.user-profile-btn')!.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
    const profile = find('.rail-flyout-popover.profile-flyout')!;
    expect(profile.textContent).toContain('Иван Иванов');

    profile.querySelector<HTMLAnchorElement>('a[href="/exports"]')!.click();
    fixture.detectChanges();
    expect(shell.flyout.isProfileFlyoutVisible()).toBe(false);
    expect(find('.rail-flyout-popover.profile-flyout')).toBeNull();
  });
});
