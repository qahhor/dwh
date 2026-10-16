import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { TranslatePipe } from '@core/services/i18n.service';

@Component({
  selector: 'app-login-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe],
  template: `
    <header class="login-header">
      <div class="brand-lockup" role="img" aria-label="SmartupCMS">
        <span class="brand-mark" aria-hidden="true">S</span>
        <span class="brand-name" aria-hidden="true">SmartupCMS</span>
      </div>
      <h1 class="login-title">{{ title() ?? ('auth.login.corporate_login' | t) }}</h1>
      @if (subtitle() !== '') {
        <p class="login-subtitle" [attr.id]="subtitleId()">
          {{ subtitle() ?? ('auth.login.platform_tagline' | t) }}
        </p>
      }
    </header>
  `,
  styleUrl: './login-header.component.css',
})
export class LoginHeaderComponent {
  readonly title = input<string>();
  readonly subtitle = input<string>();
  readonly subtitleId = input<string>();
}
