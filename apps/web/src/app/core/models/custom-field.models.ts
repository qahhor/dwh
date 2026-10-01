export type CustomFieldEntityType = 'USER' | 'PROJECT' | 'TASK' | 'NOTE' | string;
export type CustomFieldType = 'string' | 'number' | 'boolean' | 'date' | 'datetime' | 'time' | 'select' | 'user_ref';

export interface CustomField {
  id: number;
  entityType: CustomFieldEntityType;
  code: string;
  name: string;
  fieldType: CustomFieldType;
  isRequired: boolean;
  defaultValue?: string;
  optionsJson?: string;
  orderNo: number;
  createdAt: string;
  /** What a change of the record names in If-Match (plan item 3.6). */
  revision?: number;
}
