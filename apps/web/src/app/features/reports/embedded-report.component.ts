import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';

import { ActivatedRoute } from '@angular/router';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { NavigationService } from '@core/services/navigation.service';
import { CustomNavigationItem } from '@core/models/navigation.models';
import { TranslatePipe, I18nService } from '@core/services/i18n.service';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTAlertComponent } from '@shared/ui-kit/components/alert';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';

@Component({
  selector: 'app-embedded-report',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTButtonComponent, SMTAlertComponent, UiPageHeaderComponent, TranslatePipe],
  templateUrl: './embedded-report.component.html',
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
