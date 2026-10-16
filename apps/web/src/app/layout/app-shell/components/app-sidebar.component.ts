import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { CdkTrapFocus } from '@angular/cdk/a11y';

import { RouterModule } from '@angular/router';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { NavSection } from '../app-shell.models';
import { AppSidebarNavSectionsComponent } from './app-sidebar-nav-sections.component';
import { AppSidebarFlyoutComponent } from './app-sidebar-flyout.component';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';
import { User } from '@core/models/auth.models';
import { SMTInputComponent, SMTInputValue } from '@shared/ui-kit/components/forms/input';
import { countNavLinks, filterNavSections } from './filter-nav-sections';

@Component({
  selector: 'app-sidebar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CdkTrapFocus,
    SMTAvatarComponent,
    SMTInputComponent,
    RouterModule,
    TranslatePipe,
    AppSidebarNavSectionsComponent,
    AppSidebarFlyoutComponent,
  ],
  templateUrl: './app-sidebar.component.html',
  styleUrl: './app-sidebar.component.css',
})
export class AppSidebarComponent {
  private readonly injector = inject(Injector);
  private readonly i18n = inject(I18nService);
  readonly isSectionActive = input.required<(section: NavSection) => boolean>();
  readonly isRouteActive = input.required<(route: string, exact?: boolean) => boolean>();
  readonly getSectionIcon = input.required<(id: string) => string>();
  readonly hasVisibleItems = input.required<(section: NavSection) => boolean>();
  readonly isSectionExpanded = input.required<(sectionId: string) => boolean>();
  readonly isSubmenuExpanded = input.required<(itemId: string) => boolean>();

  readonly getSectionBadge = input.required<(section: NavSection) => number>();

  /** The id of the single menu item of the current page (see `activeNavItemId`). */
  readonly activeItemId = input<string | null>(null);
  readonly sidebarId = input('app-sidebar');
  readonly isMobile = input(false);
  readonly isMobileMenuOpen = input(false);
  readonly isCollapsed = input(false);
  readonly navSections = input<NavSection[]>([]);
  readonly isFlyoutVisible = input(false);
  readonly hoveredFlyoutSection = input<NavSection | null>(null);
  readonly flyoutAnchorTop = input(0);
  readonly isProfileFlyoutVisible = input(false);
  readonly currentUser = input<User | null>(null);

  readonly toggleSidebar = output<void>();
  readonly closeMobileMenu = output<boolean>();
  readonly navClick = output<void>();
  readonly toggleSection = output<{
    id: string;
    event: MouseEvent;
  }>();
  readonly toggleSubmenu = output<{
    id: string;
    event: MouseEvent;
  }>();
  readonly categoryClick = output<{
    section: NavSection;
    event: MouseEvent;
  }>();
  readonly categoryMouseEnter = output<{
    section: NavSection;
    event: MouseEvent;
  }>();
  readonly categoryMouseLeave = output<void>();
  readonly profileMouseEnter = output<MouseEvent>();
  readonly profileMouseLeave = output<void>();
  readonly flyoutMouseEnter = output<void>();
  readonly flyoutMouseLeave = output<void>();
  readonly flyoutItemClick = output<void>();
  readonly profileFlyoutMouseEnter = output<void>();
  readonly profileFlyoutMouseLeave = output<void>();
  readonly profileFlyoutClick = output<void>();
  readonly logout = output<void>();

  readonly sidebarElement = viewChild<ElementRef<HTMLElement>>('sidebarElement');
  readonly mobileDrawerClose = viewChild<ElementRef<HTMLButtonElement>>('mobileDrawerClose');
  private readonly flyout = viewChild(AppSidebarFlyoutComponent);

  readonly navigationQuery = signal('');
  private readonly searchCollapsedGroups = signal<ReadonlySet<string>>(new Set());
  readonly searchingNavigation = computed(() => this.navigationQuery().trim().length > 0);
  readonly visibleNavSections = computed(() =>
    this.searchingNavigation()
      ? filterNavSections(this.navSections(), this.navigationQuery(), (key) => this.i18n.translate(key))
      : this.navSections(),
  );
  readonly navigationResultCount = computed(() => countNavLinks(this.visibleNavSections()));

  readonly currentFlyoutSection = computed(() => {
    const section = this.navSections().find((entry) => entry.id === this.hoveredFlyoutSection()?.id);
    return section && this.hasVisibleItems()(section) ? section : null;
  });

  readonly displayedSectionExpanded = (id: string): boolean =>
    this.searchingNavigation() ? !this.searchCollapsedGroups().has(`section:${id}`) : this.isSectionExpanded()(id);
  readonly displayedSubmenuExpanded = (id: string): boolean =>
    this.searchingNavigation() ? !this.searchCollapsedGroups().has(`submenu:${id}`) : this.isSubmenuExpanded()(id);

  setNavigationQuery(value: SMTInputValue): void {
    this.navigationQuery.set(value === null ? '' : String(value));
    this.searchCollapsedGroups.set(new Set());
  }

  toggleNavigationGroup(kind: 'section' | 'submenu', value: { id: string; event: MouseEvent }): void {
    if (!this.searchingNavigation()) {
      (kind === 'section' ? this.toggleSection : this.toggleSubmenu).emit(value);
      return;
    }
    value.event.preventDefault();
    value.event.stopPropagation();
    this.searchCollapsedGroups.update((previous) => {
      const next = new Set(previous);
      const key = `${kind}:${value.id}`;
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  openCategory(section: NavSection, event: MouseEvent): void {
    this.categoryClick.emit({ section, event });
    if (event.detail === 0) {
      afterNextRender(() => this.flyout()?.focusFirstItem(), { injector: this.injector });
    }
  }
}
