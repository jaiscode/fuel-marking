import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="layout">
      <nav class="side" aria-label="Main">
        <div class="brand">Fuel Marking</div>
        <div class="group">Operations</div>
        @for (l of operations; track l.path) {
          <a [routerLink]="l.path" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: l.path === '/' }">{{ l.label }}</a>
        }
        @if (auth.me()?.admin) {
          <div class="group">Administration</div>
          @for (l of admin; track l.path) {
            <a [routerLink]="l.path" routerLinkActive="active">{{ l.label }}</a>
          }
        }
        <div class="spacer"></div>
        <div class="who">
          <span class="muted">Signed in as</span>
          <strong>{{ auth.me()?.name }}</strong>
          <span class="muted role">{{ auth.me()?.admin ? 'Administrator' : 'Viewer' }}</span>
          <button type="button" class="link" (click)="auth.logout()">Sign out</button>
        </div>
      </nav>
      <main class="content"><router-outlet /></main>
    </div>
    @if (toast.current(); as t) {
      <div class="toast" [class.err]="t.kind === 'error'" [attr.role]="t.kind === 'error' ? 'alert' : 'status'">
        {{ t.text }} <button type="button" class="link" (click)="toast.dismiss()" aria-label="Dismiss">✕</button>
      </div>
    }
  `,
  styles: [`
    .layout { display: grid; grid-template-columns: 220px 1fr; min-height: 100vh; }
    .side { background: linear-gradient(180deg, var(--nav), var(--nav-2)); color: var(--nav-text); padding: 18px 12px; display: flex; flex-direction: column; gap: 2px; position: sticky; top: 0; height: 100vh; }
    .brand { font-weight: 700; font-size: 18px; padding: 4px 10px 16px; color: #fff; }
    .group { font-size: 11px; text-transform: uppercase; letter-spacing: 0.06em; color: #8da4d0; padding: 16px 10px 6px; }
    .side a { padding: 9px 12px; border-radius: 10px; color: var(--nav-text); text-decoration: none; }
    .side a:hover { background: rgba(255,255,255,.08); color: #fff; }
    .side a.active { background: #fff; color: var(--nav-2); font-weight: 650; }
    .spacer { flex: 1; }
    .who { display: flex; flex-direction: column; gap: 2px; padding: 10px; border-top: 1px solid rgba(255,255,255,.15); font-size: 13px; align-items: flex-start; color: #fff; }
    .who .muted { color: var(--nav-text); }
    .who button.link { color: #fff; text-decoration: underline; padding-left: 0; }
    .role { font-size: 12px; }
    .content { padding: 24px; min-width: 0; max-width: 1400px; width: 100%; }
    .toast { position: fixed; right: 16px; bottom: 16px; background: var(--surface); border: 1px solid var(--border); border-left: 4px solid var(--accent); padding: 10px 14px; border-radius: 8px; box-shadow: 0 6px 20px rgba(0,0,0,.18); max-width: 420px; z-index: 10; }
    .toast.err { border-left-color: var(--danger); }
    @media (max-width: 800px) {
      .layout { grid-template-columns: 1fr; }
      .side { position: static; height: auto; flex-direction: row; flex-wrap: wrap; gap: 4px; }
      .group, .brand, .spacer { display: none; }
      .who { border: none; flex-direction: row; gap: 8px; align-items: center; }
      .content { padding: 14px; }
    }
  `]
})
export class ShellComponent {
  readonly auth = inject(AuthService);
  readonly toast = inject(ToastService);
  readonly operations = [
    { path: '/', label: 'Dashboard' }, { path: '/markings', label: 'Markings' }, { path: '/inventory', label: 'Inventory' },
    { path: '/incidents', label: 'Incidents' }, { path: '/shifts', label: 'Shifts' }, { path: '/transfers', label: 'Transfers' },
    { path: '/stock-counts', label: 'Stock counts' }
  ];
  readonly admin = [
    { path: '/admin/sites', label: 'Sites' }, { path: '/admin/omcs', label: 'Oil companies' }, { path: '/admin/fuel-types', label: 'Fuel types' },
    { path: '/admin/container-types', label: 'Container types' }, { path: '/admin/rum-types', label: 'Rum types' }, { path: '/admin/jerricans', label: 'Jerricans' }, { path: '/admin/users', label: 'Users' }
  ];
}
