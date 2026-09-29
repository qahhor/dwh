import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CustomField, CustomFieldEntityType, CustomFieldType } from '../models/custom-field.models';
import { ApiService } from './api.service';

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

/** Custom field definitions: the administration screen edits them, the entity screens read them. */
@Injectable({ providedIn: 'root' })
export class CustomFieldsApi {
  private readonly api = inject(ApiService);

  /** Every definition, or those of one entity. */
  list(entityType?: CustomFieldEntityType): Observable<CustomField[]> {
    return this.api.get<CustomField[]>('/custom-fields', entityType ? { entityType } : undefined);
  }

  create(field: NewCustomField): Observable<CustomField> {
    return this.api.post<CustomField>('/custom-fields', field);
  }

  /** Saves the field changed from `revision` (plan item 3.6): a stale revision is 409. */
  update(id: number, changes: CustomFieldChanges, revision: number | undefined): Observable<CustomField> {
    return this.api.patch<CustomField>(`/custom-fields/${id}`, changes, { ifMatch: revision });
  }

  /** The confirmation shows the failure, so no general error toast. */
  remove(id: number): Observable<unknown> {
    return this.api.delete(`/custom-fields/${id}`, { notifyError: false });
  }
}
