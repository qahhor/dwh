import { ChangeDetectionStrategy, Component, Injectable, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterOutlet } from '@angular/router';
import { map, of } from 'rxjs';
import type { FormMeta } from '@core/models/form-meta.models';
import { FormMetaService } from '@core/services/form-meta.service';
import { TranslatePipe } from '@core/services/i18n.service';
import { ENTITY_CODE, entityTitle, isNotFound } from './entity-page';
import { EntityOverrides, injectEntityOverrides } from './entity-overrides';
import { SMTEntityPageStateComponent } from './smt-entity-page-state.component';

/**
 * What the pages of one entity's general screen share (ADR-0032 7.1): its code from the route, its form from the
 * server — present before any page is drawn — its name and its overrides. Provided by `smt-entity-page` for the page
 * under it.
 */
@Injectable()
export class EntityPageContext {
  private readonly forms = inject(FormMetaService);

  /** The form once it has come; the pages are drawn only then. */
  readonly meta = computed<FormMeta | null>(() => (this.form.hasValue() ? this.form.value() : null));
  readonly overrides = computed<EntityOverrides>(() => this.overridesOf(this.code()));

  /** The entity's list address, as a router link. */
  readonly listLink = computed(() => `/e/${this.code()}`);

  readonly state = computed<'loading' | 'ready' | 'missing' | 'failed'>(() => {
    const status = this.form.status();
    if (status === 'error') return isNotFound(this.form.error()) ? 'missing' : 'failed';
    if (status !== 'resolved' && status !== 'local') return 'loading';
    return this.meta() ? 'ready' : 'missing';
  });

  private readonly overridesOf = injectEntityOverrides();

  readonly code = toSignal(inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('code') ?? '')), {
    initialValue: '',
  });

  /** The entity's form; an invalid code is not asked for, it is "not found" at once. */
  readonly form = rxResource({
    params: () => this.code(),
    stream: ({ params: code }) => (ENTITY_CODE.test(code) ? this.forms.get(code) : of(null)),
  });
  readonly title = entityTitle(this.code, this.meta);

  /** The form, for a page drawn under `smt-entity-page`, which draws nothing until it has come. */
  formMeta(): FormMeta {
    const meta = this.meta();
    if (!meta) throw new Error(`form-meta of ${this.code()} has not come`);
    return meta;
  }
}

/**
 * The general screen of a declared entity (ADR-0032 7.1), `/e/:code`: it reads the entity's form from the server once
 * and draws the page of the route under it — the list, the form or the record. An entity that is unknown, not the
 * viewer's or switched off is "not found", as the server answers 404 for all of them. A new code draws its pages
 * again from scratch, so no state of one entity reaches another.
 */
@Component({
  selector: 'smt-entity-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, SMTEntityPageStateComponent, TranslatePipe],
  providers: [EntityPageContext],
  host: { class: 'smt-entity-page' },
  template: `
    <div class="entity-page">
      @switch (context.state()) {
        @case ('ready') {
          @for (current of [context.code()]; track current) {
            <router-outlet />
          }
        }
        @case ('missing') {
          <smt-entity-page-state kind="entity" back="/" />
        }
        @case ('failed') {
          <smt-entity-page-state kind="failed" (retry)="context.form.reload()" />
        }
        @default {
          <p class="sr-only" role="status">{{ 'common.loading' | t }}</p>
        }
      }
    </div>
  `,
  styles: [
    `
      .entity-page {
        display: flex;
        flex-direction: column;
        gap: 20px;
        min-width: 0;
      }
    `,
  ],
})
export class SMTEntityPageComponent {
  readonly context = inject(EntityPageContext);
}
