import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, map, switchMap } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import type { ProblemDetail } from '@core/models/common.models';
import { I18nService } from '@core/services/i18n.service';
import { problemText } from '@shared/ui/problem-text';
import { studioProblemIssues } from './form-studio-errors';
import type { FormLayoutDeclaration, FormStudioPreview } from './form-studio.models';
import { studioDraftPath, type StudioDraftResponse, type StudioVersionPackage } from './form-studio-lifecycle.models';
import { FormStudioStore } from './form-studio.store';
import { FormStudioVersionsStore } from './form-studio-versions.store';

/** Server draft revisions are separate from the editor's local JSON file snapshot (ADR-0035). */
@Injectable()
export class FormStudioLifecycleStore {
  private readonly api = inject(ApiService);
  private readonly editor = inject(FormStudioStore);
  private readonly versions = inject(FormStudioVersionsStore);
  private readonly i18n = inject(I18nService);
  private readonly destroyRef = inject(DestroyRef);

  readonly revision = signal<number | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly sealing = signal(false);
  readonly conflict = signal(false);
  readonly error = signal('');
  readonly issues = signal<{ path: string; message: string }[]>([]);
  readonly message = signal('');
  private readonly baseline = signal<FormLayoutDeclaration | null>(null);
  private readonly staleDependencyRevision = signal<number | null>(null);
  readonly dependenciesChanged = computed(
    () => this.revision() !== null && this.revision() === this.staleDependencyRevision(),
  );
  readonly serverDirty = computed(
    () => this.baseline() !== null && JSON.stringify(this.editor.draft()) !== JSON.stringify(this.baseline()),
  );
  readonly busy = computed(() => this.loading() || this.saving() || this.sealing());
  readonly canSave = computed(
    () =>
      !!this.editor.draft() &&
      !this.busy() &&
      !this.editor.busy() &&
      !this.conflict() &&
      (this.revision() === null || this.serverDirty() || this.dependenciesChanged()),
  );
  readonly canSeal = computed(
    () =>
      this.revision() !== null &&
      !this.serverDirty() &&
      !this.dependenciesChanged() &&
      !this.conflict() &&
      !this.busy() &&
      !this.editor.busy(),
  );
  private operation = 0;

  load(): void {
    if (this.busy()) return;
    const operation = ++this.operation;
    this.loading.set(true);
    this.error.set('');
    this.message.set('');
    this.issues.set([]);
    this.api
      .get<FormStudioPreview>('/form-studio/prototype', undefined, { notifyError: false })
      .pipe(
        switchMap((prototype) =>
          this.api
            .get<StudioDraftResponse>(studioDraftPath(prototype.meta.code), undefined, { notifyError: false })
            .pipe(map((response) => ({ prototype, response }))),
        ),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.loading.set(false);
        }),
      )
      .subscribe({
        next: ({ prototype, response }) => {
          if (operation !== this.operation) return;
          const preview: FormStudioPreview = {
            meta: response.meta,
            declaration: response.draft?.declaration ?? {
              ...prototype.declaration,
              entityCode: response.meta.code,
              sections: response.meta.layout.map((section) => ({
                id: section.key,
                fields: section.fields.map((fieldId) => ({ fieldId })),
              })),
            },
          };
          this.revision.set(response.draft?.revision ?? null);
          this.baseline.set(structuredClone(preview.declaration));
          this.editor.replaceWithSaved(preview);
          this.conflict.set(false);
          this.versions.load();
        },
        error: (error) => {
          if (operation === this.operation) this.fail(error, 'settings.form_studio.load_failed');
        },
      });
  }

  save(): void {
    if (!this.canSave()) return;
    const declaration = structuredClone(this.editor.draft()!);
    const requested = JSON.stringify(declaration);
    const revision = this.revision();
    const path = studioDraftPath(declaration.entityCode);
    const operation = ++this.operation;
    this.saving.set(true);
    this.error.set('');
    this.message.set('');
    this.issues.set([]);
    const request =
      revision === null
        ? this.api.post<StudioDraftResponse>(path, declaration, { notifyError: false })
        : this.api.put<StudioDraftResponse>(path, declaration, { notifyError: false, ifMatch: revision });
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.saving.set(false);
        }),
      )
      .subscribe({
        next: (response) => {
          if (operation !== this.operation) return;
          if (!response.draft) {
            this.fail(null, 'settings.form_studio.draft_failed');
            return;
          }
          this.staleDependencyRevision.set(null);
          this.revision.set(response.draft.revision);
          this.baseline.set(structuredClone(response.draft.declaration));
          this.conflict.set(false);
          if (JSON.stringify(this.editor.draft()) === requested) {
            this.editor.replaceWithSaved({ declaration: response.draft.declaration, meta: response.meta });
          }
          this.message.set(this.i18n.translate('settings.form_studio.draft_saved'));
        },
        error: (error) => {
          if (operation === this.operation) this.fail(error, 'settings.form_studio.draft_failed');
        },
      });
  }

  seal(): void {
    if (!this.canSeal()) return;
    const path = studioDraftPath(this.editor.baseMeta()!.code);
    const operation = ++this.operation;
    this.sealing.set(true);
    this.error.set('');
    this.message.set('');
    this.issues.set([]);
    this.api
      .post<StudioVersionPackage>(path + '/versions', undefined, { notifyError: false, ifMatch: this.revision() })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (operation === this.operation) this.sealing.set(false);
        }),
      )
      .subscribe({
        next: (result) => {
          if (operation === this.operation) this.versions.acceptPrepared(result);
        },
        error: (error) => {
          if (operation === this.operation) this.fail(error, 'settings.form_studio.seal_failed');
        },
      });
  }

  private fail(error: unknown, fallback: string): void {
    const problem = typeof error === 'object' && error !== null ? (error as Partial<ProblemDetail>) : {};
    if (problem.status === 409 && problem.messageKey === 'error.md.form_studio_dependencies_changed') {
      this.staleDependencyRevision.set(this.revision());
      this.conflict.set(false);
    } else if (problem.status === 409 || problem.status === 428) {
      this.conflict.set(true);
    }
    this.error.set(problemText(error) || this.i18n.translate(fallback));
    this.issues.set(studioProblemIssues(error));
  }
}
