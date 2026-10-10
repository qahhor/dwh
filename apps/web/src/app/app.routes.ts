import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { permissionGuard } from './core/services/permission.service';
import { projectRecordMatcher, taskRecordMatcher } from './core/services/search-target';
import { recordNavigationGuard } from './core/guards/record-navigation.guard';
import { uplFormatMatcher, uplSourceMatcher } from './features/upl/upl-routes';

import { moduleActiveGuard } from './core/guards/module-active.guard';
import { entityGuard } from './shared/entity/page/entity.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'reset-password',
    loadComponent: () =>
      import('./features/auth/reset-password/reset-password.component').then((m) => m.ResetPasswordComponent),
  },
  {
    path: '',
    loadComponent: () => import('./layout/app-shell/app-shell.component').then((m) => m.AppShellComponent),
    canActivate: [authGuard],
    children: [
      {
        path: '',
        redirectTo: 'tasks',
        pathMatch: 'full',
      },
      {
        matcher: projectRecordMatcher,
        canDeactivate: [recordNavigationGuard],
        loadComponent: () => import('./features/tasks/projects/projects.component').then((m) => m.ProjectsComponent),
      },
      {
        matcher: taskRecordMatcher,
        canDeactivate: [recordNavigationGuard],
        loadComponent: () => import('./features/tasks/tasks.component').then((m) => m.TasksComponent),
      },
      {
        path: 'iam/roles',
        canDeactivate: [recordNavigationGuard],
        loadComponent: () => import('./features/iam/roles/roles.component').then((m) => m.RolesComponent),
      },
      {
        path: 'iam/org-units',
        canActivate: [permissionGuard('md.org_units', 'view')],
        canDeactivate: [recordNavigationGuard],
        loadComponent: () => import('./features/iam/org-units/org-units.component').then((m) => m.OrgUnitsComponent),
      },
      {
        path: 'iam/custom-fields',
        loadComponent: () =>
          import('./features/iam/custom-fields/custom-fields.component').then((m) => m.CustomFieldsComponent),
      },
      {
        path: 'iam/profile',
        loadComponent: () => import('./features/iam/profile/profile.component').then((m) => m.ProfileComponent),
      },
      {
        path: 'notifications',
        loadComponent: () =>
          import('./features/notifications/notifications.component').then((m) => m.NotificationsComponent),
      },
      {
        path: 'exports',
        loadComponent: () => import('./features/exports/exports.component').then((m) => m.ExportsComponent),
      },
      {
        path: 'files',
        loadComponent: () => import('./features/files/files.component').then((m) => m.FilesComponent),
      },
      {
        path: 'audit',
        canActivate: [permissionGuard('audit.log', 'view')],
        loadComponent: () => import('./features/audit/audit.component').then((m) => m.AuditComponent),
      },
      {
        path: 'analytics',
        loadComponent: () => import('./features/analytics/analytics.component').then((m) => m.AnalyticsComponent),
      },
      {
        path: 'settings',
        canDeactivate: [recordNavigationGuard],
        loadComponent: () => import('./features/settings/settings.component').then((m) => m.SettingsComponent),
      },
      {
        path: 'system',
        canActivate: [permissionGuard('md.settings', 'view')],
        loadComponent: () => import('./features/system/system.component').then((m) => m.SystemComponent),
      },
      {
        path: 'announcements',
        canActivate: [permissionGuard('notify.announcements', 'update')],
        loadComponent: () =>
          import('./features/announcements/announcements.component').then((m) => m.AnnouncementsComponent),
      },
      {
        path: 'upl/sources',
        pathMatch: 'full',
        canActivate: [moduleActiveGuard('upl'), permissionGuard('upl.sources', 'view')],
        loadComponent: () =>
          import('./features/upl/sources/sources-list.component').then((m) => m.SourcesListComponent),
      },
      {
        path: 'upl/overview',
        pathMatch: 'full',
        canActivate: [moduleActiveGuard('upl'), permissionGuard('upl.packages', 'view')],
        loadComponent: () =>
          import('./features/upl/overview/upl-overview.component').then((m) => m.UplOverviewComponent),
      },
      {
        path: 'upl/packages',
        pathMatch: 'full',
        canActivate: [moduleActiveGuard('upl'), permissionGuard('upl.packages', 'view')],
        loadComponent: () => import('./features/upl/packages/packages.component').then((m) => m.PackagesComponent),
      },
      {
        matcher: uplFormatMatcher,
        canActivate: [moduleActiveGuard('upl'), permissionGuard('upl.sources', 'view')],
        canDeactivate: [recordNavigationGuard],
        loadComponent: () =>
          import('./features/upl/formats/format-editor.component').then((m) => m.FormatEditorComponent),
      },
      {
        matcher: uplSourceMatcher,
        canActivate: [moduleActiveGuard('upl'), permissionGuard('upl.sources', 'view')],
        canDeactivate: [recordNavigationGuard],
        loadComponent: () => import('./features/upl/sources/source-card.component').then((m) => m.SourceCardComponent),
      },
      {
        path: 'notes',
        canActivate: [moduleActiveGuard('notes'), permissionGuard('notes', 'view')],
        loadComponent: () => import('./features/notes/notes.component').then((m) => m.NotesComponent),
      },
      {
        // The general screen of every declared entity (ADR-0032 7.1): a new entity needs no route of its own.
        path: 'e/:code',
        canActivate: [entityGuard],
        loadChildren: () => import('./features/entity-screens.routes').then((m) => m.ENTITY_SCREEN_ROUTES),
      },
      {
        path: 'settings/modules',
        canActivate: [permissionGuard('md.modules', 'view')],
        loadComponent: () => import('./features/settings/modules/modules.component').then((m) => m.ModulesComponent),
      },
      {
        path: 'settings/navigation',
        canActivate: [permissionGuard('md.navigation', 'view')],
        loadComponent: () =>
          import('./features/settings/navigation/navigation-settings.component').then(
            (m) => m.NavigationSettingsComponent,
          ),
      },
      {
        path: 'embed/:code',
        loadComponent: () =>
          import('./features/reports/embedded-report.component').then((m) => m.EmbeddedReportComponent),
      },
    ],
  },

  {
    path: '**',
    redirectTo: 'tasks',
  },
];
