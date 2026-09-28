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
import { I18nService, TranslatePipe } from '../../../../core/services/i18n.service';
import { UiLocalTableComponent } from '../../../../shared/ui/ui-local-table.component';
import { UiRecordHistoryComponent } from '../../../../shared/ui/ui-record-history.component';
import { TableConfig } from '../../../../shared/ui-kit/components/table/table.types';
import { SMTDialogComponent, SMTDialogContentDirective } from '../../../../shared/ui-kit/components/modal';
import { SMTButtonComponent } from '../../../../shared/ui-kit/components/button';
import { LoginAttemptRecord, User, UserSecuritySummary, UserSession } from '../../../../core/models/auth.models';
import { UserOrgUnitsPanelComponent } from '../../org-units/public-api';
import { UserEffectivePermissionsPanelComponent } from './user-effective-permissions-panel.component';
import { SMTAvatarComponent } from '../../../../shared/ui-kit/components/avatar';
import { SMTTabBarComponent, SMTTabItem } from '../../../../shared/ui-kit/components/tab-bar';
import { optionsMemo } from '../../../../shared/ui-kit/components/forms/radio-group';

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
  template: `
    <!-- User View / Profile Modal -->
    <smt-dialog
      [open]="isOpen()"
      [smtTitle]="'iam.profil_polzovatelya' | t"
      [smtSize]="
        activeViewTab() === 'security' ||
        activeViewTab() === 'permissions' ||
        (canViewOrgUnits() && !!viewingUser() && safeRecordId()(viewingUser()!.id))
          ? 'xl'
          : 'sm'
      "
      (closed)="closeRecordView.emit()"
    >
      <ng-template smtDialogContent>
        @if (recordLoading()) {
          <div body role="status">{{ 'search.record_loading' | t }}</div>
        }
        @if (recordError()) {
          <div body role="alert">
            <p>{{ (recordNotFound() ? 'search.record_not_found' : 'search.record_load_error') | t }}</p>
            @if (!recordNotFound()) {
              <button smt-button type="button" smtVariant="secondary" (click)="retryRecordView.emit(routeRecordId())">
                {{ 'audit.retry' | t }}
              </button>
            }
          </div>
        }
        @if (viewingUser(); as u) {
          <div body class="view-body" [attr.data-record-id]="routeRecordId() || viewingUser()?.id || ''">
            @if (routeRecordId()) {
              <p>#{{ routeRecordId() }}</p>
            }
            @if (!safeRecordId()(u.id)) {
              <p role="status">{{ 'search.record_readonly_id' | t }}</p>
            }
            <div class="view-header-card">
              <smt-avatar [name]="u.name" smtSize="lg" />
              <div class="info">
                <h3 class="name">{{ u.name }}</h3>
                <span class="handle font-mono">&#64;{{ u.login }}</span>
              </div>
            </div>

            <!-- Segmented Tab Bar -->
            <smt-tab-bar
              class="modal-tab-bar"
              [tabs]="viewTabs(u)"
              [value]="activeViewTab()"
              [smtAriaLabel]="'iam.razdely_kartochki' | t"
              (valueChange)="$event && switchTab.emit({ tab: $event, userId: u.id })"
            />

            <!-- Info Tab -->
            @if (activeViewTab() === 'info') {
              <div class="info-list">
                <div class="info-row">
                  <span class="lbl">Email</span>
                  <span class="val font-mono">{{ u.email }}</span>
                </div>
                <div class="info-row">
                  <span class="lbl">{{ 'iam.telefon.822f9fd' | t }}</span>
                  <span class="val font-mono">{{ u.phone || '—' }}</span>
                </div>
                <div class="info-row">
                  <span class="lbl">{{ 'iam.rukovoditel' | t }}</span>
                  <span class="val">{{ getManagerName()(u) || '—' }}</span>
                </div>
                <div class="info-row">
                  <span class="lbl">{{ 'iam.roli' | t }}</span>
                  <span class="val">{{ getUserRoleNames()(u).join(', ') || '—' }}</span>
                </div>
                <div class="info-row">
                  <span class="lbl">{{ 'iam.2fa_zaschita' | t }}</span>
                  <span class="val">{{
                    (u.is2faEnabled ? 'common.enabled_feminine' : 'common.disabled_feminine') | t
                  }}</span>
                </div>
                <div class="info-row">
                  <span class="lbl">{{ 'common.status' | t }}</span>
                  <span class="val">{{
                    (u.state === 'A' ? 'common.active_masculine' : 'common.blocked_masculine') | t
                  }}</span>
                </div>
                <div class="info-row">
                  <span class="lbl">{{ 'iam.sozdan' | t }}</span>
                  <span class="val font-mono">{{ u.createdAt | date: 'dd.MM.yyyy' }}</span>
                </div>
              </div>
            }
            @if (activeViewTab() === 'info' && safeRecordId()(u.id)) {
              <ui-record-history class="user-history" kind="users" [recordId]="u.id" />
            }

            <!-- Security & Sessions Tab -->
            @if (activeViewTab() === 'security') {
              <div class="security-tab-content">
                @if (isLoadingSecurity()) {
                  <div class="security-loading">
                    <span class="material-symbols-outlined spin-icon" aria-hidden="true">sync</span>
                    <span>{{ 'common.loading' | t }}</span>
                  </div>
                }

                @if (!isLoadingSecurity() && userSecurity(); as sec) {
                  <div class="security-details">
                    <!-- Security Overview Cards -->
                    <div class="sec-metrics-grid">
                      <div class="sec-metric-card">
                        <span class="sec-metric-lbl">{{ 'iam.status_2fa' | t }}</span>
                        <span
                          class="sec-metric-badge"
                          [class.success]="sec.is2faEnabled"
                          [class.muted]="!sec.is2faEnabled"
                        >
                          <span class="material-symbols-outlined metric-icon" aria-hidden="true">{{
                            sec.is2faEnabled ? 'lock' : 'lock_open'
                          }}</span>
                          {{ (sec.is2faEnabled ? 'iam.vklyuchena' : 'iam.otklyuchena') | t }}
                        </span>
                      </div>
                      <div class="sec-metric-card">
                        <span class="sec-metric-lbl">{{ 'iam.trebovanie_smeny_parolya' | t }}</span>
                        <span
                          class="sec-metric-badge"
                          [class.warning]="sec.forcePasswordChange"
                          [class.success]="!sec.forcePasswordChange"
                        >
                          <span class="material-symbols-outlined metric-icon" aria-hidden="true">{{
                            sec.forcePasswordChange ? 'priority_high' : 'check'
                          }}</span>
                          {{ (sec.forcePasswordChange ? 'iam.trebuetsya' : 'iam.ne_trebuetsya') | t }}
                        </span>
                      </div>
                      <div class="sec-metric-card">
                        <span class="sec-metric-lbl">{{ 'iam.aktivnyh_sessiy' | t }}</span>
                        <span class="sec-metric-val">{{ sec.activeSessionsCount }}</span>
                      </div>
                      <div class="sec-metric-card">
                        <span class="sec-metric-lbl">{{ 'iam.versiya_bezopasnosti' | t }}</span>
                        <span class="sec-metric-val font-mono">v{{ sec.authVersion }}</span>
                      </div>
                    </div>

                    <!-- Quick Security Actions Toolbar -->
                    @if (canUpdateUser()) {
                      <div class="sec-actions-bar">
                        <button
                          type="button"
                          class="sec-action-btn warning"
                          [disabled]="isSecurityActionPending() || sec.forcePasswordChange"
                          (click)="forcePasswordChange.emit(u.id)"
                        >
                          <span class="material-symbols-outlined" aria-hidden="true">password</span>
                          <span>{{ 'iam.potrebovat_smenu_parolya' | t }}</span>
                        </button>

                        <button
                          type="button"
                          class="sec-action-btn danger"
                          [disabled]="isSecurityActionPending() || !sec.is2faEnabled"
                          (click)="reset2fa.emit(u.id)"
                        >
                          <span class="material-symbols-outlined" aria-hidden="true">key_off</span>
                          <span>{{ 'iam.sbrosit_2fa' | t }}</span>
                        </button>

                        <button
                          type="button"
                          class="sec-action-btn secondary"
                          [disabled]="isSecurityActionPending() || sec.activeSessionsCount === 0"
                          (click)="terminateAllSessions.emit(u.id)"
                        >
                          <span class="material-symbols-outlined" aria-hidden="true">logout</span>
                          <span>{{ 'iam.zavershit_vse_sessii' | t }}</span>
                        </button>
                      </div>
                    }

                    <!-- Active Sessions List -->
                    <div class="sec-section">
                      <div class="sec-section-title">
                        <span class="material-symbols-outlined sec-title-icon" aria-hidden="true">devices</span>
                        <h4>{{ 'iam.aktivnye_sessii' | t }}</h4>
                        <span class="count-pill">{{ sec.activeSessions.length }}</span>
                      </div>

                      @if (sec.activeSessions.length === 0) {
                        <div class="sec-empty-state">
                          <p>{{ 'iam.net_aktivnyh_sessiy' | t }}</p>
                        </div>
                      }

                      @if (sec.activeSessions.length > 0) {
                        <div class="sec-table-scroll">
                          <ui-local-table
                            data-testid="user-sessions-table"
                            [rows]="sessions()"
                            [config]="sessionsConfig()"
                            [sortValues]="sessionSortValues"
                          />
                        </div>
                      }
                    </div>

                    <!-- Recent Login Attempts History Section -->
                    <div class="sec-section">
                      <div class="sec-section-title">
                        <span class="material-symbols-outlined sec-title-icon" aria-hidden="true">history</span>
                        <h4>{{ 'iam.istoriya_popytok_vhoda' | t }}</h4>
                        <span class="count-pill">{{ sec.recentLoginAttempts.length }}</span>
                      </div>

                      @if (sec.recentLoginAttempts.length === 0) {
                        <div class="sec-empty-state">
                          <p>{{ 'iam.net_zapisan_popytok_vhoda' | t }}</p>
                        </div>
                      }

                      @if (sec.recentLoginAttempts.length > 0) {
                        <div class="sec-table-scroll">
                          <ui-local-table
                            data-testid="user-login-attempts-table"
                            [rows]="attempts()"
                            [config]="attemptsConfig()"
                            [sortValues]="attemptSortValues"
                          />
                        </div>
                      }
                    </div>
                  </div>
                }
              </div>
            }

            <!-- Org Units Tab (use [hidden] to keep directive in DOM for spec tests) -->
            <div [hidden]="activeViewTab() !== 'orgUnits'">
              @if (isOpen() && canViewOrgUnits() && safeRecordId()(u.id)) {
                <app-user-org-units-panel
                  [userId]="u.id"
                  (busyChange)="orgPanelBusy.emit($event)"
                ></app-user-org-units-panel>
              }
            </div>

            <!-- Effective Permissions Tab -->
            @if (activeViewTab() === 'permissions') {
              <div>
                @if (isOpen() && canViewAssignments() && safeRecordId()(u.id)) {
                  <app-user-effective-permissions-panel
                    [userId]="u.id"
                    [canAssign]="canAssignPermissions()"
                    [userRoleNames]="getUserRoleNames()(u)"
                  ></app-user-effective-permissions-panel>
                }
              </div>
            }
          </div>
        }
        <div footer>
          <button smt-button type="button" smtVariant="secondary" smtSize="md" (click)="closeRecordView.emit()">
            {{ (routeRecordId() ? 'search.back_to_list' : 'audit.zakryt') | t }}
          </button>
          @if (canUpdateUser() && viewingUser() && safeRecordId()(viewingUser()!.id)) {
            <button smt-button type="button" smtVariant="primary" smtSize="md" (click)="openEdit.emit()">
              {{ 'common.edit' | t }}
            </button>
          }
        </div>
      </ng-template>
    </smt-dialog>

    <ng-template #sessionIpCell let-s
      ><span class="font-mono text-xs">{{ s.ip }}</span></ng-template
    >
    <ng-template #sessionAgentCell let-s
      ><span class="text-xs text-truncate" [title]="s.userAgent">{{ s.userAgent || '—' }}</span></ng-template
    >
    <ng-template #sessionCreatedCell let-s
      ><span class="font-mono text-xs text-muted">{{ s.createdAt | date: 'dd.MM.yyyy HH:mm' }}</span></ng-template
    >
    <ng-template #sessionSeenCell let-s
      ><span class="font-mono text-xs text-muted">{{ s.lastSeenAt | date: 'dd.MM.yyyy HH:mm' }}</span></ng-template
    >
    <ng-template #sessionActionCell let-s>
      <div class="text-right">
        <button
          smt-button
          type="button"
          smtVariant="ghost"
          smtSize="sm"
          smtIconOnly
          smtIcon="close"
          class="danger"
          [title]="'iam.zavershit_sessiyu' | t"
          [attr.aria-label]="'iam.terminate_session_ip_named' | t: { ip: s.ip }"
          [disabled]="isSecurityActionPending()"
          (click)="terminateSession(s)"
        ></button>
      </div>
    </ng-template>
    <ng-template #attemptTimeCell let-att
      ><span class="font-mono text-xs text-muted">{{ att.attemptAt | date: 'dd.MM.yyyy HH:mm:ss' }}</span></ng-template
    >
    <ng-template #attemptIpCell let-att
      ><span class="font-mono text-xs">{{ att.ip }}</span></ng-template
    >
    <ng-template #attemptStatusCell let-att>
      <span class="status-indicator" [class.active]="att.isSuccess" [class.danger-dot]="!att.isSuccess">
        <span class="dot" aria-hidden="true"></span>
        {{ attemptStatus(att) }}
      </span>
    </ng-template>
    <ng-template #attemptReasonCell let-att
      ><span class="text-xs text-muted">{{ att.failureReason || '—' }}</span></ng-template
    >
  `,
  styleUrl: './user-detail-modal.component.css',
})
export class UserDetailModalComponent {
  /** Texts of the tabs below; translated again when the language changes. */
  private readonly tabText = inject(I18nService);

  private readonly i18n = inject(I18nService);

  readonly safeRecordId = input.required<(id: any) => boolean>();
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
      ariaLabel: this.i18n.translate('iam.aktivnye_sessii'),
      layout: 'fit',
      columns: {
        ip: { header: { type: 'primitive', value: 'IP' }, content: cell(this.sessionIpCell), width: '130px' },
        agent: { header: header('iam.ustroystvo_i_brauzer'), content: cell(this.sessionAgentCell) },
        created: { header: header('iam.sozdana'), content: cell(this.sessionCreatedCell), width: '140px' },
        seen: { header: header('iam.poslednyaya_aktivnost'), content: cell(this.sessionSeenCell), width: '160px' },
        action: {
          header: header('audit.deystvie'),
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
      ariaLabel: this.i18n.translate('iam.istoriya_popytok_vhoda'),
      layout: 'fit',
      columns: {
        time: { header: header('iam.vremya'), content: cell(this.attemptTimeCell), width: '160px' },
        ip: { header: { type: 'primitive', value: 'IP' }, content: cell(this.attemptIpCell), width: '130px' },
        status: { header: header('common.status'), content: cell(this.attemptStatusCell), width: '120px' },
        reason: { header: header('iam.prichina_otkaza'), content: cell(this.attemptReasonCell) },
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
    return this.i18n.translate(att.isSuccess ? 'iam.uspeshno' : 'iam.oshibka');
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
      { value: 'info' as const, label: this.tabText.translate('iam.osnovnoe'), icon: 'badge' },
      { value: 'security' as const, label: this.tabText.translate('iam.bezopasnost_i_sessii'), icon: 'shield' },
      ...(orgUnits
        ? [{ value: 'orgUnits' as const, label: this.tabText.translate('iam.org_struktura'), icon: 'account_tree' }]
        : []),
      ...(permissions
        ? [
            {
              value: 'permissions' as const,
              label: this.tabText.translate('iam.effektivnye_prava'),
              icon: 'lock_person',
            },
          ]
        : []),
    ]);
  }
}
