export interface KeysetPage<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
  totalReturned: number;
}

export interface ProblemDetail {
  type?: string;
  title: string;
  status: number;
  code: string;
  detail: string;
  instance?: string;
  timestamp?: string;
  errors?: FieldErrorItem[];
}

export interface FieldErrorItem {
  field: string;
  code: string;
  message: string;
}
