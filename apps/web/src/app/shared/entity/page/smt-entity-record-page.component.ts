import { NgComponentOutlet, NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, finalize, map, of, tap } from 'rxjs';
import type { FormCollectionMeta, FormSectionMeta, FormTabMeta } from '@core/models/form-meta.models';
import { hasCapability, recordValues } from '@core/services/form-meta.service';
import { I18nService, TranslatePipe } from '@core/services/i18n.service';
import { PermissionService } from '@core/services/permission.service';
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
import { SMTEntityRelatedComponent } from '../smt-entity-related.component';
import { SMTEntityRowsComponent } from '../smt-entity-rows.component';
import { isNotFound, recordIdOf, recordName } from './entity-page';
import { SMTEntityPageStateComponent } from './smt-entity-page-state.component';
import { EntityPageContext } from './smt-entity-page.component';

/** The actions the page draws as its own buttons; any other action of the record is run by its code. */
const OWN_ACTIONS = new Set(['create', 'update', 'archive', 'delete']);

/**
 * A record of a declared entity, `/e/:code/:id` (ADR-0032 7.1): its fields by the form's sections in words, its
 * change history and the entity's own tabs; the buttons follow the record's `actions` — what this viewer may do with
 * this record now — so a right the viewer lacks is never offered. Archiving, restoring and every action name the
 * revision the record was read at. A document's card has the tabs its declaration gives (ADR-0032 9.3): sections, the
 * rows of a collection, a related list of another entity, the history; its state shows next to its name, and the
 * transitions of its process are buttons with their question first (ADR-0032 9.2).
 */
@Component({
  selector: 'smt-entity-record-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgComponentOutlet,
    NgTemplateOutlet,
    RouterLink,
    SMTBadgeComponent,
    SMTButtonComponent,
    SMTEntityCardComponent,
    SMTEntityRelatedComponent,
    SMTEntityRowsComponent,
    SMTEntityPageStateComponent,
    SMTTabBarComponent,
    TranslatePipe,
    UiPageHeaderComponent,
    UiRecordHistoryComponent,
  ],
  host: { class: 'smt-entity-record-page' },
  templateUrl: './smt-entity-record-page.component.html',
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
  private readonly permissions = inject(PermissionService);

  /** The record as last read: loaded, or as an archive switch or an action returned it. */
  readonly record = linkedSignal<EntityRecord | null>(() => (this.loaded.hasValue() ? this.loaded.value() : null));
  readonly busy = signal(false);

  readonly tab = linkedSignal<string>(() => {
    this.recordId();
    return this.context.formMeta().tabs?.[0]?.key ?? 'fields';
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
    const declared = this.context.formMeta().tabs;
    if (declared?.length) {
      // The tabs the declaration gives (ADR-0032 9.3), then the entity's own.
      return [
        ...declared.map((tab) => ({ value: tab.key, label: t(tab.labelKey), panelId: 'entity-record-panel' })),
        ...this.ownTabs().map((tab) => ({ value: tab.key, label: t(tab.labelKey), panelId: 'entity-record-panel' })),
      ];
    }
    return [
      { value: 'fields', label: t('ui.entity_page.tab_fields'), panelId: 'entity-record-panel' },
      ...(hasCapability(this.context.formMeta(), 'history')
        ? [{ value: 'history', label: t('ui.entity_page.tab_history'), panelId: 'entity-record-panel' }]
        : []),
      ...this.ownTabs().map((tab) => ({
        value: tab.key,
        label: t(tab.labelKey),
        panelId: 'entity-record-panel',
      })),
    ];
  });

  /** The declared tab open now, or null on a card with the platform's own tabs or on an entity's own tab. */
  readonly metaTab = computed<FormTabMeta | null>(
    () => this.context.formMeta().tabs?.find((tab) => tab.key === this.tab()) ?? null,
  );

  /** The record as the server returned it, by property: the rows of its collections and the currency of their money. */
  readonly recordData = computed<Record<string, unknown>>(() => ({ ...(this.record() ?? {}) }));

  /** The name of the record's state in its process (ADR-0032 9.2), or null without one. */
  readonly stateLabel = computed(() => {
    this.i18n.currentLang();
    const workflow = this.context.formMeta().workflow;
    const record = this.recordData();
    const state = workflow?.states.find((candidate) => candidate.code === record[workflow.field]);
    return state ? this.i18n.translate(state.labelKey) : null;
  });

  /** The entity's own tabs the viewer may open: those without rights, or with one of them held. */
  private readonly ownTabs = computed(() =>
    (this.context.overrides().tabs ?? []).filter(
      (tab) =>
        !tab.requires?.length || tab.requires.some((right) => this.permissions.hasPermission(right.form, right.action)),
    ),
  );

  /** The record's id from the route. */
  readonly id = toSignal(inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id'))), {
    initialValue: null,
  });

  readonly loaded = rxResource({
    params: () => this.recordId(),
    stream: ({ params: id }) => (id === null ? of(null) : this.entities.get(this.context.code(), id)),
  });

  /** The sections a declared tab shows, in the order of the form; a section the viewer has no field in is gone. */
  sectionsOf(tab: FormTabMeta): FormSectionMeta[] {
    const keys = tab.sections ?? [];
    return this.sections().filter((section) => keys.includes(section.key));
  }

  collectionOf(tab: FormTabMeta): FormCollectionMeta | null {
    return this.context.formMeta().collections?.find((collection) => collection.key === tab.collection) ?? null;
  }

  rowsOfRecord(key: string): Record<string, unknown>[] {
    const rows = this.recordData()[key];
    return Array.isArray(rows) ? (rows as Record<string, unknown>[]) : [];
  }

  can(action: string): boolean {
    return (this.record()?.actions ?? []).includes(action);
  }

  sectionOverride(key: string) {
    return this.context.overrides().sections?.[key] ?? null;
  }

  tabOverride(key: string) {
    return this.ownTabs().find((tab) => tab.key === key)?.component ?? null;
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

  /**
   * Runs one of the record's actions from the revision on screen (ADR-0032 6.7). An action with a question asks first,
   * with the record's name: a transition's own (ADR-0032 9.2), or a text in the catalog (`entity.action_confirm.<code>`:
   * blocking, anonymisation).
   */
  run(action: string): void {
    const record = this.record();
    if (!record || this.busy()) return;
    const transition = this.context.formMeta().workflow?.transitions.find((candidate) => candidate.code === action);
    const confirmKey = transition?.confirmKey ?? `entity.action_confirm.${action}`;
    if (this.i18n.hasKey(confirmKey)) {
      this.modal
        .confirm({
          title: this.actionLabel(action),
          message: this.i18n.translate(confirmKey, { name: this.name() }),
          yesLabel: this.actionLabel(action),
          noLabel: this.i18n.translate('common.cancel'),
          destructive: true,
        })
        .subscribe((confirmed) => {
          if (confirmed) this.perform(action);
        });
      return;
    }
    this.perform(action);
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

  private perform(action: string): void {
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

  /** A change of the record: busy while it goes, and the record as the server returns it afterwards. */
  private track(change: Observable<EntityRecord>): Observable<EntityRecord> {
    this.busy.set(true);
    return change.pipe(
      tap((fresh) => this.record.set(fresh)),
      finalize(() => this.busy.set(false)),
    );
  }
}
