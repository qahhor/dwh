import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { SearchResult } from '../models/search.models';

@Injectable({
  providedIn: 'root'
})
export class CommandPaletteService {
  readonly isOpen = signal<boolean>(false);

  constructor(private api: ApiService) {}

  open() {
    this.isOpen.set(true);
  }

  close() {
    this.isOpen.set(false);
  }

  toggle() {
    this.isOpen.update(v => !v);
  }

  search(query: string, entityType: string = 'ALL', limit: number = 10): Observable<SearchResult> {
    return this.api.get<SearchResult>('/search', { q: query, entity: entityType, limit });
  }
}
