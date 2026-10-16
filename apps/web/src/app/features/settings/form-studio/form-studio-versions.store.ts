import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import type { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';
import { downloadStudioJson } from './form-studio-files';
import {
  studioDraftPath,
  STUDIO_PACKAGE_MAX_BYTES,
  type StudioPackageVerification,
  type StudioVersionPackage,
  type StudioVersionSummary,
} from './form-studio-lifecycle.models';
import { FormStudioStore } from './form-studio.store';

/** Immutable artifacts are read and checked independently of the mutable editor (ADR-0035). */
@Injectable()
export class FormStudioVersionsStore {
  private readonly api = inject(ApiService);
  private readonly editor = inject(FormStudioStore);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly items = signal<StudioVersionSummary[]>([]);
  readonly selected = signal<StudioVersionPackage | null>(null);
  readonly verified = signal<StudioPackageVerification | null>(null);
  readonly loading = signal(false);
  readonly selecting = signal(false);
  readonly verifying = signal(false);
  readonly error = signal('');
  readonly artifactError = signal('');
  readonly verificationError = signal('');
  readonly hasMore = signal(false);
  private readonly reading = signal(false);
  private readonly cursor = signal<string | null>(null);
  readonly busy = computed(() => this.selecting() || this.verifying() || this.reading());
  private listOperation = 0;
  private artifactOperation = 0;
  private verificationOperation = 0;
  private fileOperation = 0;

  load(more = false): void {
    const path = this.path();
    if (!path || (more && (!this.hasMore() || this.loading()))) return;
    const operation = ++this.listOperation;
    this.loading.set(true);
    this.error.set('');
    if (!more) {
      this.items.set([]);
      this.cursor.set(null);
      this.hasMore.set(false);
    }
    this.api
      .get<KeysetPage<StudioVersionSummary>>(
        path + '/versions',
        { limit: 20, ...(more ? { cursor: this.cursor() } : {}) },
        { notifyError: false },
      )
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.listOperation) this.loading.set(false);
        }),
      )
      .subscribe({
        next: (page) => {
          if (operation !== this.listOperation) return;
          this.items.set([
            ...new Map([...(more ? this.items() : []), ...page.items].map((item) => [item.version, item])).values(),
          ]);
          this.cursor.set(page.nextCursor);
          this.hasMore.set(page.hasMore);
        },
        error: (error) => {
          if (operation === this.listOperation)
            this.error.set(this.problem(error, 'settings.form_studio.versions_failed'));
        },
      });
  }

  select(version: number): void {
    this.readVersion(version, false);
  }

  downloadSelected(): void {
    const version = this.selected()?.version;
    if (version !== undefined) this.readVersion(version, true);
  }

  acceptPrepared(result: StudioVersionPackage): void {
    ++this.artifactOperation;
    this.selecting.set(false);
    this.selected.set(structuredClone(result));
    this.artifactError.set('');
    this.load();
  }

  async verifyFile(file: File): Promise<void> {
    const operation = ++this.fileOperation;
    ++this.verificationOperation;
    this.reading.set(false);
    this.verifying.set(false);
    this.verified.set(null);
    this.verificationError.set('');
    if (file.size > STUDIO_PACKAGE_MAX_BYTES) {
      this.invalidPackage();
      return;
    }
    this.reading.set(true);
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
      if (operation === this.fileOperation && !this.destroyRef.destroyed) this.verifyText(text);
    } catch {
      if (operation === this.fileOperation && !this.destroyRef.destroyed) this.invalidPackage();
    } finally {
      if (operation === this.fileOperation) this.reading.set(false);
    }
  }

  verifyText(text: string): void {
    ++this.fileOperation;
    this.reading.set(false);
    const path = this.path();
    if (!path) return;
    if (new TextEncoder().encode(text).length > STUDIO_PACKAGE_MAX_BYTES) {
      this.invalidPackage();
      return;
    }
    let version: unknown;
    try {
      version = (JSON.parse(text) as { version?: unknown } | null)?.version;
    } catch {
      this.invalidPackage();
      return;
    }
    if (typeof version !== 'number' || !Number.isSafeInteger(version) || version < 1) {
      this.invalidPackage();
      return;
    }
    const operation = ++this.verificationOperation;
    this.verifying.set(true);
    this.verified.set(null);
    this.verificationError.set('');
    this.api
      .post<StudioPackageVerification>(
        `${path}/versions/${version}/verify`,
        new Blob([text], { type: 'application/json' }),
        { notifyError: false },
      )
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.verificationOperation) this.verifying.set(false);
        }),
      )
      .subscribe({
        next: (result) => {
          if (operation === this.verificationOperation) this.verified.set(result);
        },
        error: (error) => {
          if (operation === this.verificationOperation)
            this.verificationError.set(this.problem(error, 'settings.form_studio.package_failed'));
        },
      });
  }

  private readVersion(version: number, download: boolean): void {
    const path = this.path();
    if (!path || !Number.isSafeInteger(version) || version < 1) return;
    const operation = ++this.artifactOperation;
    this.selecting.set(true);
    this.artifactError.set('');
    if (!download) this.selected.set(null);
    this.api
      .get<StudioVersionPackage>(`${path}/versions/${version}`, undefined, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.artifactOperation) this.selecting.set(false);
        }),
      )
      .subscribe({
        next: (result) => {
          if (operation !== this.artifactOperation) return;
          this.selected.set(structuredClone(result));
          if (download) {
            try {
              downloadStudioJson(JSON.stringify(result, null, 2), `smartupcms-form-layout-v${result.version}.json`);
            } catch {
              this.artifactError.set(this.i18n.translate('settings.form_studio.export_failed'));
            }
          }
        },
        error: (error) => {
          if (operation === this.artifactOperation)
            this.artifactError.set(this.problem(error, 'settings.form_studio.version_failed'));
        },
      });
  }

  private invalidPackage(): void {
    ++this.verificationOperation;
    this.verifying.set(false);
    this.verified.set(null);
    this.verificationError.set(this.i18n.translate('settings.form_studio.package_invalid'));
  }

  private path(): string | null {
    const code = this.editor.baseMeta()?.code;
    return code ? studioDraftPath(code) : null;
  }

  private problem(error: unknown, fallback: string): string {
    return problemText(error) || this.i18n.translate(fallback);
  }
}
