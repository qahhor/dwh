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
  hits: SearchHit[];
}
