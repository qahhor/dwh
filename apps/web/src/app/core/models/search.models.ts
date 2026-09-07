export interface SearchHit {
  entityType: 'USER' | 'TASK' | 'PROJECT';
  id: string;
  title: string;
  description: string;
  targetUrl: string;
}

export interface SearchResult {
  query: string;
  totalHits: number;
  foundHits: number | null;
  hasMore: boolean;
  source: 'TYPESENSE' | 'POSTGRES';
  degraded: boolean;
  hits: SearchHit[];
}
