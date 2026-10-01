import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';

import { RouterModule } from '@angular/router';
import { AuthService } from './core/services/auth.service';
import { TranslatePipe } from './core/services/i18n.service';
import { UiToastContainerComponent } from './shared/ui/ui-toast.component';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslatePipe, RouterModule, UiToastContainerComponent],
  template: `
    @if (authService.isLoading()) {
      <div class="app-loader">
        <div class="loader-spinner"></div>
        <div class="loader-text">{{ 'app.startup.initializing' | t }}</div>
      </div>
    }

    @if (!authService.isLoading()) {
      <router-outlet></router-outlet>
    }
    <ui-toast-container></ui-toast-container>
  `,
  styles: [
    `
      .app-loader {
        height: 100vh;
        width: 100vw;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 16px;
        background-color: var(--bg-app);
        color: var(--text-muted);
        font-size: 13px;
      }

      .loader-spinner {
        width: 32px;
        height: 32px;
        border: 3px solid var(--border-color);
        border-top-color: var(--primary);
        border-radius: 50%;
        animation: spin 0.6s linear infinite;
      }

      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
      }
    `,
  ],
})
export class AppComponent implements OnInit {
  authService = inject(AuthService);

  ngOnInit() {
    this.authService.checkSession().subscribe();
  }
}
