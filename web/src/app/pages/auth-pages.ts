import { Component, inject } from '@angular/core';
import { AuthService } from '../core/auth.service';

@Component({
  selector: 'app-signed-out',
  standalone: true,
  template: `
    <main class="wrap"><section class="card">
      <h1>Signed out</h1>
      <p class="muted">You have been signed out of Fuel Marking.</p>
      <button class="primary" type="button" (click)="auth.login('/')">Sign in again</button>
    </section></main>`,
  styles: [`.wrap { min-height: 100vh; display: grid; place-items: center; padding: 16px; } section { width: min(420px, 100%); display: flex; flex-direction: column; gap: 12px; padding: 28px; }`]
})
export class SignedOutComponent { readonly auth = inject(AuthService); }

@Component({
  selector: 'app-no-access',
  standalone: true,
  template: `
    <main class="wrap"><section class="card">
      <h1>No access</h1>
      <p>You are signed in{{ name() ? ' as ' + name() : '' }}, but your account has not been given a role for this application.</p>
      <p class="muted">Ask an administrator to assign you the <b>viewer</b> or <b>administrator</b> role, then sign in again.</p>
      <button class="primary" type="button" (click)="auth.logout()">Sign out</button>
    </section></main>`,
  styles: [`.wrap { min-height: 100vh; display: grid; place-items: center; padding: 16px; } section { width: min(480px, 100%); display: flex; flex-direction: column; gap: 12px; padding: 28px; } p { margin: 0; }`]
})
export class NoAccessComponent {
  readonly auth = inject(AuthService);
  name = () => this.auth.me()?.name ?? '';
}

@Component({
  selector: 'app-auth-error',
  standalone: true,
  template: `
    <main class="wrap"><section class="card">
      <h1>Sign-in is unavailable</h1>
      <p class="error-text" role="alert">{{ auth.error() || 'Something went wrong while contacting the server.' }}</p>
      <p class="muted">Check that the server and the identity provider are reachable, then try again.</p>
      <button class="primary" type="button" (click)="reload()">Try again</button>
    </section></main>`,
  styles: [`.wrap { min-height: 100vh; display: grid; place-items: center; padding: 16px; } section { width: min(480px, 100%); display: flex; flex-direction: column; gap: 12px; padding: 28px; } p { margin: 0; }`]
})
export class AuthErrorComponent {
  readonly auth = inject(AuthService);
  reload(): void { window.location.assign('/'); }
}
