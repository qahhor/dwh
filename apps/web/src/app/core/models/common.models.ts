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
  /** The text in the request's language, rendered by the server from messageKey when the item has one. */
  message: string;
  /** The catalog key of the text (plan 10/10, item 3.1); absent for bean validation's own messages. */
  messageKey?: string;
  params?: Record<string, unknown>;
}
