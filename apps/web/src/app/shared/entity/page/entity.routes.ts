import { Routes } from '@angular/router';
import { SMTEntityPageComponent } from './smt-entity-page.component';

/**
 * The general screen of every declared entity (ADR-0032 7.1): its list, a new record, a record, a record's change.
 * `smt-entity-page` reads the entity's form once for the page under it.
 */
export const ENTITY_ROUTES: Routes = [
  {
    path: '',
    component: SMTEntityPageComponent,
    children: [
      {
        path: '',
        loadComponent: () => import('./smt-entity-list-page.component').then((m) => m.SMTEntityListPageComponent),
      },
      {
        path: 'new',
        loadComponent: () => import('./smt-entity-edit-page.component').then((m) => m.SMTEntityEditPageComponent),
      },
      {
        path: ':id',
        loadComponent: () => import('./smt-entity-record-page.component').then((m) => m.SMTEntityRecordPageComponent),
      },
      {
        path: ':id/edit',
        loadComponent: () => import('./smt-entity-edit-page.component').then((m) => m.SMTEntityEditPageComponent),
      },
    ],
  },
];
