import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subscription } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';
import { studioProblemIssues } from '../form-studio-errors';
import {
  initialSimpleForm,
  SIMPLE_FORM_MAX_BYTES,
  type SimpleFormDeclaration,
  type SimpleFormPackage,
} from './new-form.models';

/** The source declaration stays local until an explicit delivery action (ADR-0038). */
@Injectable()
export class NewFormStore {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);
  readonly delivery = signal<'cli' | 'live'>('cli');
  readonly locked = signal(false);
  readonly draft = signal(initialSimpleForm());
  readonly prepared = signal<SimpleFormPackage | null>(null);
  readonly busy = signal(false);
  readonly importing = signal(false);
  readonly error = signal('');
  readonly issues = signal<{ path: string; message: string }[]>([]);
  private readonly saved = signal(JSON.stringify(this.draft()));
  readonly dirty = computed(() => JSON.stringify(this.draft()) !== this.saved());
  private cliModule: SimpleFormDeclaration['module'] | null = null;
  private operation = 0;
  private request?: Subscription;

  chooseDelivery(mode: 'cli' | 'live'): void {
    if (this.locked() || mode === this.delivery()) return;
    const module =
      mode === 'live'
        ? {
            code: 'studio',
            title: {
              ru: this.i18n.getDictionary('ru')['studio.module.name'] ?? 'Studio',
              uz: 'Yaratilgan shakllar',
              en: 'Custom forms',
            },
          }
        : (this.cliModule ?? initialSimpleForm().module);
    if (mode === 'live') this.cliModule = structuredClone(this.draft().module);
    this.edit((draft) => {
      draft.module = structuredClone(module);
    });
    this.delivery.set(mode);
  }

  edit(update: (draft: SimpleFormDeclaration) => void): void {
    if (this.locked()) return;
    const next = structuredClone(this.draft());
    update(next);
    if (JSON.stringify(next) === JSON.stringify(this.draft())) return;
    this.cancel();
    this.clear();
    this.prepared.set(null);
    this.draft.set(next);
  }

  applySuggested(declaration: SimpleFormDeclaration): void {
    if (this.locked()) return;
    this.cancel();
    this.clear();
    this.prepared.set(null);
    this.delivery.set('cli');
    this.cliModule = null;
    this.draft.set(structuredClone(declaration));
  }

  prepare(): void {
    if (this.locked()) return;
    this.check(this.draft());
  }

  importText(text: string): void {
    if (this.locked()) return;
    if (new TextEncoder().encode(text).length > SIMPLE_FORM_MAX_BYTES) {
      this.invalidImport();
      return;
    }
    try {
      JSON.parse(text);
    } catch {
      this.invalidImport();
      return;
    }
    this.check(new Blob([text], { type: 'application/json' }), true);
  }

  invalidImport(): void {
    this.cancel();
    this.clear();
    this.prepared.set(null);
    this.error.set(this.i18n.translate('settings.simple_form.invalid_import'));
  }

  downloadFailed(): void {
    this.error.set(this.i18n.translate('settings.form_studio.export_failed'));
  }

  markDownloaded(): void {
    this.saved.set(JSON.stringify(this.draft()));
    this.clear();
  }

  private check(body: unknown, imported = false): void {
    this.cancel();
    const operation = this.operation;
    this.clear();
    this.prepared.set(null);
    this.busy.set(true);
    this.request = this.api
      .post<SimpleFormPackage | SimpleFormDeclaration>(
        imported ? '/form-studio/new-forms/check-draft' : '/form-studio/new-forms/prepare',
        body,
        { notifyError: false },
      )
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.busy.set(false);
        }),
      )
      .subscribe({
        next: (result) => {
          if (operation !== this.operation) return;
          const copy = structuredClone(result);
          if (imported) {
            this.delivery.set('cli');
            this.cliModule = null;
            this.draft.set(copy as SimpleFormDeclaration);
          } else {
            const prepared = copy as SimpleFormPackage;
            this.draft.set(prepared.declaration);
            this.prepared.set(prepared);
          }
        },
        error: (error) => {
          if (operation !== this.operation) return;
          this.error.set(problemText(error) || this.i18n.translate('settings.form_studio.validate_failed'));
          this.issues.set(studioProblemIssues(error));
        },
      });
  }

  private cancel(): void {
    ++this.operation;
    this.request?.unsubscribe();
    this.busy.set(false);
  }

  private clear(): void {
    this.error.set('');
    this.issues.set([]);
  }
}
