import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { RouterModule } from '@angular/router';
import { TranslatePipe } from '@core/services/i18n.service';
import { NavSection } from '../app-shell.models';
import { User } from '@core/models/auth.models';

@Component({
  selector: 'app-sidebar-flyout',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterModule, TranslatePipe],
  template: `
    <!-- Collapsed Rail Flyout Popover (Opens next to hovered/clicked category) -->
    @if (isCollapsed() && !isMobile() && isFlyoutVisible() && hoveredFlyoutSection(); as section) {
      <div
        class="rail-flyout-popover"
        [style.top.px]="flyoutAnchorTop()"
        (mouseenter)="flyoutMouseEnter.emit()"
        (mouseleave)="flyoutMouseLeave.emit()"
        role="menu"
        [attr.aria-label]="section.titleKey | t"
      >
        <div class="flyout-header">
          <span class="flyout-section-title">{{ section.titleKey | t }}</span>
        </div>
        <div class="flyout-body">
          @for (item of section.items; track item) {
            <!-- Internal link item -->
            @if (item.permission() && !item.children?.length && !item.external) {
              <a
                [routerLink]="item.route"
                routerLinkActive="active"
                [routerLinkActiveOptions]="{ exact: !!item.exact }"
                class="flyout-item"
                (click)="flyoutItemClick.emit()"
                role="menuitem"
              >
                <span class="material-symbols-outlined flyout-icon" aria-hidden="true">{{ item.icon }}</span>
                <span class="flyout-label">{{ item.label ? item.label : (item.titleKey || item.labelKey! | t) }}</span>
                @if (item.badge && item.badge() > 0) {
                  <span class="unread-chip flyout-chip">
                    {{ item.badge() }}
                  </span>
                }
              </a>
            }

            <!-- External link item -->
            @if (item.permission() && !item.children?.length && item.external) {
              <a
                [href]="item.targetUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="flyout-item"
                (click)="flyoutItemClick.emit()"
                role="menuitem"
              >
                <span class="material-symbols-outlined flyout-icon" aria-hidden="true">{{ item.icon }}</span>
                <span class="flyout-label">{{ item.label ? item.label : (item.titleKey || item.labelKey! | t) }}</span>
                <span class="material-symbols-outlined flyout-ext-icon" aria-hidden="true">open_in_new</span>
              </a>
            }

            <!-- Submenu group item -->
            @if (item.permission() && item.children?.length) {
              <div class="flyout-parent-group">
                <div class="flyout-item flyout-parent-header">
                  <span class="material-symbols-outlined flyout-icon" aria-hidden="true">{{ item.icon }}</span>
                  <span class="flyout-label">{{
                    item.label ? item.label : (item.titleKey || item.labelKey! | t)
                  }}</span>
                </div>
                <div class="flyout-subitems">
                  @for (child of item.children; track child) {
                    @if (child.permission()) {
                      <a
                        [routerLink]="child.route"
                        routerLinkActive="active"
                        [routerLinkActiveOptions]="{ exact: !!child.exact }"
                        class="flyout-item flyout-subitem"
                        (click)="flyoutItemClick.emit()"
                        role="menuitem"
                      >
                        <span class="material-symbols-outlined flyout-icon sub-icon" aria-hidden="true">{{
                          child.icon
                        }}</span>
                        <span class="flyout-label">{{ child.label ? child.label : (child.labelKey! | t) }}</span>
                      </a>
                    }
                  }
                </div>
              </div>
            }
          }
        </div>
      </div>
    }

    <!-- Collapsed Rail Profile Popover -->
    @if (isCollapsed() && !isMobile() && isProfileFlyoutVisible()) {
      <div
        class="rail-flyout-popover profile-flyout"
        [style.bottom.px]="12"
        (mouseenter)="profileFlyoutMouseEnter.emit()"
        (mouseleave)="profileFlyoutMouseLeave.emit()"
        role="menu"
      >
        <div class="flyout-header profile-flyout-header">
          <div class="flyout-user-name">{{ currentUser()?.name }}</div>
          <div class="flyout-user-role font-mono">&#64;{{ currentUser()?.login }}</div>
        </div>
        <div class="flyout-body">
          <a routerLink="/iam/profile" class="flyout-item" (click)="profileFlyoutClick.emit()" role="menuitem">
            <span class="material-symbols-outlined flyout-icon" aria-hidden="true">account_circle</span>
            <span class="flyout-label">{{ 'nav.profile' | t }}</span>
          </a>
          <a routerLink="/exports" class="flyout-item" (click)="profileFlyoutClick.emit()" role="menuitem">
            <span class="material-symbols-outlined flyout-icon" aria-hidden="true">download</span>
            <span class="flyout-label">{{ 'nav.exports' | t }}</span>
          </a>
          <a routerLink="/settings" class="flyout-item" (click)="profileFlyoutClick.emit()" role="menuitem">
            <span class="material-symbols-outlined flyout-icon" aria-hidden="true">settings</span>
            <span class="flyout-label">{{ 'nav.settings' | t }}</span>
          </a>
          <button type="button" class="flyout-item flyout-btn text-danger" (click)="logout.emit()" role="menuitem">
            <span class="material-symbols-outlined flyout-icon" aria-hidden="true">logout</span>
            <span class="flyout-label">{{ 'layout.app_shell.vyyti_iz_sistemy' | t }}</span>
          </button>
        </div>
      </div>
    }
  `,
  styleUrl: './app-sidebar-flyout.component.css',
})
export class AppSidebarFlyoutComponent {
  readonly isCollapsed = input(false);
  readonly isMobile = input(false);
  readonly isFlyoutVisible = input(false);
  readonly hoveredFlyoutSection = input<NavSection | null>(null);
  readonly flyoutAnchorTop = input(0);
  readonly isProfileFlyoutVisible = input(false);
  readonly currentUser = input<User | null>(null);

  readonly flyoutMouseEnter = output<void>();
  readonly flyoutMouseLeave = output<void>();
  readonly flyoutItemClick = output<void>();
  readonly profileFlyoutMouseEnter = output<void>();
  readonly profileFlyoutMouseLeave = output<void>();
  readonly profileFlyoutClick = output<void>();
  readonly logout = output<void>();
}
