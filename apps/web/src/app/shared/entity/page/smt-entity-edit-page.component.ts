import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { finalize, map, of } from 'rxjs';
import type { ProblemDetail } from '@core/models/common.models';
import type { FormProblems } from '@core/models/form-meta.models';
import {
  canDo,
  formProblems,
  lockedKeys,
  recordPayload,
  recordValues,
  rowsOf,
  serverProblems,
  withLocks,
} from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { EntitiesApi, EntityRecord } from '../entities.api';
import { SMTEntityFormComponent } from '../smt-entity-form.component';
import { SMTEntityLinesComponent } from '../smt-entity-lines.component';
import { isNotFound, recordIdOf, recordName } from './entity-page';
import { SMTEntityPageStateComponent } from './smt-entity-page-state.component';
import { EntityPageContext } from './smt-entity-page.component';

/**
 * Creating a record, `/e/:code/new`, or changing one, `/e/:code/:id/edit` (ADR-0032 7.1): the entity's form from
 * `form-meta`, checked by its declared rules before the request; the change names the revision it was read at
 * (If-Match). A 422 puts the server's problems under the fields; a stale revision (409) or a missing one (428) is
 * the shared conflict message with a button that reads the record again. A document's rows are edited under the form
 * and saved with it (ADR-0032 9.1); the fields and rows the state of its process locks are read-only (ADR-0032 9.2).
 */
@Component({
  selector: 'smt-entity-edit-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    SMTButtonComponent,
    SMTEntityFormComponent,
    SMTEntityLinesComponent,
    SMTEntityPageStateComponent,
    TranslatePipe,
    UiPageHeaderComponent,
  ],
  host: { class: 'smt-entity-edit-page' },
  template: `
    @switch (state()) {
      @case ('missing') {
        <smt-entity-page-state kind="record" [back]="context.listLink()" />
      }
      @case ('denied') {
        <smt-entity-page-state kind="denied" [back]="context.listLink()" />
      }
      @case ('failed') {
        <smt-entity-page-state kind="failed" [back]="context.listLink()" (retry)="loaded.reload()" />
      }
      @case ('ready') {
        <ui-page-header [title]="heading()" [eyebrow]="context.title()" />
        <form ngNoForm class="entity-edit" novalidate (submit)="$event.preventDefault(); save()">
          <smt-entity-form
            [meta]="meta()"
            [recordId]="record()?.id ?? null"
            [controls]="context.overrides().fields ?? {}"
            [problems]="problems()"
            [disabled]="saving()"
            [(value)]="values"
          />
          @for (collection of meta().collections ?? []; track collection.key) {
            <smt-entity-lines
              [collection]="collection"
              [values]="values()"
              [problems]="problems()"
              [disabled]="saving()"
              [readonly]="locked().has(collection.key)"
              [rows]="rowsOf(values(), collection.key)"
              (rowsChange)="setRows(collection.key, $event)"
            />
          }
          <div class="entity-edit-actions">
            <a smt-button smtVariant="secondary" [routerLink]="backLink()" data-testid="entity-cancel">
              {{ 'common.cancel' | t }}
            </a>
            <button smt-button type="submit" smtVariant="primary" [smtLoading]="saving()" data-testid="entity-save">
              {{ 'common.save' | t }}
            </button>
          </div>
        </form>
      }
      @default {
        <p class="sr-only" role="status">{{ 'common.loading' | t }}</p>
      }
    }
  `,
  styles: [
    `
      :host {
        display: flex;
        flex-direction: column;
        gap: 16px;
        min-width: 0;
      }
      .entity-edit {
        display: flex;
        flex-direction: column;
        gap: 20px;
        max-width: 960px;
      }
      .entity-edit-actions {
        display: flex;
        justify-content: flex-end;
        gap: 8px;
      }
    `,
  ],
})
export class SMTEntityEditPageComponent {
  readonly context = inject(EntityPageContext);
  private readonly entities = inject(EntitiesApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  /** The record as last read: the loaded one, or the one read again after a save was refused over a newer revision. */
  readonly record = linkedSignal<EntityRecord | null>(() => (this.loaded.hasValue() ? this.loaded.value() : null));
  /** The fields by key, starting from the record, or from the defaults of a new one. */
  readonly values = linkedSignal(() => recordValues(this.meta(), this.record() ? { ...this.record() } : null));
  readonly problems = signal<FormProblems>({});
  readonly saving = signal(false);

  /** The form, with the fields the state of the record's process locks read-only. */
  readonly meta = computed(() => withLocks(this.context.formMeta(), this.record()));
  /** The fields and collections the state of the record locks; none while it is new. */
  readonly locked = computed(() => lockedKeys(this.context.formMeta(), this.record()));
  readonly recordId = computed(() => recordIdOf(this.id()));
  readonly creating = computed(() => this.id() === undefined);

  readonly state = computed<'loading' | 'ready' | 'missing' | 'denied' | 'failed'>(() => {
    if (this.creating()) return canDo(this.meta(), 'create') ? 'ready' : 'denied';
    if (this.recordId() === null) return 'missing';
    const status = this.loaded.status();
    if (status === 'error') return isNotFound(this.loaded.error()) ? 'missing' : 'failed';
    if (status !== 'resolved' && status !== 'local') return 'loading';
    const record = this.record();
    if (!record) return 'missing';
    return (record.actions ?? []).includes('update') ? 'ready' : 'denied';
  });

  readonly heading = computed(() => {
    this.i18n.currentLang();
    const record = this.record();
    if (!record) return this.i18n.translate('ui.entity_page.create_title');
    const name = recordName(this.meta(), record, (id) => this.i18n.translate('ui.entity_page.record', { id }));
    return this.i18n.translate('ui.entity_page.edit_title', { name });
  });

  readonly backLink = computed(() => {
    const record = this.record();
    return record ? `${this.context.listLink()}/${record.id}` : this.context.listLink();
  });

  /** The record's id from the route; none on `/new`. */
  readonly id = toSignal(inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id') ?? undefined)), {
    initialValue: undefined,
  });

  /** The record as read: none while creating. */
  readonly loaded = rxResource({
    params: () => ({ id: this.recordId(), creating: this.creating() }),
    stream: ({ params }) =>
      params.creating || params.id === null ? of(null) : this.entities.get(this.context.code(), params.id),
  });

  /** The rows of a collection on the form, for the template. */
  readonly rowsOf = rowsOf;

  /** Checked by the entity's declared rules first; the server checks them again and names the fields it rejects. */
  save(): void {
    if (this.saving()) return;
    const meta = this.meta();
    const record = this.record();
    const translate = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    this.problems.set(formProblems(meta, this.values(), translate, !record));
    if (Object.keys(this.problems()).length > 0) return;

    const code = this.context.code();
    const payload = recordPayload(meta, this.values(), record ? { ...record } : null);
    this.saving.set(true);
    const request = record
      ? this.entities.patch(code, record.id, payload, record.revision)
      : this.entities.create(code, payload);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.toast.success(translate(record ? 'ui.entity_page.saved' : 'ui.entity_page.created'));
        void this.router.navigate([this.context.listLink(), saved.id]);
      },
      error: (problem: ProblemDetail) => {
        this.problems.set(serverProblems(meta, problem?.errors, translate));
        if (Object.keys(this.problems()).length > 0) return;
        this.saveErrors.show(problem, {
          fallbackKey: record ? 'ui.entity_page.save_failed' : 'ui.entity_page.create_failed',
          reload: record ? () => this.reload(record.id) : undefined,
        });
      },
    });
  }

  /** The rows of a collection as the lines edit them. */
  setRows(key: string, rows: unknown[]): void {
    this.values.update((values) => ({ ...values, [key]: rows }));
  }

  /** Reads the record again after a save was refused over a newer revision: the form shows what is saved now. */
  reload(id: number): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.entities
      .get(this.context.code(), id)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (fresh) => {
          this.problems.set({});
          this.record.set(fresh);
        },
        error: (problem: unknown) => this.saveErrors.show(problem, { fallbackKey: 'ui.entity_page.load_failed' }),
      });
  }
}
