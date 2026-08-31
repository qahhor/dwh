import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login.component').then(m => m.LoginComponent)
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/shell.component').then(m => m.ShellComponent),
    children: [
      { path: 'fleet', loadComponent: () => import('./pages/fleet.component').then(m => m.FleetComponent) },
      { path: 'clients', loadComponent: () => import('./pages/clients.component').then(m => m.ClientsComponent) },
      { path: 'modules', loadComponent: () => import('./pages/modules.component').then(m => m.ModulesComponent) },
      { path: 'backups', loadComponent: () => import('./pages/backups.component').then(m => m.BackupsComponent) },
      { path: 'announcements', loadComponent: () => import('./pages/announcements.component').then(m => m.AnnouncementsComponent) },
      { path: '', pathMatch: 'full', redirectTo: 'fleet' }
    ]
  },
  { path: '**', redirectTo: '' }
];
