import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { UiBadgeComponent } from '../../../../shared/ui/ui-badge.component';
import { TranslatePipe } from '../../../../core/services/i18n.service';
import { User } from '../profile.models';
import { SMTAvatarComponent } from '../../../../shared/ui-kit/components/avatar';

@Component({
  selector: 'app-user-profile-card',
  standalone: true,
  imports: [
    SMTAvatarComponent, CommonModule,
    TranslatePipe,
    UiBadgeComponent
  ],
  template: `
    <div class="card user-card" *ngIf="user">
      <smt-avatar class="user-avatar-large" [name]="user.name" smtSize="xl" />
      <div class="user-details">
        <div class="user-title-row">
          <h3 class="user-fullname">{{ user.name }}</h3>
          <ui-badge [variant]="user.state === 'A' ? 'active' : 'passive'" [dot]="true">
            {{ (user.state === 'A' ? 'common.active_masculine' : 'common.blocked_masculine') | t }}
          </ui-badge>
          <ui-badge *ngIf="user.is2faEnabled" variant="active">
            <span class="material-symbols-outlined badge-icon" aria-hidden="true">verified_user</span> {{ 'iam.2fa_vklyuchena' | t }}
          </ui-badge>
        </div>
        <div class="user-info-grid">
          <div class="info-item">
            <span class="info-label">{{ 'iam.login' | t }}:</span>
            <span class="info-value font-mono">&#64;{{ user.login }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ 'iam.email' | t }}:</span>
            <span class="info-value font-mono">{{ user.email }}</span>
          </div>
          <div class="info-item" *ngIf="user.phone">
            <span class="info-label">{{ 'iam.telefon' | t }}:</span>
            <span class="info-value font-mono">{{ user.phone }}</span>
          </div>
          <div class="info-item">
            <span class="info-label">{{ 'iam.yazyk_zona' | t }}:</span>
            <span class="info-value">{{ user.language || 'ru' }} ({{ user.timezone || 'Asia/Tashkent' }})</span>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      min-width: 0;
    }

    .card {
      background-color: var(--bg-surface);
      border: 1px solid var(--border-color);
      border-radius: var(--radius-lg);
      padding: 18px 22px;
    }

    .user-card {
      display: flex;
      align-items: center;
      gap: 20px;
    }

    .user-avatar-large {
      --smt-avatar-size: 64px;
    }

    .user-details {
      flex: 1;
      min-width: 0;
    }

    .user-title-row {
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 8px;
      flex-wrap: wrap;
    }

    .user-fullname {
      font-size: 18px;
      font-weight: 600;
      color: var(--text-main);
      margin: 0;
    }

    .badge-icon {
      font-size: 14px;
      vertical-align: middle;
    }

    .user-info-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr));
      gap: 10px;
      font-size: 13px;
    }

    .info-item {
      display: flex;
      align-items: center;
      gap: 6px;
      min-width: 0;
    }

    .info-label {
      color: var(--text-muted);
      flex-shrink: 0;
    }

    .info-value {
      color: var(--text-main);
      font-weight: 500;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .font-mono {
      font-family: monospace;
    }

    @media (max-width: 640px) {
      .card {
        padding: 14px;
      }
      .user-card {
        align-items: flex-start;
        gap: 14px;
      }
      .user-avatar-large {
        --smt-avatar-size: 48px;
      }
      .user-title-row {
        flex-wrap: wrap;
      }
    }
  `]
})
export class UserProfileCardComponent {
  @Input() user: User | null = null;
}
