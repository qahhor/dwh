import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '@core/services/api.service';
import {
  CreatedWebhookSubscription,
  CreateWebhookSubscriptionDto,
  WebhookSubscription,
} from './webhooks-settings.models';

/** Webhook subscriptions: list, create (the answer carries the signing secret once), pause and delete. */
@Injectable({ providedIn: 'root' })
export class WebhooksApi {
  private readonly api = inject(ApiService);

  list(): Observable<WebhookSubscription[]> {
    return this.api.get<WebhookSubscription[]>('/webhooks/subscriptions');
  }

  create(body: CreateWebhookSubscriptionDto): Observable<CreatedWebhookSubscription> {
    return this.api.post<CreatedWebhookSubscription>('/webhooks/subscriptions', body, { notifyError: false });
  }

  setState(id: number, state: string, revision: number | undefined): Observable<void> {
    return this.api.patch<void>(`/webhooks/subscriptions/${id}`, { state }, { ifMatch: revision });
  }

  /** The confirmation shows the failure, so no general error toast. */
  remove(id: number): Observable<void> {
    return this.api.delete<void>(`/webhooks/subscriptions/${id}`, { notifyError: false });
  }
}
