import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { CustomField, CustomFieldEntityType, CustomFieldType } from '../models/custom-field.models';
import { ApiService } from './api.service';
import { MetaCacheState } from './meta-cache';

/** What the administrator sets on a custom field; the entity, code and type are fixed once created. */
export interface CustomFieldChanges {
  name: string;
  isRequired: boolean;
  defaultValue?: string;
  options?: Array<string | { value: string; label: string }>;
  orderNo: number;
}

export interface NewCustomField extends CustomFieldChanges {
  entityType: CustomFieldEntityType;
  code: string;
  fieldType: CustomFieldType | string;
}

/**
 * Custom field definitions: the administration screen edits them, the entity screens read them. A change makes
 * every list and form description stale (plan 10/10, item 5.0), so the new field is a column and a form field
 * on the next screen without a reload.
 */
@Injectable({ providedIn: 'root' })
export class CustomFieldsApi {
  private readonly api = inject(ApiService);
  private readonly meta = inject(MetaCacheState);

  /** Every definition, or those of one entity. */
  list(entityType?: CustomFieldEntityType): Observable<CustomField[]> {
    return this.api.get<CustomField[]>('/custom-fields', entityType ? { entityType } : undefined);
  }

  /** The screen shows a failure itself. */
  create(field: NewCustomField): Observable<CustomField> {
    return this.api.post<CustomField>('/custom-fields', field, { notifyError: false }).pipe(this.changed());
  }

  /** Saves the field changed from `revision` (plan item 3.6): a stale revision is 409; the screen shows a failure. */
  update(id: number, changes: CustomFieldChanges, revision: number | undefined): Observable<CustomField> {
    return this.api
      .patch<CustomField>(`/custom-fields/${id}`, changes, { notifyError: false, ifMatch: revision })
      .pipe(this.changed());
  }

  /** The confirmation shows the failure, so no general error toast. */
  remove(id: number): Observable<unknown> {
    return this.api.delete(`/custom-fields/${id}`, { notifyError: false }).pipe(this.changed());
  }

  private changed<T>() {
    // A 204 may complete without a value: either way the change is done.
    return tap<T>({ next: () => this.meta.invalidate(), complete: () => this.meta.invalidate() });
  }
}
