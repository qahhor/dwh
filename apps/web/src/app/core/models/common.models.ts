export interface KeysetPage<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
  totalReturned: number;
  /** The whole list's size, counted on the first page by registry lists (ADR-0016). */
  totalEstimated?: number;
}

export interface ProblemDetail {
  type?: string;
  title: string;
  status: number;
  retryAfterSeconds?: number;
  code: string;
  detail: string;
  instance?: string;
  timestamp?: string;
  errors?: FieldErrorItem[];
  invalid_params?: Array<{ name: string; reason: string; code?: string }>;
}


export interface FieldErrorItem {
  field: string;
  code: string;
  message: string;
}
