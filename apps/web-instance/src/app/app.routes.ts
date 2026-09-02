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
