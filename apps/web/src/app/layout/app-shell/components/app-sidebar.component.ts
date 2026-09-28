import { ChangeDetectionStrategy, Component, ElementRef, input, output, viewChild } from '@angular/core';

import { RouterModule } from '@angular/router';
import { TranslatePipe } from '@core/services/i18n.service';
import { NavItem, NavSection } from '../app-shell.models';
import { AppSidebarNavSectionsComponent } from './app-sidebar-nav-sections.component';
import { AppSidebarFlyoutComponent } from './app-sidebar-flyout.component';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';
import { User } from '@core/models/auth.models';

@Component({
  selector: 'app-sidebar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTAvatarComponent, RouterModule, TranslatePipe, AppSidebarNavSectionsComponent, AppSidebarFlyoutComponent],
  templateUrl: './app-sidebar.component.html',
  styleUrl: './app-sidebar.component.css',
})
export class AppSidebarComponent {
  readonly isSectionActive = input.required<(section: NavSection) => boolean>();
  readonly isRouteActive = input.required<(route: string, exact?: boolean) => boolean>();
  readonly getSectionIcon = input.required<(id: string) => string>();
  readonly hasVisibleItems = input.required<(section: NavSection) => boolean>();
  readonly isSectionExpanded = input.required<(sectionId: string) => boolean>();
  readonly isSubmenuExpanded = input.required<(itemId: string) => boolean>();

  readonly getSectionBadge = input.required<(section: NavSection) => number>();

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
  readonly onNavClick = output<void>();
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
}
