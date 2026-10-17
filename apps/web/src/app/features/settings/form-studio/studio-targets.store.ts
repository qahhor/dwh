import { DestroyRef, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import type { components } from '@core/api/openapi';
import type { KeysetPage } from '@core/models/common.models';
import { ApiService } from '@core/services/api.service';
import { PermissionService } from '@core/services/permission.service';
import type { StudioLabels } from './form-studio.models';

type ListSummary = Required<components['schemas']['StudioListSummary']>;
type FormSummary = Required<Omit<components['schemas']['StudioFormSummary'], 'broken'>> & { broken?: string | null };

/** A reference list or a live form a reference field may name: its code and its names. */
export interface StudioTarget {
  code: string;
  labels: StudioLabels;
}

/**
 * What the reference fields of a live form may name (ADR-0041): the reference lists the publisher may read
 * (`studio.lists.view`) and the live forms they publish. The first page of each (100) is offered; the server checks
 * every target again when the form is activated.
 */
@Injectable()
export class StudioTargetsStore {
  private readonly api = inject(ApiService);
  private readonly permissions = inject(PermissionService);
  private readonly destroyRef = inject(DestroyRef);
  readonly lists = signal<StudioTarget[]>([]);
  readonly forms = signal<StudioTarget[]>([]);
  private loaded = false;

  load(): void {
    if (this.loaded) return;
    this.loaded = true;
    if (this.permissions.hasPermission('studio.lists', 'view')) {
      this.api
        .get<KeysetPage<ListSummary>>('/studio/lists', { limit: 100 }, { notifyError: false })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (page) => this.lists.set((page.items ?? []).map((list) => ({ code: list.code, labels: list.name }))),
          error: () => this.lists.set([]),
        });
    }
    if (this.permissions.hasPermission('studio.forms', 'publish')) {
      this.api
        .get<KeysetPage<FormSummary>>('/studio/forms', { limit: 100 }, { notifyError: false })
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (page) =>
            this.forms.set(
              (page.items ?? [])
                .filter((form) => form.status === 'active' && !form.quarantined && !form.broken)
                .map((form) => ({ code: form.entityCode, labels: form.title })),
            ),
          error: () => this.forms.set([]),
        });
    }
  }
}
