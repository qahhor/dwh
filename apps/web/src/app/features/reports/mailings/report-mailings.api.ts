import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';
import type { ApiSchema } from '@core/api/api-schema';
import type { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';
import type { ReportViewItem } from '@shared/entity/report/report-catalog.api';

/** A mailing of a saved report as the server answers it (ADR-0045). */
export type ReportMailing = ApiSchema<'ReportMailing'>;
export type ReportMailingRequest = ApiSchema<'ReportMailingRequest'>;
export type ReportMailingSchedule = ApiSchema<'ReportMailingSchedule'>;
export type ReportMailingCondition = ApiSchema<'ReportMailingCondition'>;
export type ReportMailingOptions = ApiSchema<'ReportMailingOptions'>;
export type ReportMailingRun = ApiSchema<'ReportMailingRun'>;
export type ReportMailingRunDetail = ApiSchema<'ReportMailingRunDetail'>;
export type ReportMailingDelivery = ApiSchema<'ReportMailingDelivery'>;

/** The channels a mailing may go out on. */
export type MailingChannel = 'email' | 'telegram' | 'in_app';

const QUIET = { notifyError: false };

/**
 * Scheduled mailings of saved reports (ADR-0045): `/report-mailings` — the options of this installation, the mailings
 * of a report, a create, a change and a delete at their revision, a send now to all or a test to me, and the run
 * history; `/report-views/{id}` names the report they send.
 */
@Injectable({ providedIn: 'root' })
export class ReportMailingsApi {
  private readonly api = inject(ApiService);

  report(viewId: number): Observable<ReportViewItem> {
    return this.api.get<ReportViewItem>(`/report-views/${viewId}`, undefined, QUIET);
  }

  options(): Observable<ReportMailingOptions> {
    return this.api.get<ReportMailingOptions>('/report-mailings/options', undefined, QUIET);
  }

  list(viewId: number, cursor?: string | null): Observable<KeysetPage<ReportMailing>> {
    return this.api.get<KeysetPage<ReportMailing>>(
      '/report-mailings',
      { viewId, limit: 50, ...(cursor ? { cursor } : {}) },
      QUIET,
    );
  }

  create(body: ReportMailingRequest): Observable<ReportMailing> {
    return this.api.post<ReportMailing>('/report-mailings', body, QUIET);
  }

  update(id: number, revision: number, body: ReportMailingRequest): Observable<ReportMailing> {
    return this.api.put<ReportMailing>(`/report-mailings/${id}`, body, { ...QUIET, ifMatch: revision });
  }

  remove(id: number, revision: number): Observable<void> {
    return this.api.delete<void>(`/report-mailings/${id}`, { ...QUIET, ifMatch: revision });
  }

  /** A send now to every recipient (`all`) or a test to the person who asks (`me`). */
  send(id: number, scope: 'all' | 'me'): Observable<ReportMailingRun> {
    return this.api.post<ReportMailingRun>(`/report-mailings/${id}/runs`, { scope }, QUIET);
  }

  runs(id: number, cursor?: string | null): Observable<KeysetPage<ReportMailingRun>> {
    return this.api.get<KeysetPage<ReportMailingRun>>(
      `/report-mailings/${id}/runs`,
      { limit: 20, ...(cursor ? { cursor } : {}) },
      QUIET,
    );
  }

  run(id: number, runId: number): Observable<ReportMailingRunDetail> {
    return this.api.get<ReportMailingRunDetail>(`/report-mailings/${id}/runs/${runId}`, undefined, QUIET);
  }
}

/** The request that changes a stored mailing, every part kept but what `changes` names. */
export function mailingRequest(
  mailing: ReportMailing,
  changes: Partial<ReportMailingRequest> = {},
): ReportMailingRequest {
  return {
    name: mailing.name,
    recipientIds: mailing.recipients.map((recipient) => recipient.id),
    channels: [...mailing.channels],
    attachXlsx: mailing.attachXlsx,
    includeLink: mailing.includeLink,
    schedule: { ...mailing.schedule },
    parameters: { ...(mailing.parameters as Record<string, unknown>) },
    condition: { ...mailing.condition },
    active: mailing.active,
    ...changes,
  } as ReportMailingRequest;
}
