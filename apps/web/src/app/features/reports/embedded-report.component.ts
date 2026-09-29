import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';

import { ActivatedRoute, RouterModule } from '@angular/router';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { NavigationService } from '@core/services/navigation.service';
import { CustomNavigationItem } from '@core/models/navigation.models';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';

@Component({
  selector: 'app-embedded-report',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, RouterModule, TranslatePipe],
  template: `
    <div class="embedded-report-container" [class.fullscreen]="isFullscreen()">
      <!-- Top Toolbar -->
      <div class="report-toolbar">
        <div class="report-meta">
          <div class="report-icon-box">
            <span class="material-symbols-outlined report-icon" aria-hidden="true">{{
              report()?.icon || 'analytics'
            }}</span>
          </div>
          <div class="report-title-group">
            <h1 class="report-title">{{ report()?.title || ('reports.loading' | t) }}</h1>
            <div class="report-meta-sub">
              @if (urlHost()) {
                <span class="report-badge">{{ urlHost() }}</span>
              }
              <span class="status-indicator" [class.loading]="isLoading()" [class.ready]="!isLoading()">
                <span class="status-dot"></span>
                <span class="status-text">{{
                  isLoading() ? ('common.loading' | t) : ('nav.settings.stat_active' | t)
                }}</span>
              </span>
            </div>
          </div>
        </div>

        <div class="report-actions">
          <button
            type="button"
            class="action-btn"
            (click)="reloadIframe()"
            [title]="'common.refresh' | t"
            [attr.aria-label]="'common.refresh' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">refresh</span>
            <span class="action-label">{{ 'common.refresh' | t }}</span>
          </button>

          <button
            type="button"
            class="action-btn"
            (click)="toggleFullscreen()"
            [title]="isFullscreen() ? ('reports.exit_fullscreen' | t) : ('reports.fullscreen' | t)"
            [attr.aria-label]="isFullscreen() ? ('reports.exit_fullscreen' | t) : ('reports.fullscreen' | t)"
          >
            <span class="material-symbols-outlined" aria-hidden="true">{{
              isFullscreen() ? 'fullscreen_exit' : 'fullscreen'
            }}</span>
            <span class="action-label">{{
              isFullscreen() ? ('reports.exit_fullscreen' | t) : ('reports.fullscreen' | t)
            }}</span>
          </button>

          <button
            type="button"
            class="action-btn"
            (click)="openPopup()"
            [title]="'reports.open_popup' | t"
            [attr.aria-label]="'reports.open_popup' | t"
          >
            <span class="material-symbols-outlined" aria-hidden="true">open_in_browser</span>
            <span class="action-label">{{ 'reports.open_popup' | t }}</span>
          </button>

          @if (report()?.url) {
            <a
              [href]="report()?.url"
              target="_blank"
              rel="noopener noreferrer"
              class="action-btn primary"
              [title]="'reports.open_external' | t"
              [attr.aria-label]="'reports.open_external' | t"
            >
              <span class="material-symbols-outlined" aria-hidden="true">open_in_new</span>
              <span class="action-label">{{ 'reports.open_external' | t }}</span>
            </a>
          }
        </div>
      </div>

      <!-- Content Area -->
      <div class="report-viewport">
        <!-- Loading Overlay -->
        @if (isLoading()) {
          <div class="report-loading">
            <div class="spinner" aria-hidden="true"></div>
            <span class="loading-label">{{ 'common.loading' | t }}</span>
          </div>
        }

        <!-- Error Alert -->
        @if (errorMessage()) {
          <div class="report-error" role="alert">
            <span class="material-symbols-outlined icon" aria-hidden="true">error</span>
            <span>{{ errorMessage() }}</span>
            <button smt-button smtVariant="secondary" type="button" (click)="loadReport()">
              {{ 'common.retry' | t }}
            </button>
          </div>
        }

        <!-- Safe iFrame -->
        @if (safeUrl() && !errorMessage()) {
          <iframe
            #reportFrame
            [src]="safeUrl()"
            class="report-iframe"
            sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-downloads"
            referrerpolicy="no-referrer-when-downgrade"
            (load)="onIframeLoaded()"
            [attr.title]="report()?.title || ('reports.embedded_report' | t)"
          ></iframe>
        }

        <!-- Helpful Assistant Footer -->
        @if (!isLoading() && showTip()) {
          <div class="report-fallback-bar">
            <div class="fallback-tip">
              <span class="material-symbols-outlined tip-icon" aria-hidden="true">lightbulb</span>
              <span>{{ 'reports.iframe_blocked_hint' | t }}</span>
            </div>
            <div class="fallback-actions">
              <button type="button" class="fallback-btn" (click)="openPopup()">
                <span class="material-symbols-outlined" aria-hidden="true">open_in_browser</span>
                {{ 'reports.open_popup' | t }}
              </button>
              <button
                type="button"
                class="fallback-close"
                (click)="showTip.set(false)"
                [attr.aria-label]="'common.close' | t"
              >
                <span class="material-symbols-outlined" aria-hidden="true">close</span>
              </button>
            </div>
          </div>
        }
      </div>
    </div>
  `,
  styleUrl: './embedded-report.component.css',
})
export class EmbeddedReportComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly navService = inject(NavigationService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly i18n = inject(I18nService);

  /** A failed read keeps the report that was on screen. */
  readonly report = linkedSignal<CustomNavigationItem | undefined, CustomNavigationItem | null>({
    source: () => (this.item.hasValue() ? this.item.value() : undefined),
    computation: (item, previous) => item ?? previous?.value ?? null,
  });
  /** Emptied for a moment by reloadIframe, so the frame is created again. */
  readonly safeUrl = linkedSignal<CustomNavigationItem | null, SafeResourceUrl | null>({
    source: () => this.report(),
    computation: (item) => (item ? this.sanitizer.bypassSecurityTrustResourceUrl(item.url) : null),
  });
  /** Busy while the report is read and then while its frame loads; a failed read is not busy. */
  readonly isLoading = linkedSignal(() => this.item.status() !== 'error');
  readonly isFullscreen = signal<boolean>(false);
  readonly showTip = signal<boolean>(true);

  readonly errorMessage = computed(() => (this.item.error() ? this.i18n.translate('reports.load_error') : null));
  readonly urlHost = computed(() => {
    const item = this.report();
    if (!item) return '';
    try {
      return new URL(item.url, window.location.origin).hostname;
    } catch {
      return '';
    }
  });

  /** The report code of the route; another code loads another report. */
  private readonly code = toSignal(this.route.paramMap.pipe(map((params) => params.get('code') || undefined)));
  private readonly item = rxResource({
    params: this.code,
    stream: ({ params }) => this.navService.getItemByCode(params),
  });

  loadReport(): void {
    this.item.reload();
  }

  onIframeLoaded(): void {
    this.isLoading.set(false);
  }

  reloadIframe(): void {
    const current = this.report();
    if (!current) return;
    this.isLoading.set(true);
    this.safeUrl.set(null);
    setTimeout(() => {
      this.safeUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(current.url));
    }, 100);
  }

  toggleFullscreen(): void {
    this.isFullscreen.update((v) => !v);
  }

  openPopup(): void {
    const current = this.report();
    if (!current?.url) return;
    const w = Math.min(window.screen.width * 0.9, 1280);
    const h = Math.min(window.screen.height * 0.85, 800);
    const left = Math.max(0, (window.screen.width - w) / 2);
    const top = Math.max(0, (window.screen.height - h) / 2);
    window.open(
      current.url,
      '_blank',
      `width=${w},height=${h},top=${top},left=${left},menubar=no,toolbar=no,location=no,status=no,resizable=yes,scrollbars=yes`,
    );
  }
}
