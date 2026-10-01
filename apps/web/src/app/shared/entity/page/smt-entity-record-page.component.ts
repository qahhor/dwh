import { NgComponentOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, finalize, map, of, tap } from 'rxjs';
import type { FormSectionMeta } from '@core/models/form-meta.models';
import { hasCapability, recordValues } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { ToastService } from '@core/services/toast.service';
import { SaveErrorNotifier } from '@shared/ui/save-errors';
import { UiPageHeaderComponent } from '@shared/ui/ui-page-header.component';
import { UiRecordHistoryComponent } from '@shared/ui/ui-record-history.component';
import { SMTBadgeComponent } from '@shared/ui-kit/components/badge/badge.component';
import { SMTButtonComponent } from '@shared/ui-kit/components/button';
import { SMTModalService } from '@shared/ui-kit/components/modal';
import { SMTTabBarComponent, SMTTabItem } from '@shared/ui-kit/components/tab-bar';
import { EntitiesApi, EntityRecord } from '../entities.api';
import { SMTEntityCardComponent } from '../smt-entity-card.component';
import { isNotFound, recordIdOf, recordName } from './entity-page';
import { SMTEntityPageStateComponent } from './smt-entity-page-state.component';
import { EntityPageContext } from './smt-entity-page.component';

/** The actions the page draws as its own buttons; any other action of the record is run by its code. */
const OWN_ACTIONS = new Set(['create', 'update', 'archive', 'delete']);

/**
 * A record of a declared entity, `/e/:code/:id` (ADR-0032 7.1): its fields by the form's sections in words, its
 * change history and the entity's own tabs; the buttons follow the record's `actions` — what this viewer may do with
 * this record now — so a right the viewer lacks is never offered. Archiving, restoring and every action name the
 * revision the record was read at.
 */
@Component({
  selector: 'smt-entity-record-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgComponentOutlet,
    RouterLink,
    SMTBadgeComponent,
    SMTButtonComponent,
    SMTEntityCardComponent,
    SMTEntityPageStateComponent,
    SMTTabBarComponent,
    TranslatePipe,
    UiPageHeaderComponent,
    UiRecordHistoryComponent,
  ],
  host: { class: 'smt-entity-record-page' },
  template: `
    @switch (state()) {
      @case ('missing') {
        <smt-entity-page-state kind="record" [back]="context.listLink()" />
      }
      @case ('failed') {
        <smt-entity-page-state kind="failed" [back]="context.listLink()" (retry)="loaded.reload()" />
      }
      @case ('ready') {
        @if (record(); as current) {
          <ui-page-header [title]="name()" [eyebrow]="context.title()">
            @if (current.archived) {
              <smt-badge smtSize="SM" smtVariant="gray" data-testid="entity-archived">{{
                'ui.entity.archived_mark' | t
              }}</smt-badge>
            }
            <a smt-button smtVariant="ghost" smtIcon="arrow_back" [routerLink]="context.listLink()">
              {{ 'ui.entity_page.back' | t }}
            </a>
            @if (can('update')) {
              <a smt-button smtVariant="primary" smtIcon="edit" routerLink="edit" data-testid="entity-edit">
                {{ 'common.edit' | t }}
              </a>
            }
            @for (action of otherActions(); track action) {
              <button
                smt-button
                type="button"
                smtVariant="secondary"
                [attr.data-action]="action"
                [smtLoading]="busy()"
                (click)="run(action)"
              >
                {{ actionLabel(action) }}
              </button>
            }
            @if (can('archive')) {
              <button
                smt-button
                type="button"
                smtVariant="secondary"
                smtIcon="inventory_2"
                data-testid="entity-archive"
                [smtLoading]="busy()"
                (click)="toggleArchive()"
              >
                {{ (current.archived ? 'ui.entity_page.restore' : 'ui.entity_page.archive') | t }}
              </button>
            }
            @if (can('delete')) {
              <button
                smt-button
                type="button"
                smtVariant="danger"
                smtIcon="delete"
                data-testid="entity-delete"
                [disabled]="busy()"
                (click)="remove()"
              >
                {{ 'common.delete' | t }}
              </button>
            }
          </ui-page-header>

          @if (tabs().length > 1) {
            <smt-tab-bar
              [tabs]="tabs()"
              [value]="tab()"
              [smtAriaLabel]="name()"
              smtIdPrefix="entity-record"
              (valueChange)="$event && tab.set($event)"
            />
          }
          <div
            id="entity-record-panel"
            class="entity-record-panel"
            [attr.role]="tabs().length > 1 ? 'tabpanel' : null"
            [attr.aria-labelledby]="tabs().length > 1 ? 'entity-record-' + tab() + '-tab' : null"
            [attr.data-tab]="tab()"
          >
            @switch (tab()) {
              @case ('fields') {
                @for (section of sections(); track section.key) {
                  <section class="entity-record-section" [attr.data-section]="section.key">
                    @if (sections().length > 1) {
                      <h2 class="entity-record-section-title">{{ section.labelKey | t }}</h2>
                    }
                    @if (sectionOverride(section.key); as custom) {
                      <ng-container
                        *ngComponentOutlet="
                          custom;
                          inputs: { meta: context.formMeta(), record: current, values: values() }
                        "
                      />
                    } @else {
                      <smt-entity-card
                        [meta]="context.formMeta()"
                        [value]="values()"
                        [sections]="[section.key]"
                        [recordId]="current.id"
                      />
                    }
                  </section>
                }
              }
              @case ('history') {
                <ui-record-history [kind]="context.code()" [recordId]="current.id" [meta]="context.formMeta()" />
              }
              @default {
                @if (tabOverride(tab()); as custom) {
                  <ng-container *ngComponentOutlet="custom; inputs: { meta: context.formMeta(), record: current }" />
                }
              }
            }
          </div>
        }
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
      .entity-record-panel {
        display: flex;
        flex-direction: column;
        gap: 16px;
        max-width: 960px;
      }
      .entity-record-section {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .entity-record-section-title {
        margin: 0;
        font-size: 0.875rem;
        font-weight: 600;
      }
    `,
  ],
})
export class SMTEntityRecordPageComponent {
  readonly context = inject(EntityPageContext);
  private readonly entities = inject(EntitiesApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly i18n = inject(I18nService);
  private readonly modal = inject(SMTModalService);
  private readonly saveErrors = inject(SaveErrorNotifier);

  /** The record's id from the route. */
  readonly id = toSignal(inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id'))), {
    initialValue: null,
  });

  /** The record as last read: loaded, or as an archive switch or an action returned it. */
  readonly record = linkedSignal<EntityRecord | null>(() => (this.loaded.hasValue() ? this.loaded.value() : null));
  readonly busy = signal(false);

  readonly tab = linkedSignal<string>(() => {
    this.recordId();
    return 'fields';
  });

  readonly recordId = computed(() => recordIdOf(this.id()));
  readonly values = computed(() => recordValues(this.context.formMeta(), this.record() ? { ...this.record() } : null));

  readonly state = computed<'loading' | 'ready' | 'missing' | 'failed'>(() => {
    if (this.recordId() === null) return 'missing';
    const status = this.loaded.status();
    if (status === 'error') return isNotFound(this.loaded.error()) ? 'missing' : 'failed';
    if (status !== 'resolved' && status !== 'local') return 'loading';
    return this.record() ? 'ready' : 'missing';
  });

  readonly name = computed(() => {
    this.i18n.currentLang();
    const record = this.record();
    return record
      ? recordName(this.context.formMeta(), record, (id) => this.i18n.translate('ui.entity_page.record', { id }))
      : '';
  });

  /** The record's actions that are not the page's own buttons: they are run by their code. */
  readonly otherActions = computed(() => (this.record()?.actions ?? []).filter((action) => !OWN_ACTIONS.has(action)));

  readonly sections = computed<FormSectionMeta[]>(() => this.context.formMeta().layout);

  readonly tabs = computed<SMTTabItem[]>(() => {
    this.i18n.currentLang();
    const t = (key: string) => this.i18n.translate(key);
    return [
      { value: 'fields', label: t('ui.entity_page.tab_fields'), panelId: 'entity-record-panel' },
      ...(hasCapability(this.context.formMeta(), 'history')
        ? [{ value: 'history', label: t('ui.entity_page.tab_history'), panelId: 'entity-record-panel' }]
        : []),
      ...(this.context.overrides().tabs ?? []).map((tab) => ({
        value: tab.key,
        label: t(tab.labelKey),
        panelId: 'entity-record-panel',
      })),
    ];
  });

  readonly loaded = rxResource({
    params: () => this.recordId(),
    stream: ({ params: id }) => (id === null ? of(null) : this.entities.get(this.context.code(), id)),
  });

  can(action: string): boolean {
    return (this.record()?.actions ?? []).includes(action);
  }

  sectionOverride(key: string) {
    return this.context.overrides().sections?.[key] ?? null;
  }

  tabOverride(key: string) {
    return this.context.overrides().tabs?.find((tab) => tab.key === key)?.component ?? null;
  }

  /** An action's label: `entity.action.<code>` when the catalog has it, otherwise the code itself. */
  actionLabel(action: string): string {
    const key = `entity.action.${action}`;
    return this.i18n.hasKey(key) ? this.i18n.translate(key) : action;
  }

  /** Moves the record to the archive or back, from the revision on screen (ADR-0032 5.4). */
  toggleArchive(): void {
    const record = this.record();
    if (!record || this.busy()) return;
    const archived = !record.archived;
    this.track(this.entities.setArchived(this.context.code(), record.id, archived, record.revision)).subscribe({
      next: () =>
        this.toast.success(this.i18n.translate(archived ? 'ui.entity_page.archived' : 'ui.entity_page.restored')),
      error: (problem: unknown) =>
        this.saveErrors.show(problem, {
          fallbackKey: 'ui.entity_page.archive_failed',
          reload: () => this.loaded.reload(),
        }),
    });
  }

  /** Runs one of the record's actions from the revision on screen (ADR-0032 6.7). */
  run(action: string): void {
    const record = this.record();
    if (!record || this.busy()) return;
    this.track(this.entities.action(this.context.code(), record.id, action, record.revision)).subscribe({
      next: () => this.toast.success(this.i18n.translate('ui.entity_page.action_done')),
      error: (problem: unknown) =>
        this.saveErrors.show(problem, {
          fallbackKey: 'ui.entity_page.action_failed',
          reload: () => this.loaded.reload(),
        }),
    });
  }

  /** Asks first; the dialog stays open while the record is deleted and shows the failure. */
  remove(): void {
    const record = this.record();
    if (!record) return;
    const t = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    this.modal
      .confirm({
        title: t('common.delete'),
        message: t('ui.entity_page.delete_confirm', { name: this.name() }),
        yesLabel: t('common.delete'),
        noLabel: t('common.cancel'),
        destructive: true,
        action: () =>
          this.entities.remove(this.context.code(), record.id).pipe(
            tap(() => {
              this.toast.success(t('ui.entity_page.deleted'));
              void this.router.navigate([this.context.listLink()]);
            }),
          ),
        actionError: () => t('ui.entity_page.delete_failed'),
      })
      .subscribe();
  }

  /** A change of the record: busy while it goes, and the record as the server returns it afterwards. */
  private track(change: Observable<EntityRecord>): Observable<EntityRecord> {
    this.busy.set(true);
    return change.pipe(
      tap((fresh) => this.record.set(fresh)),
      finalize(() => this.busy.set(false)),
    );
  }
}
