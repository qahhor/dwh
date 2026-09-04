# Application routes

All authenticated routes use `AppShellComponent` and `authGuard` unless noted.

| Route | Component | Purpose |
|---|---|---|
| `/login` | `features/auth/login/login.component.ts` | Authentication |
| `/tasks` | `features/tasks/tasks.component.ts` | Task list and kanban |
| `/tasks/projects` | `features/tasks/projects/projects.component.ts` | Project management |
| `/analytics` | `features/analytics/analytics.component.ts` | Operational analytics |
| `/iam/users` | `features/iam/users/users.component.ts` | User administration |
| `/iam/roles` | `features/iam/roles/roles.component.ts` | Role and permission matrix |
| `/iam/custom-fields` | `features/iam/custom-fields/custom-fields.component.ts` | Dynamic field administration |
| `/iam/profile` | `features/iam/profile/profile.component.ts` | Profile, password and tokens |
| `/files` | `features/files/files.component.ts` | File storage and upload |
| `/notifications` | `features/notifications/notifications.component.ts` | User notifications |
| `/announcements` | `features/announcements/announcements.component.ts` | Announcement management |
| `/audit` | `features/audit/audit.component.ts` | Audit and security events |
| `/system` | `features/system/system.component.ts` | Runtime health; permission guarded |
| `/settings` | `features/settings/settings.component.ts` | System and personal settings |

## Router source

```typescript
import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { AppShellComponent } from './layout/app-shell/app-shell.component';
import { permissionGuard } from './core/services/permission.service';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login/login.component').then(m => m.LoginComponent)
  },
  {
    path: '',
    component: AppShellComponent,
    canActivate: [authGuard],
    children: [
      {
        path: '',
        redirectTo: 'tasks',
        pathMatch: 'full'
      },
      {
        path: 'tasks',
        loadComponent: () => import('./features/tasks/tasks.component').then(m => m.TasksComponent)
      },
      {
        path: 'tasks/projects',
        loadComponent: () => import('./features/tasks/projects/projects.component').then(m => m.ProjectsComponent)
      },
      {
        path: 'iam/users',
        loadComponent: () => import('./features/iam/users/users.component').then(m => m.UsersComponent)
      },
      {
        path: 'iam/roles',
        loadComponent: () => import('./features/iam/roles/roles.component').then(m => m.RolesComponent)
      },
      {
        path: 'iam/custom-fields',
        loadComponent: () => import('./features/iam/custom-fields/custom-fields.component').then(m => m.CustomFieldsComponent)
      },
      {
        path: 'iam/profile',
        loadComponent: () => import('./features/iam/profile/profile.component').then(m => m.ProfileComponent)
      },
      {
        path: 'notifications',
        loadComponent: () => import('./features/notifications/notifications.component').then(m => m.NotificationsComponent)
      },
      {
        path: 'files',
        loadComponent: () => import('./features/files/files.component').then(m => m.FilesComponent)
      },
      {
        path: 'audit',
        loadComponent: () => import('./features/audit/audit.component').then(m => m.AuditComponent)
      },
      {
        path: 'analytics',
        loadComponent: () => import('./features/analytics/analytics.component').then(m => m.AnalyticsComponent)
      },
      {
        path: 'settings',
        loadComponent: () => import('./features/settings/settings.component').then(m => m.SettingsComponent)
      },
      {
        path: 'system',
        canActivate: [permissionGuard('platform.settings', 'view')],
        loadComponent: () => import('./features/system/system.component').then(m => m.SystemComponent)
      },
      {
        path: 'announcements',
        canActivate: [permissionGuard('platform.announcements', 'update')],
        loadComponent: () => import('./features/announcements/announcements.component').then(m => m.AnnouncementsComponent)
      }
    ]
  },



  {
    path: '**',
    redirectTo: 'tasks'
  }
];
```
