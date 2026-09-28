import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { UiBadgeComponent } from '@shared/ui/ui-badge.component';
import { TranslatePipe } from '@core/services/i18n.service';
import { User } from '../profile.models';
import { SMTAvatarComponent } from '@shared/ui-kit/components/avatar';

@Component({
  selector: 'app-user-profile-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTAvatarComponent, TranslatePipe, UiBadgeComponent],
  template: `
    @if (user(); as user) {
      <div class="card user-card">
        <smt-avatar class="user-avatar-large" [name]="user.name" smtSize="xl" />
        <div class="user-details">
          <div class="user-title-row">
            <h3 class="user-fullname">{{ user.name }}</h3>
            <ui-badge [variant]="user.state === 'A' ? 'active' : 'passive'" [dot]="true">
              {{ (user.state === 'A' ? 'common.active_masculine' : 'common.blocked_masculine') | t }}
            </ui-badge>
            @if (user.is2faEnabled) {
              <ui-badge variant="active">
                <span class="material-symbols-outlined badge-icon" aria-hidden="true">verified_user</span>
                {{ 'iam.2fa_vklyuchena' | t }}
              </ui-badge>
            }
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
            @if (user.phone) {
              <div class="info-item">
                <span class="info-label">{{ 'iam.telefon' | t }}:</span>
                <span class="info-value font-mono">{{ user.phone }}</span>
              </div>
            }
            <div class="info-item">
              <span class="info-label">{{ 'iam.yazyk_zona' | t }}:</span>
              <span class="info-value">{{ user.language || 'ru' }} ({{ user.timezone || 'Asia/Tashkent' }})</span>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  styleUrl: './user-profile-card.component.css',
})
export class UserProfileCardComponent {
  readonly user = input<User | null>(null);
}
