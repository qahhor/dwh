import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Subscription } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import { I18nService } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';
import { studioProblemIssues } from '../form-studio-errors';
import {
  editorDeclaration,
  FIELD_FORMULA_KEYS,
  initialSimpleForm,
  LIVE_FIELD_TYPES,
  SIMPLE_FORM_MAX_BYTES,
  withFormat,
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
      if (mode === 'cli') toDeliverable(draft);
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
    this.check(withFormat(this.draft()));
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
            this.draft.set(editorDeclaration(copy as SimpleFormDeclaration));
          } else {
            const prepared = copy as SimpleFormPackage;
            this.draft.set(editorDeclaration(prepared.declaration));
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

/**
 * A declaration the developer CLI delivers: format 1 knows no data scope, layout or title field and no select,
 * reference or user field (ADR-0041), so they become plain text fields of the same key and labels in one section;
 * the formulas and rules of format 5 (ADR-0042) are live-form only and are left out.
 */
function toDeliverable(draft: SimpleFormDeclaration): void {
  delete draft.entity.scope;
  delete draft.entity.titleField;
  delete draft.layout;
  delete draft.rules;
  delete draft.lines;
  delete draft.workflow;
  delete draft.entity.numbering;
  draft.formatVersion = 1;
  draft.fields = draft.fields.map((source) => {
    const field = { ...source };
    FIELD_FORMULA_KEYS.forEach((key) => delete field[key]);
    if (!LIVE_FIELD_TYPES.includes(field.type)) return field;
    const next = { ...field, type: 'text' as const, maxLength: 255 };
    delete next.options;
    delete next.target;
    delete next.defaultValue;
    return next;
  });
}
