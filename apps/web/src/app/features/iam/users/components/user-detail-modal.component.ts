import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  inject,
  viewChild,
  input,
  output,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { Observable } from 'rxjs';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { UiRecordHistoryComponent } from '@shared/ui/ui-record-history.component';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { SMTDialogComponent, SMTDialogContentDirective } from '@shared/ui-kit/components/modal';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { LoginAttemptRecord, User, UserSecuritySummary, UserSession } from '@core/models/auth.models';
import { UserOrgUnitsPanelComponent } from '@features/iam/org-units/public-api';
import { UserEffectivePermissionsPanelComponent } from './user-effective-permissions-panel.component';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';
import { SMTTabBarComponent, SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { optionsMemo } from '@shared/ui-kit/components/forms/radio-group';

@Component({
  selector: 'app-user-detail-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    SMTTabBarComponent,
    SMTAvatarComponent,
    TranslatePipe,
    SMTDialogComponent,
    SMTDialogContentDirective,
    SMTButtonComponent,
    UserOrgUnitsPanelComponent,
    UserEffectivePermissionsPanelComponent,
    UiLocalTableComponent,
    UiRecordHistoryComponent,
    DatePipe,
  ],
  templateUrl: './user-detail-modal.component.html',
  styleUrl: './user-detail-modal.component.css',
})
export class UserDetailModalComponent {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  private readonly i18n = inject(I18nService);

  readonly safeRecordId = input.required<(id: unknown) => boolean>();
  readonly getUserRoleNames = input.required<(u: User) => string[]>();
  readonly getManagerName = input.required<(u: User) => string | null>();

  readonly isOpen = input(false);
  readonly recordLoading = input(false);
  readonly recordError = input(false);
  readonly recordNotFound = input(false);
  readonly activeViewTab = input<'info' | 'security' | 'orgUnits' | 'permissions'>('info');
  readonly isLoadingSecurity = input(false);
  readonly isSecurityActionPending = input(false);
  readonly canUpdateUser = input(false);
  readonly canViewOrgUnits = input(false);
  readonly canViewAssignments = input(false);
  readonly canAssignPermissions = input(false);

  readonly isSubmitting = input(false);

  readonly viewingUser = input<User | null>(null);
  readonly routeRecordId = input<string | null>(null);

  readonly userSecurity = input<UserSecuritySummary | null>(null);

  readonly closeRecordView = output<void>();
  readonly retryRecordView = output<string | null>();
  readonly switchTab = output<{
    tab: 'info' | 'security' | 'orgUnits' | 'permissions';
    userId: number;
  }>();
  readonly openEdit = output<void>();
  readonly forcePasswordChange = output<number>();
  readonly reset2fa = output<number>();
  readonly terminateAllSessions = output<number>();
  readonly terminateSingleSession = output<{
    sessionId: number;
    userId: number;
  }>();
  readonly orgPanelBusy = output<boolean>();
  /** A change made in the dialog raised the user's revision (plan item 3.6). */
  readonly userRevisionChange = output<{ userId: number; revision: number }>();
  /** The user changed since the dialog read it: read it again. */
  readonly refreshUser = output<number>();

  private readonly sessionIpCell = viewChild.required<TemplateRef<unknown>>('sessionIpCell');
  private readonly sessionAgentCell = viewChild.required<TemplateRef<unknown>>('sessionAgentCell');
  private readonly sessionCreatedCell = viewChild.required<TemplateRef<unknown>>('sessionCreatedCell');
  private readonly sessionSeenCell = viewChild.required<TemplateRef<unknown>>('sessionSeenCell');
  private readonly sessionActionCell = viewChild.required<TemplateRef<unknown>>('sessionActionCell');
  private readonly attemptTimeCell = viewChild.required<TemplateRef<unknown>>('attemptTimeCell');
  private readonly attemptIpCell = viewChild.required<TemplateRef<unknown>>('attemptIpCell');
  private readonly attemptStatusCell = viewChild.required<TemplateRef<unknown>>('attemptStatusCell');
  private readonly attemptReasonCell = viewChild.required<TemplateRef<unknown>>('attemptReasonCell');

  readonly orgUnitsPanel = viewChild(UserOrgUnitsPanelComponent);

  readonly sessions = computed(() => this.security()?.activeSessions ?? []);
  readonly attempts = computed(() => this.security()?.recentLoginAttempts ?? []);

  readonly sessionsConfig = computed<TableConfig<UserSession>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, s) => s.id,
      ariaLabel: this.i18n.translate('iam.common.active_sessions'),
      layout: 'fit',
      columns: {
        ip: { header: { type: 'primitive', value: 'IP' }, content: cell(this.sessionIpCell), width: '130px' },
        agent: { header: header('iam.users.detail.device_browser'), content: cell(this.sessionAgentCell) },
        created: {
          header: header('iam.common.created_feminine'),
          content: cell(this.sessionCreatedCell),
          width: '140px',
        },
        seen: { header: header('iam.common.last_activity'), content: cell(this.sessionSeenCell), width: '160px' },
        action: {
          header: header('audit.common.action'),
          content: cell(this.sessionActionCell),
          width: '70px',
          align: 'right',
        },
      },
      columnsOrder: ['ip', 'agent', 'created', 'seen', 'action'],
    };
  });

  readonly attemptsConfig = computed<TableConfig<LoginAttemptRecord>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, att) => att.id,
      ariaLabel: this.i18n.translate('iam.users.detail.login_attempts_history'),
      layout: 'fit',
      columns: {
        time: { header: header('iam.users.detail.time'), content: cell(this.attemptTimeCell), width: '160px' },
        ip: { header: { type: 'primitive', value: 'IP' }, content: cell(this.attemptIpCell), width: '130px' },
        status: { header: header('common.status'), content: cell(this.attemptStatusCell), width: '120px' },
        reason: { header: header('iam.users.detail.failure_reason'), content: cell(this.attemptReasonCell) },
      },
      columnsOrder: ['time', 'ip', 'status', 'reason'],
    };
  });

  private readonly security = computed<UserSecuritySummary | null>(() => this.userSecurity());

  /** The summary carries every open session, so a header click sorts them all. */
  readonly sessionSortValues = {
    ip: (s: UserSession) => s.ip,
    agent: (s: UserSession) => s.userAgent,
    created: (s: UserSession) => new Date(s.createdAt),
    seen: (s: UserSession) => new Date(s.lastSeenAt),
  };

  /** The recent attempts the summary returns, sortable by time, address, outcome and reason. */
  readonly attemptSortValues = {
    time: (att: LoginAttemptRecord) => new Date(att.attemptAt),
    ip: (att: LoginAttemptRecord) => att.ip,
    status: (att: LoginAttemptRecord) => this.attemptStatus(att),
    reason: (att: LoginAttemptRecord) => att.failureReason,
  };

  private readonly tabsMemo = optionsMemo<SMTTabItem<'info' | 'security' | 'orgUnits' | 'permissions'>[]>();

  attemptStatus(att: LoginAttemptRecord): string {
    return this.i18n.translate(att.isSuccess ? 'iam.users.detail.attempt_success' : 'iam.users.detail.attempt_failed');
  }

  terminateSession(session: UserSession): void {
    const viewingUser = this.viewingUser();
    if (!viewingUser) return;
    this.terminateSingleSession.emit({ sessionId: session.id, userId: viewingUser.id });
  }

  canLeave(): boolean | Observable<boolean> {
    return this.orgUnitsPanel()?.canLeave() ?? true;
  }

  /** The card's sections; org units and effective rights only with the right to see them. */
  viewTabs(user: User): SMTTabItem<'info' | 'security' | 'orgUnits' | 'permissions'>[] {
    const withId = !!this.safeRecordId()(user.id);
    const orgUnits = this.canViewOrgUnits() && withId;
    const permissions = this.canViewAssignments() && withId;
    return this.tabsMemo([this.tabText.currentLang(), orgUnits, permissions], () => [
      { value: 'info' as const, label: this.tabText.translate('iam.users.detail.general_tab'), icon: 'badge' },
      { value: 'security' as const, label: this.tabText.translate('iam.users.detail.security_tab'), icon: 'shield' },
      ...(orgUnits
        ? [{ value: 'orgUnits' as const, label: this.tabText.translate('iam.org_struktura'), icon: 'account_tree' }]
        : []),
      ...(permissions
        ? [
            {
              value: 'permissions' as const,
              label: this.tabText.translate('iam.users.detail.effective_permissions'),
              icon: 'lock_person',
            },
          ]
        : []),
    ]);
  }
}
