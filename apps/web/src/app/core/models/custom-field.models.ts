export interface CustomField {
  id: number;
  entityType: 'USER' | 'PROJECT' | 'TASK';
  code: string;
  name: string;
  fieldType: 'string' | 'number' | 'boolean' | 'date' | 'select';
  isRequired: boolean;
  defaultValue?: string;
  optionsJson?: string;
  orderNo: number;
  createdAt: string;
}
