import { Component, computed, EventEmitter, inject, Input, Output, Signal, TemplateRef, viewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { UiButtonComponent } from '../../../../shared/ui/ui-button.component';
import { UiServerTableComponent } from '../../../../shared/ui/ui-server-table.component';
import { KeysetPager } from '../../../../shared/paging/keyset-pager';
import { TableConfig } from '../../../../shared/ui-kit/components/table/table.types';
import { User } from '../../../../core/models/auth.models';
import { SMTAvatarComponent } from '../../../../shared/ui-kit/components/avatar';
import { SMTDropdownButtonComponent, SMTMenuItem } from '../../../../shared/ui-kit/components/dropdown-button';

type UserMenuAction = 'block' | 'unblock' | 'delete';

/**
 * The user list, a page at a time from the server.
 *
 * The server returns users in the order they were created and pages by
 * cursor, so the table offers no column sorting: sorting the rows of one page
 * would only look like sorting the list.
 */
@Component({
  selector: 'app-user-table-view',
  standalone: true,
  imports: [
    SMTDropdownButtonComponent, SMTAvatarComponent, CommonModule,
    TranslatePipe,
    UiButtonComponent,
    UiServerTableComponent
  ],
  template: `
    <div class="table-container" role="region" [attr.aria-label]="'iam.tablica_polzovateley' | t" [attr.aria-busy]="pager.loading()">
      <ui-server-table
        [pager]="pager"
        [config]="tableConfig()"
        [loadingLabel]="'iam.users.loading' | t"
        [errorLabel]="'iam.users.load_error' | t"
        errorId="users-load-error"
        [countsPage]="true"
        [emptyTemplate]="emptyState()" />
    </div>

    <ng-template #identityCell let-u>
      <button type="button" class="user-identity" (click)="viewUser.emit(u)" [attr.aria-label]="'iam.open_user_profile_named' | t:{name: u.name}">
        <smt-avatar [name]="u.name" smtSize="sm" />
        <span class="identity-info">
          <span class="full-name">{{ u.name }}</span>
          <span class="login-handle font-mono">&#64;{{ u.login }}</span>
        </span>
      </button>
    </ng-template>
    <ng-template #contactsCell let-u>
      <div class="contacts-cell">
        <span class="contact-email">{{ u.email }}</span>
        @if (u.phone) { <span class="contact-phone font-mono">{{ u.phone }}</span> }
      </div>
    </ng-template>
    <ng-template #rolesCell let-u>
      @let roleNames = getUserRoleNames(u);
      <div class="roles-wrap">
        @for (roleName of roleNames; track roleName) { <span class="role-pill">{{ roleName }}</span> }
        @empty { <span class="muted-dash">—</span> }
      </div>
    </ng-template>
    <ng-template #managerCell let-u>
      @let managerName = getManagerName(u);
      @if (managerName) { <span class="manager-text">{{ managerName }}</span> }
      @else { <span class="muted-dash">—</span> }
    </ng-template>
    <ng-template #twoFactorCell let-u>
      @let twoFactorLabel = (u.is2faEnabled ? 'iam.two_factor_enabled' : 'iam.two_factor_disabled_short') | t;
      <!-- role="img": the name is announced and the icon's ligature text ("check_circle") is not. -->
      <span role="img" class="material-symbols-outlined twofa-dot" [class.active]="u.is2faEnabled" [title]="twoFactorLabel" [attr.aria-label]="twoFactorLabel">
        {{ u.is2faEnabled ? 'check_circle' : 'remove' }}
      </span>
    </ng-template>
    <ng-template #statusCell let-u>
      <span class="status-indicator" [class.active]="u.state === 'A'">
        <span class="dot" aria-hidden="true"></span>
        {{ (u.state === 'A' ? 'common.active_masculine' : 'common.disabled_masculine') | t }}
      </span>
    </ng-template>
    <ng-template #createdCell let-u>
      <span class="text-muted font-mono text-xs tabular-nums">{{ u.createdAt | date:'dd.MM.yyyy' }}</span>
    </ng-template>
    <ng-template #actionsCell let-u>
      <div class="row-actions">
        <ui-button variant="ghost" size="sm" icon="visibility" [ariaLabel]="'iam.view_user_named' | t:{name: u.name}" [title]="'iam.prosmotr' | t" (onClick)="viewUser.emit(u)"></ui-button>
        @if (canUpdateUser) {
          <ui-button variant="ghost" size="sm" icon="edit" [ariaLabel]="'iam.edit_user_named' | t:{name: u.name}" [title]="'common.edit' | t" (onClick)="editUser.emit(u)"></ui-button>
        }
        @if (moreActions(u); as actions) {
          <smt-dropdown-button
            smtIconOnly
            icon="more_vert"
            data-testid="user-more-actions"
            [smtAriaLabel]="'iam.more_actions_named' | t:{name: u.name}"
            [items]="actions"
            (itemSelect)="runAction($event, u)" />
        }
      </div>
    </ng-template>
    <ng-template #emptyStateTpl>
      <div class="empty-state">
        <span class="material-symbols-outlined empty-ico" aria-hidden="true">search_off</span>
        <p class="empty-text">{{ 'iam.polzovateli_ne_naydeny' | t }}</p>
      </div>
    </ng-template>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .table-container { min-width: 0; }

    /* Identity */
    .user-identity {
      width: 100%;
      border: 0;
      background: transparent;
      color: inherit;
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;
      font: inherit;
      padding: 0;
      text-align: left;
    }
    .identity-info {
      display: flex;
      flex-direction: column;
    }
    .full-name { font-weight: 500; }
    .login-handle { font-size: 11px; color: var(--text-muted); }

    .contacts-cell {
      display: flex;
      flex-direction: column;
    }
    .contact-email { font-size: 12px; }
    .contact-phone { font-size: 11px; color: var(--text-muted); }

    .roles-wrap {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
    }
    .role-pill {
      font-size: 11px;
      padding: 1px 6px;
      border-radius: 4px;
      background-color: var(--bg-hover);
      border: 1px solid var(--border-color);
      color: var(--text-main);
    }
    .manager-text { font-size: 12px; }
    .muted-dash { color: var(--text-light); }

    .twofa-dot {
      font-size: 16px;
      color: var(--text-light);
    }
    .twofa-dot.active { color: var(--success); }

    .status-indicator {
      display: inline-flex;
      align-items: center;
      gap: 5px;
      font-size: 12px;
      color: var(--text-muted);
    }
    .status-indicator .dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background-color: var(--text-light);
    }
    .status-indicator.active { color: var(--text-main); }
    .status-indicator.active .dot { background-color: var(--success); }

    .row-actions { display: flex; justify-content: flex-end; gap: 2px; }

    .empty-state {
      text-align: center;
      padding: 32px 12px;
      color: var(--text-muted);
    }
    .empty-ico { font-size: 32px; color: var(--text-light); margin-bottom: 4px; }
    .empty-text { font-size: 13px; margin: 0; }

    .font-mono { font-family: monospace; }
    .text-muted { color: var(--text-muted); }
    .text-xs { font-size: 11px; }
    .tabular-nums { font-variant-numeric: tabular-nums; }

    .user-identity:focus-visible {
      outline: 2px solid var(--primary);
      outline-offset: 2px;
      border-radius: var(--radius-sm);
    }
  `]
})
export class UserTableViewComponent {
  @Input({ required: true }) pager!: KeysetPager<User>;
  @Input() canUpdateUser = false;
  @Input() canBlockUser = false;
  @Input() canUnblockUser = false;
  @Input() canDeleteUser = false;

  @Input() getUserRoleNames!: (u: User) => string[];
  @Input() getManagerName!: (u: User) => string | null;

  @Output() viewUser = new EventEmitter<User>();
  @Output() editUser = new EventEmitter<User>();
  @Output() toggleState = new EventEmitter<{ user: User, action: 'block' | 'unblock' }>();
  @Output() deleteUser = new EventEmitter<User>();

  private readonly i18n = inject(I18nService);
  private readonly identityCell = viewChild.required<TemplateRef<unknown>>('identityCell');
  private readonly contactsCell = viewChild.required<TemplateRef<unknown>>('contactsCell');
  private readonly rolesCell = viewChild.required<TemplateRef<unknown>>('rolesCell');
  private readonly managerCell = viewChild.required<TemplateRef<unknown>>('managerCell');
  private readonly twoFactorCell = viewChild.required<TemplateRef<unknown>>('twoFactorCell');
  private readonly statusCell = viewChild.required<TemplateRef<unknown>>('statusCell');
  private readonly createdCell = viewChild.required<TemplateRef<unknown>>('createdCell');
  private readonly actionsCell = viewChild.required<TemplateRef<unknown>>('actionsCell');
  /** Per user, the menu built for the rights and language it was built with, so an open menu is not rebuilt. */
  private readonly actionMenus = new WeakMap<User, { key: string; items: SMTMenuItem<UserMenuAction>[] | null }>();
  readonly emptyState = viewChild.required<TemplateRef<unknown>>('emptyStateTpl');

  readonly tableConfig = computed<TableConfig<User>>(() => {
    const header = (value: string) => ({ type: 'primitive' as const, value });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    // Every row is its own grid, so tracks are fixed or shares of the width, never content-sized.
    const share = 'max(150px, calc((100% - 480px) / 4))';
    return {
      trackBy: (_index, user) => user.id,
      layout: 'fit',
      ariaLabel: this.i18n.translate('iam.spisok_polzovateley'),
      columnsOrder: ['identity', 'contacts', 'roles', 'manager', 'twoFactor', 'status', 'created', 'actions'],
      columns: {
        identity: { header: header(this.i18n.translate('audit.polzovatel')), content: cell(this.identityCell), width: share },
        contacts: { header: header(this.i18n.translate('iam.kontakty')), content: cell(this.contactsCell), width: share },
        roles: { header: header(this.i18n.translate('iam.roli')), content: cell(this.rolesCell), width: share },
        manager: { header: header(this.i18n.translate('iam.rukovoditel')), content: cell(this.managerCell), width: share },
        twoFactor: { header: header('2FA'), content: cell(this.twoFactorCell), width: '60px', align: 'center' },
        status: { header: header(this.i18n.translate('common.status')), content: cell(this.statusCell), width: '130px' },
        created: { header: header(this.i18n.translate('iam.sozdan')), content: cell(this.createdCell), width: '100px', align: 'right' },
        actions: { header: header(this.i18n.translate('common.actions')), content: cell(this.actionsCell), width: '190px', align: 'right' },
      },
    };
  });

  /** Block or unblock and delete, behind "more" so the row keeps two visible actions; null when none apply. */
  moreActions(user: User): SMTMenuItem<UserMenuAction>[] | null {
    const block = user.state === 'A' && this.canBlockUser;
    const unblock = user.state === 'P' && this.canUnblockUser;
    const remove = user.login !== 'admin' && this.canDeleteUser;
    const key = [block, unblock, remove, this.i18n.currentLang()].join('|');
    const cached = this.actionMenus.get(user);
    if (cached?.key === key) return cached.items;
    const items: SMTMenuItem<UserMenuAction>[] = [
      ...(block ? [{ id: 'block' as const, label: this.i18n.translate('common.block'), icon: 'lock' }] : []),
      ...(unblock ? [{ id: 'unblock' as const, label: this.i18n.translate('common.unblock'), icon: 'lock_open' }] : []),
      ...(remove ? [{ id: 'delete' as const, label: this.i18n.translate('common.delete'), icon: 'delete', danger: true, separated: block || unblock }] : []),
    ];
    const result = items.length > 0 ? items : null;
    this.actionMenus.set(user, { key, items: result });
    return result;
  }

  runAction(action: UserMenuAction, user: User): void {
    if (action === 'delete') this.deleteUser.emit(user);
    else this.toggleState.emit({ user, action });
  }
}
