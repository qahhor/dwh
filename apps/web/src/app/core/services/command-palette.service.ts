import { Injectable, signal, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from './api.service';
import { SearchResult } from '../models/search.models';

@Injectable({
  providedIn: 'root',
})
export class CommandPaletteService {
  private api = inject(ApiService);

  readonly isOpen = signal<boolean>(false);

  open() {
    this.isOpen.set(true);
  }

  close() {
    this.isOpen.set(false);
  }

  toggle() {
    this.isOpen.update((v) => !v);
  }

  search(query: string, entityType: string = 'ALL', limit?: number): Observable<SearchResult> {
    return this.api.get<SearchResult>('/search', { q: query, entity: entityType, limit }, { notifyError: false });
  }
}
