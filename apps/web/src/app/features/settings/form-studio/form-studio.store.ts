import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import type { FormMeta } from '@core/models/form-meta.models';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';
import { studioProblemIssues } from './form-studio-errors';
import { STUDIO_MAX_BYTES, type FormLayoutDeclaration, type FormStudioPreview } from './form-studio.models';

/** A local declaration draft; every preview and export passes the server's contract check. */
@Injectable()
export class FormStudioStore {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly draft = signal<FormLayoutDeclaration | null>(null);
  readonly validated = signal<FormStudioPreview | null>(null);
  readonly baseMeta = signal<FormMeta | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly issues = signal<{ path: string; message: string }[]>([]);
  private readonly saved = signal('');

  readonly dirty = computed(() => this.draft() !== null && JSON.stringify(this.draft()) !== this.saved());

  private operation = 0;

  load(): void {
    const operation = ++this.operation;
    this.loading.set(true);
    this.error.set('');
    this.issues.set([]);
    this.validated.set(null);
    this.api
      .get<FormStudioPreview>('/form-studio/prototype', undefined, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.loading.set(false);
        }),
      )
      .subscribe({
        next: (result) => {
          if (operation === this.operation) this.accept(result, true);
        },
        error: (error) => {
          if (operation === this.operation) this.fail(error, 'settings.form_studio.load_failed');
        },
      });
  }

  edit(update: (draft: FormLayoutDeclaration) => void): void {
    const current = this.draft();
    if (!current) return;
    const next = structuredClone(current);
    update(next);
    if (JSON.stringify(next) === JSON.stringify(current)) return;
    ++this.operation;
    this.busy.set(false);
    this.validated.set(null);
    this.error.set('');
    this.issues.set([]);
    this.draft.set(next);
  }

  validate(): void {
    const draft = this.draft();
    if (draft) this.check(draft, false);
  }

  importText(text: string): void {
    if (new TextEncoder().encode(text).length > STUDIO_MAX_BYTES) {
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
    ++this.operation;
    this.busy.set(false);
    this.validated.set(null);
    this.issues.set([]);
    this.error.set(this.i18n.translate('settings.form_studio.invalid_json'));
  }

  exportText(): string | null {
    const result = this.validated();
    if (!result) return null;
    return JSON.stringify(result.declaration, null, 2);
  }

  exportFailed(): void {
    this.error.set(this.i18n.translate('settings.form_studio.export_failed'));
  }

  markExported(): void {
    const result = this.validated();
    if (result) {
      this.saved.set(JSON.stringify(result.declaration));
      this.error.set('');
      this.issues.set([]);
    }
  }

  replaceWithSaved(result: FormStudioPreview): void {
    ++this.operation;
    this.busy.set(false);
    this.loading.set(false);
    this.error.set('');
    this.issues.set([]);
    this.accept(result, true);
  }

  private check(value: unknown, imported: boolean): void {
    const operation = ++this.operation;
    this.busy.set(true);
    this.error.set('');
    this.issues.set([]);
    this.validated.set(null);
    this.api
      .post<FormStudioPreview>('/form-studio/preview', value, { notifyError: false })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.busy.set(false);
        }),
      )
      .subscribe({
        next: (result) => {
          if (operation === this.operation) this.accept(result, imported);
        },
        error: (error) => {
          if (operation === this.operation) this.fail(error, 'settings.form_studio.validate_failed');
        },
      });
  }

  private accept(result: FormStudioPreview, saved: boolean): void {
    const copy = structuredClone(result);
    this.draft.set(copy.declaration);
    this.validated.set(copy);
    this.baseMeta.set(copy.meta);
    if (saved) this.saved.set(JSON.stringify(copy.declaration));
  }

  private fail(error: unknown, fallback: string): void {
    this.error.set(problemText(error) || this.i18n.translate(fallback));
    this.issues.set(studioProblemIssues(error));
  }
}
