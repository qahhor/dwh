import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import type { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';
import type { SMTLookupSource } from '@shared/ui-kit/components/forms/data-select/lookup-source';
import { restLookup } from '@shared/lookups/lookup-sources';

/** An automation of a live form as the server answers it (ADR-0046). */
export type Automation = ApiSchema<'StudioAutomation'>;
export type AutomationRequest = ApiSchema<'StudioAutomationRequest'>;
export type AutomationTrigger = ApiSchema<'StudioAutomationTrigger'>;
export type AutomationAction = ApiSchema<'StudioAutomationAction'>;
export type AutomationTexts = ApiSchema<'StudioAutomationTexts'>;
export type AutomationOptions = ApiSchema<'StudioAutomationOptions'>;
export type AutomationRun = ApiSchema<'StudioAutomationRun'>;
export type AutomationActionResult = ApiSchema<'StudioAutomationActionResult'>;
export type AutomationProblem = ApiSchema<'StudioAutomationProblem'>;
export type AutomationDryRun = ApiSchema<'StudioAutomationDryRun'>;
export type AutomationRunNow = ApiSchema<'StudioAutomationRunNow'>;
export type AutomationCheck = ApiSchema<'StudioFormulaCheck'>;

/** A live form as the Studio list names it. */
export interface AutomationFormSummary {
  entityCode: string;
  title: { ru: string; uz: string; en: string };
}

/** A record of a live form as the dry run's picker names it. */
export interface AutomationRecordRow {
  id: number;
  recordNumber?: number | null;
  docNumber?: string | null;
}

/** What starts an automation. */
export type TriggerKind = AutomationTrigger['kind'];
/** The kinds of its actions. */
export type ActionType = AutomationAction['type'];

const QUIET = { notifyError: false };

/**
 * Automations of live forms (ADR-0046): `/automations` — the options of this installation, the automations of a form,
 * a create, a change and a delete at their revision, the run log, a run now of a scheduled automation, a dry run over
 * a record and the check of one formula while it is typed.
 */
@Injectable({ providedIn: 'root' })
export class AutomationsApi {
  private readonly api = inject(ApiService);

  /** The live forms of the installation, for the editor's choice of a form. */
  forms(): Observable<KeysetPage<AutomationFormSummary>> {
    return this.api.get<KeysetPage<AutomationFormSummary>>('/studio/forms', { limit: 100 }, QUIET);
  }

  /** The records of a live form the viewer sees, for the dry run's picker: by number or document number. */
  records(form: string): SMTLookupSource<AutomationRecordRow, number> {
    return restLookup<AutomationRecordRow>(this.api, {
      path: `/entities/${form}`,
      searchParam: 'q',
      key: (row) => row.id,
      option: (row) => ({ label: row.docNumber || `#${row.recordNumber ?? row.id}` }),
    });
  }

  options(): Observable<AutomationOptions> {
    return this.api.get<AutomationOptions>('/automations/options', undefined, QUIET);
  }

  list(form: string | null, cursor?: string | null): Observable<KeysetPage<Automation>> {
    return this.api.get<KeysetPage<Automation>>(
      '/automations',
      { limit: 100, ...(form ? { form } : {}), ...(cursor ? { cursor } : {}) },
      QUIET,
    );
  }

  get(id: number): Observable<Automation> {
    return this.api.get<Automation>(`/automations/${id}`, undefined, QUIET);
  }

  create(body: AutomationRequest): Observable<Automation> {
    return this.api.post<Automation>('/automations', body, QUIET);
  }

  update(id: number, revision: number, body: AutomationRequest): Observable<Automation> {
    return this.api.put<Automation>(`/automations/${id}`, body, { ...QUIET, ifMatch: revision });
  }

  remove(id: number, revision: number): Observable<void> {
    return this.api.delete<void>(`/automations/${id}`, { ...QUIET, ifMatch: revision });
  }

  runs(id: number, cursor?: string | null): Observable<KeysetPage<AutomationRun>> {
    return this.api.get<KeysetPage<AutomationRun>>(
      `/automations/${id}/runs`,
      { limit: 20, ...(cursor ? { cursor } : {}) },
      QUIET,
    );
  }

  runNow(id: number): Observable<AutomationRunNow> {
    return this.api.post<AutomationRunNow>(`/automations/${id}/runs`, {}, QUIET);
  }

  dryRun(recordId: number, automation: AutomationRequest): Observable<AutomationDryRun> {
    return this.api.post<AutomationDryRun>('/automations/dry-run', { recordId, automation }, QUIET);
  }

  /** The type of a formula, or its first problem; with a target field the formula computes that field's value. */
  check(
    form: string,
    trigger: TriggerKind,
    formula: string,
    target: { form?: string | null; field: string } | null,
  ): Observable<AutomationCheck> {
    return this.api.post<AutomationCheck>(
      '/automations/formulas/check',
      {
        form,
        trigger,
        formula,
        ...(target?.field ? { targetField: target.field } : {}),
        ...(target?.form ? { targetForm: target.form } : {}),
      },
      QUIET,
    );
  }
}

/** The request that changes a stored automation, every part kept but what `changes` names. */
export function automationRequest(automation: Automation, changes: Partial<AutomationRequest> = {}): AutomationRequest {
  return {
    name: automation.name,
    active: automation.active,
    trigger: { ...automation.trigger },
    ...(automation.condition ? { condition: automation.condition } : {}),
    actions: automation.actions.map((action) => ({ ...action })),
    timeZone: automation.timeZone,
    ...changes,
  };
}
