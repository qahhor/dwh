import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { RouterModule } from '@angular/router';
import { TranslatePipe } from '@core/services/i18n.service';
import { NavSection } from '../app-shell.models';

@Component({
  selector: 'app-sidebar-nav-sections',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterModule, TranslatePipe],
  template: `
    @for (section of navSections(); track section; let first = $first) {
      @if (hasVisibleItems()(section)) {
        <button
          type="button"
          class="nav-section-header"
          (click)="toggleSection.emit({ id: section.id, event: $event })"
          [attr.aria-expanded]="isSectionExpanded()(section.id)"
          [attr.aria-controls]="'section-content-' + section.id"
          [attr.aria-label]="
            (isSectionExpanded()(section.id) ? 'layout.app_shell.collapse_section' : 'layout.app_shell.expand_section')
              | t
          "
        >
          <span class="nav-section-title">{{ section.titleKey | t }}</span>
          @if (!isSectionExpanded()(section.id) && isSectionActive()(section)) {
            <span class="section-active-dot" [attr.title]="'common.active' | t"></span>
          }
          <span
            class="material-symbols-outlined section-chevron"
            [class.rotated]="!isSectionExpanded()(section.id)"
            aria-hidden="true"
            >expand_more</span
          >
        </button>
      }
      <div
        [id]="'section-content-' + section.id"
        class="nav-section-content"
        [class.collapsed]="!isSectionExpanded()(section.id)"
      >
        @for (item of section.items; track item) {
          <!-- Internal link item -->
          @if (item.permission() && !item.children?.length && !item.external) {
            <a
              [routerLink]="item.route"
              routerLinkActive="active"
              [routerLinkActiveOptions]="{ exact: !!item.exact }"
              [attr.aria-current]="item.route && isRouteActive()(item.route, !!item.exact) ? 'page' : null"
              class="nav-item"
              [title]="item.label ? item.label : (item.titleKey || item.labelKey! | t)"
            >
              <span class="material-symbols-outlined nav-icon" aria-hidden="true">{{ item.icon }}</span>
              <span class="nav-label">{{ item.label ? item.label : (item.labelKey! | t) }}</span>
              @if (item.badge && item.badge() > 0) {
                <span class="unread-chip">
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
              class="nav-item"
              [title]="item.label ? item.label : (item.titleKey || item.labelKey! | t)"
            >
              <span class="material-symbols-outlined nav-icon" aria-hidden="true">{{ item.icon }}</span>
              <span class="nav-label">{{ item.label ? item.label : (item.labelKey! | t) }}</span>
              <span
                class="material-symbols-outlined sub-icon"
                style="margin-left: auto; font-size: 14px; opacity: 0.7;"
                aria-hidden="true"
                >open_in_new</span
              >
            </a>
          }

          <!-- Submenu parent -->
          @if (item.permission() && item.children?.length) {
            <div class="nav-parent-group">
              <button
                type="button"
                class="nav-item nav-parent-btn"
                (click)="toggleSubmenu.emit({ id: item.id, event: $event })"
                [attr.aria-expanded]="isSubmenuExpanded()(item.id)"
                [title]="item.label ? item.label : (item.titleKey || item.labelKey! | t)"
              >
                <span class="material-symbols-outlined nav-icon" aria-hidden="true">{{ item.icon }}</span>
                <span class="nav-label">{{ item.label ? item.label : (item.labelKey! | t) }}</span>
                <span
                  class="material-symbols-outlined submenu-chevron"
                  [class.rotated]="!isSubmenuExpanded()(item.id)"
                  aria-hidden="true"
                  >expand_more</span
                >
              </button>

              @if (isSubmenuExpanded()(item.id)) {
                <div class="nav-submenu">
                  @for (child of item.children; track child) {
                    @if (child.permission()) {
                      <a
                        [routerLink]="child.route"
                        routerLinkActive="active"
                        [routerLinkActiveOptions]="{ exact: !!child.exact }"
                        [attr.aria-current]="child.route && isRouteActive()(child.route, !!child.exact) ? 'page' : null"
                        class="nav-item nav-subitem"
                        [title]="child.label ? child.label : (child.titleKey || child.labelKey! | t)"
                      >
                        <span class="material-symbols-outlined nav-icon sub-icon" aria-hidden="true">{{
                          child.icon
                        }}</span>
                        <span class="nav-label">{{ child.label ? child.label : (child.labelKey! | t) }}</span>
                      </a>
                    }
                  }
                </div>
              }
            </div>
          }
        }
      </div>
    }
  `,
  styleUrl: './app-sidebar-nav-sections.component.css',
})
export class AppSidebarNavSectionsComponent {
  readonly hasVisibleItems = input.required<(section: NavSection) => boolean>();
  readonly isSectionExpanded = input.required<(sectionId: string) => boolean>();
  readonly isSectionActive = input.required<(section: NavSection) => boolean>();
  readonly isSubmenuExpanded = input.required<(itemId: string) => boolean>();
  readonly isRouteActive = input.required<(route: string, exact?: boolean) => boolean>();

  readonly navSections = input<NavSection[]>([]);

  readonly toggleSection = output<{
    id: string;
    event: MouseEvent;
  }>();
  readonly toggleSubmenu = output<{
    id: string;
    event: MouseEvent;
  }>();
}
