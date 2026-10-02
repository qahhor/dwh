import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  Signal,
  TemplateRef,
  computed,
  effect,
  inject,
  input,
  untracked,
  viewChild,
} from '@angular/core';
import { LoginAttemptRecord, UserSession } from '@core/models/auth.models';
import type { FormMeta } from '@core/models/form-meta.models';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
import type { EntityRecord } from '@shared/entity/entities.api';
import { UiLocalTableComponent } from '@shared/ui/ui-local-table.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { TableConfig } from '@shared/ui-kit/components/table/table.types';
import { UserSecurityService } from '../services/user-security.service';

/**
 * The tab "Sessions and security" of a user on the general record page (ADR-0032 7.2, `provideEntityOverrides`): the
 * second factor, the forced password change, the authentication generation, the open sessions and the recent sign-in
 * attempts, with closing one session or all of them for a holder of the right to block. Blocking, the second factor
 * and the forced password change are the record's actions in the page header (ADR-0032 8).
 */
@Component({
  selector: 'app-user-security-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, TranslatePipe, UiLocalTableComponent, SMTButtonComponent],
  templateUrl: './user-security-tab.component.html',
  styleUrl: './user-security-tab.component.css',
})
export class UserSecurityTabComponent {
  readonly security = inject(UserSecurityService);
  private readonly i18n = inject(I18nService);
  private readonly permissions = inject(PermissionService);

  readonly record = input.required<EntityRecord>();
  readonly meta = input<FormMeta | null>(null);

  private readonly sessionIpCell = viewChild.required<TemplateRef<unknown>>('sessionIpCell');
  private readonly sessionAgentCell = viewChild.required<TemplateRef<unknown>>('sessionAgentCell');
  private readonly sessionCreatedCell = viewChild.required<TemplateRef<unknown>>('sessionCreatedCell');
  private readonly sessionSeenCell = viewChild.required<TemplateRef<unknown>>('sessionSeenCell');
  private readonly sessionActionCell = viewChild.required<TemplateRef<unknown>>('sessionActionCell');
  private readonly attemptTimeCell = viewChild.required<TemplateRef<unknown>>('attemptTimeCell');
  private readonly attemptIpCell = viewChild.required<TemplateRef<unknown>>('attemptIpCell');
  private readonly attemptStatusCell = viewChild.required<TemplateRef<unknown>>('attemptStatusCell');
  private readonly attemptReasonCell = viewChild.required<TemplateRef<unknown>>('attemptReasonCell');

  readonly summary = computed(() => this.security.userSecurity());
  readonly sessions = computed(() => this.summary()?.activeSessions ?? []);
  readonly attempts = computed(() => this.summary()?.recentLoginAttempts ?? []);

  /** Closing sessions takes access away, as blocking does: the same right (ADR-0028). */
  readonly canCloseSessions = computed(() => this.permissions.hasPermission('md.users', 'block'));

  readonly sessionsConfig = computed<TableConfig<UserSession>>(() => {
    const header = (key: string) => ({ type: 'primitive' as const, value: this.i18n.translate(key) });
    const cell = (template: Signal<TemplateRef<unknown>>) => ({ type: 'templateRef' as const, value: template });
    return {
      trackBy: (_index, session) => session.id,
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
      trackBy: (_index, attempt) => attempt.id,
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

  /** The record on screen at its revision: an action of the header changes it, and the summary is read again. */
  private readonly shown = computed(() => `${this.record().id}:${this.record().revision ?? 0}`);

  /** The summary carries every open session, so a header click sorts them all. */
  readonly sessionSortValues = {
    ip: (session: UserSession) => session.ip,
    agent: (session: UserSession) => session.userAgent,
    created: (session: UserSession) => new Date(session.createdAt),
    seen: (session: UserSession) => new Date(session.lastSeenAt),
  };

  readonly attemptSortValues = {
    time: (attempt: LoginAttemptRecord) => new Date(attempt.attemptAt),
    ip: (attempt: LoginAttemptRecord) => attempt.ip,
    status: (attempt: LoginAttemptRecord) => this.attemptStatus(attempt),
    reason: (attempt: LoginAttemptRecord) => attempt.failureReason,
  };

  constructor() {
    effect(() => {
      this.shown();
      untracked(() => this.security.loadUserSecurity(this.record().id));
    });
  }

  attemptStatus(attempt: LoginAttemptRecord): string {
    return this.i18n.translate(
      attempt.isSuccess ? 'iam.users.detail.attempt_success' : 'iam.users.detail.attempt_failed',
    );
  }

  closeAll(): void {
    this.security.terminateUserSessions(this.record().id);
  }

  close(session: UserSession): void {
    this.security.terminateSingleSession(session.id, this.record().id);
  }
}
