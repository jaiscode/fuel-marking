import { Routes } from '@angular/router';
import { adminGuard, authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: 'signed-out', loadComponent: () => import('./pages/auth-pages').then(m => m.SignedOutComponent), title: 'Signed out' },
  { path: 'no-access', loadComponent: () => import('./pages/auth-pages').then(m => m.NoAccessComponent), title: 'No access' },
  { path: 'auth-error', loadComponent: () => import('./pages/auth-pages').then(m => m.AuthErrorComponent), title: 'Sign-in unavailable' },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/shell.component').then(m => m.ShellComponent),
    children: [
      { path: '', pathMatch: 'full', loadComponent: () => import('./pages/dashboard.component').then(m => m.DashboardComponent), title: 'Dashboard' },
      { path: 'markings', loadComponent: () => import('./pages/markings.component').then(m => m.MarkingsComponent), title: 'Markings' },
      { path: 'markings/:id', loadComponent: () => import('./pages/marking-detail.component').then(m => m.MarkingDetailComponent), title: 'Marking' },
      { path: 'inventory', loadComponent: () => import('./pages/inventory.component').then(m => m.InventoryComponent), title: 'Inventory' },
      { path: 'incidents', loadComponent: () => import('./pages/incidents.component').then(m => m.IncidentsComponent), title: 'Incidents' },
      { path: 'shifts', loadComponent: () => import('./pages/shifts.component').then(m => m.ShiftsComponent), title: 'Shifts' },
      { path: 'stock-counts', loadComponent: () => import('./pages/stock-counts.component').then(m => m.StockCountsComponent), title: 'Stock counts' },
      { path: 'transfers', loadComponent: () => import('./pages/transfers.component').then(m => m.TransfersComponent), title: 'Transfers' },
      {
        path: 'admin',
        canActivate: [adminGuard],
        children: [
          { path: 'sites', loadComponent: () => import('./pages/admin/sites.component').then(m => m.AdminSitesComponent), title: 'Sites' },
          { path: 'omcs', loadComponent: () => import('./pages/admin/omcs.component').then(m => m.AdminOmcsComponent), title: 'Oil companies' },
          { path: 'fuel-types', data: { kind: 'fuel' }, loadComponent: () => import('./pages/admin/code-list.component').then(m => m.AdminCodeListComponent), title: 'Fuel types' },
          { path: 'container-types', loadComponent: () => import('./pages/admin/container-types.component').then(m => m.AdminContainerTypesComponent), title: 'Container types' },
          { path: 'rum-types', data: { kind: 'rum' }, loadComponent: () => import('./pages/admin/code-list.component').then(m => m.AdminCodeListComponent), title: 'Rum types' },
          { path: 'jerricans', loadComponent: () => import('./pages/admin/jerricans.component').then(m => m.AdminJerricansComponent), title: 'Jerricans' },
          { path: 'users', loadComponent: () => import('./pages/admin/users.component').then(m => m.AdminUsersComponent), title: 'Users' }
        ]
      }
    ]
  },
  { path: '**', redirectTo: '' }
];
