import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';
import { User } from '../profile.models';

@Component({
  selector: 'app-profile-security-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  template: `
    <div class="card section-card">
      <div class="section-header">
        <div class="section-title-box">
          <span class="material-symbols-outlined section-icon" aria-hidden="true">security</span>
          <h4 class="section-title">{{ 'iam.profile.security.title' | t }}</h4>
        </div>
      </div>

      <div class="security-info-box">
        <div class="twofa-status-banner" [class.enabled]="user()?.is2faEnabled">
          <span class="material-symbols-outlined twofa-big-icon" aria-hidden="true">
            {{ user()?.is2faEnabled ? 'verified_user' : 'gpp_maybe' }}
          </span>
          <div class="twofa-status-text">
            <div class="twofa-status-title">
              {{ (user()?.is2faEnabled ? 'iam.two_factor_active' : 'iam.two_factor_disabled') | t }}
            </div>
            <div class="twofa-status-desc">
              {{
                user()?.is2faEnabled
                  ? ('iam.two_factor_active_description' | t)
                  : ('iam.two_factor_disabled_description' | t)
              }}
            </div>
          </div>
        </div>

        <div class="security-tips">
          <div class="tip-item">
            <span class="material-symbols-outlined tip-icon" aria-hidden="true">check_circle</span>
            <span>{{ 'iam.profile.security.brute_force_note' | t }}</span>
          </div>
          <div class="tip-item">
            <span class="material-symbols-outlined tip-icon" aria-hidden="true">check_circle</span>
            <span>{{ 'iam.profile.security.idle_timeout_note' | t }}</span>
          </div>
        </div>
      </div>
    </div>
  `,
  styleUrl: './profile-security-card.component.css',
})
export class ProfileSecurityCardComponent {
  readonly user = input<User | null>(null);
}
