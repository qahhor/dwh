/** The `POST …/bulk` contract (server `BulkRunner`): one action over chosen records, reported record by record. */

export interface BulkRequest<P = Record<string, unknown>> {
  action: string;
  ids: number[];
  params?: P;
}

export interface BulkItemResult {
  id: number;
  ok: boolean;
  /** Error code of the single operation that failed, e.g. `not_found`. */
  code: string | null;
  message: string | null;
}

export interface BulkResult {
  action: string;
  succeeded: number;
  failed: number;
  results: BulkItemResult[];
}

/** The server takes at most this many records per request. */
export const BULK_MAX_IDS = 100;

export function failedItems(result: BulkResult): BulkItemResult[] {
  return result.results.filter(item => !item.ok);
}
