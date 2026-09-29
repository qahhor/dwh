import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ApiRequestOptions, ApiService } from './api.service';
import {
  CustomNavigationItem,
  EntityMenuItem,
  NavigationPermissionChoice,
  CreateNavigationItemPayload,
  UpdateNavigationItemPayload,
} from '../models/navigation.models';

@Injectable({
  providedIn: 'root',
})
export class NavigationService {
  private readonly api = inject(ApiService);

  readonly activeItems = signal<CustomNavigationItem[]>([]);
  /** Menu items of the declared entities the viewer may open (roadmap item 57). */
  readonly entityItems = signal<EntityMenuItem[]>([]);
  readonly isLoading = signal<boolean>(false);

  loadActiveItems(): Observable<CustomNavigationItem[]> {
    this.isLoading.set(true);
    return this.api.get<CustomNavigationItem[]>('/navigation/items/active').pipe(
      tap({
        next: (items) => {
          this.activeItems.set(items || []);
          this.isLoading.set(false);
        },
        error: () => {
          this.isLoading.set(false);
        },
      }),
    );
  }

  loadEntityItems(): Observable<EntityMenuItem[]> {
    return this.api
      .get<EntityMenuItem[]>('/entities/menu', undefined, { notifyError: false })
      .pipe(tap((items) => this.entityItems.set(items || [])));
  }

  loadAllItems(): Observable<CustomNavigationItem[]> {
    return this.api.get<CustomNavigationItem[]>('/navigation/items', undefined, { notifyError: false });
  }

  /** Catalog pairs an administrator can limit a menu item to. */
  loadPermissionChoices(): Observable<NavigationPermissionChoice[]> {
    return this.api.get<NavigationPermissionChoice[]>('/navigation/items/permissions');
  }

  getItemById(id: number): Observable<CustomNavigationItem> {
    return this.api.get<CustomNavigationItem>(`/navigation/items/${id}`);
  }

  getItemByCode(code: string): Observable<CustomNavigationItem> {
    return this.api.get<CustomNavigationItem>(`/navigation/items/by-code/${code}`);
  }

  createItem(payload: CreateNavigationItemPayload): Observable<CustomNavigationItem> {
    return this.api
      .post<CustomNavigationItem>('/navigation/items', payload, { notifyError: false })
      .pipe(tap(() => this.loadActiveItems().subscribe({ error: () => {} })));
  }

  updateItem(id: number, payload: UpdateNavigationItemPayload): Observable<CustomNavigationItem> {
    return this.api
      .put<CustomNavigationItem>(`/navigation/items/${id}`, payload, { notifyError: false })
      .pipe(tap(() => this.loadActiveItems().subscribe({ error: () => {} })));
  }

  /** Shows or hides the item: the request states the result, so a repeated click changes nothing twice. */
  setActive(id: number, active: boolean): Observable<CustomNavigationItem> {
    return this.api
      .put<CustomNavigationItem>(`/navigation/items/${id}/active`, { active }, { notifyError: false })
      .pipe(tap(() => this.loadActiveItems().subscribe({ error: () => {} })));
  }

  deleteItem(id: number, options?: ApiRequestOptions): Observable<void> {
    return this.api
      .delete<void>(`/navigation/items/${id}`, options)
      .pipe(tap(() => this.loadActiveItems().subscribe({ error: () => {} })));
  }
}
