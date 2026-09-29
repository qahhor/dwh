export interface KeysetPage<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
  totalReturned: number;
  /** The whole list's size, counted on the first page by registry lists (ADR-0016). */
  totalEstimated?: number;
  /** False when `totalEstimated` is an estimate of a large table, not a count (plan item 3.5). */
  totalExact?: boolean;
}

export interface ProblemDetail {
  type?: string;
  title: string;
  status: number;
  retryAfterSeconds?: number;
  code: string;
  detail: string;
  /** Catalog key of the text and its parameters (plan 10/10, item 3.1): what a screen compares, not the text. */
  messageKey?: string;
  params?: Record<string, string | number>;
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
