import { Routes } from '@angular/router';
import { ENTITY_ROUTES } from '@shared/entity/page/entity.routes';
import { provideUserScreen } from './iam/users/users.overrides';

/**
 * The general entity screen `/e/:code` (ADR-0032 7.1) with the tweaks of the application's entities by key
 * (`provideEntityOverrides`, ADR-0032 7.2): loaded with the screen, so the tabs and controls of an entity's own add
 * nothing to the start of the application. An entity without tweaks is not named here.
 */
export const ENTITY_SCREEN_ROUTES: Routes = [
  {
    path: '',
    providers: [provideUserScreen()],
    children: ENTITY_ROUTES,
  },
];
