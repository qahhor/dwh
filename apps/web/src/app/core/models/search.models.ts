/**
 * A hit of the global search (ADR-0032, 10.3): the code of its entity (`ms.tasks`), the record's id and where it
 * leads in the application — the entity's general screen `/e/<code>/<id>` or its own screen.
 */
export interface SearchHit {
  entityType: string;
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
  suggestedQuery?: string | null;
}

/** A searched field of an entity: its key and the dictionary key of its label. */
export interface SearchCategoryField {
  key: string;
  labelKey: string;
}

/** An entity the caller may search: its code, the dictionary key of its name, its icon and its searched fields. */
export interface SearchCategory {
  code: string;
  labelKey?: string | null;
  icon?: string | null;
  fields: SearchCategoryField[];
}
