import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { AuthService } from './core/services/auth.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterModule],
  template: `
    <div *ngIf="authService.isLoading()" class="app-loader">
      <div class="loader-spinner"></div>
      <div class="loader-text">Инициализация SmartupCMS…</div>
    </div>

    <router-outlet *ngIf="!authService.isLoading()"></router-outlet>
  `,
  styles: [`
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
      to { transform: rotate(360deg); }
    }
  `]
})
export class AppComponent implements OnInit {
  constructor(public authService: AuthService) {}

  ngOnInit() {
    this.authService.checkSession().subscribe();
  }
}
