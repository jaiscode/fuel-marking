import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { OAuthService } from 'angular-oauth2-oidc';
import { firstValueFrom } from 'rxjs';
import { AuthConfig, Me } from './models';

const LAST_REDIRECT_KEY = 'fm.loginRedirectAt';
/** A 401 this soon after returning from the provider means the API rejects our token: do not loop back to the provider. */
const LOOP_WINDOW_MS = 20_000;
/**
 * Renew the access token when this little time is left, rather than at the edge of expiry (where the server's own clock
 * tolerance means one request could be refused and the person bounced to the login page). The OIDC library's own
 * setting for this is unreliable (it subtracts seconds from a millisecond timestamp), so the app decides itself.
 */
const RENEW_BEFORE_EXPIRY_MS = 30_000;

/**
 * Single sign-on with the organisation's OpenID Connect provider: authorization code flow with PKCE, no client secret.
 * The backend publishes the provider details, so one build serves every environment.
 *
 * Tokens live in sessionStorage (cleared when the tab closes). That is the usual trade-off for a browser-only app:
 * keep the page free of third-party scripts, and consider a backend-for-frontend if your risk assessment requires
 * tokens never to reach the browser.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly oauth = inject(OAuthService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  /** Set when sign-in could not be set up (backend or provider unreachable). */
  readonly error = signal<string | null>(null);
  readonly me = signal<Me | null>(null);
  private returnUrl: string | null = null;

  /** Runs before the app starts: discovers the provider and completes a sign-in that is returning from it. */
  async init(): Promise<void> {
    try {
      const cfg = await firstValueFrom(this.http.get<AuthConfig>('/api/public/auth-config'));
      if (!cfg.issuer) throw new Error('The server has no OIDC provider configured.');
      this.oauth.configure({
        issuer: cfg.issuer,
        clientId: cfg.clientId,
        scope: cfg.scope,
        responseType: 'code',
        redirectUri: window.location.origin + '/',
        postLogoutRedirectUri: window.location.origin + '/signed-out',
        // ID-token checks tolerate a minute of difference between the browser's and the provider's clocks
        clockSkewInSec: 60,
        // some providers (Auth0) need the API audience on the authorization request; others ignore it
        customQueryParams: cfg.audience ? { audience: cfg.audience } : undefined
      });
      await this.oauth.loadDiscoveryDocumentAndTryLogin();
      if (this.oauth.hasValidAccessToken() && this.oauth.state) {
        this.returnUrl = AuthService.safePath(decodeURIComponent(this.oauth.state));
      }
    } catch (e) {
      this.error.set(e instanceof Error ? e.message : 'Sign-in is unavailable.');
    }
  }

  isSignedIn(): boolean { return this.oauth.hasValidAccessToken(); }

  /** Path to resume after returning from the provider; handed out once. */
  takeReturnUrl(): string | null {
    const r = this.returnUrl;
    this.returnUrl = null;
    return r && r !== '/' ? r : null;
  }

  /** Redirects the browser to the provider. */
  login(returnTo: string = '/'): void {
    this.write(LAST_REDIRECT_KEY, String(Date.now()));
    this.oauth.initCodeFlow(AuthService.safePath(returnTo) ?? '/');
  }

  /** True if we only just came back from the provider (so a 401 is a configuration problem, not an expired session). */
  justSignedIn(now = Date.now()): boolean {
    const at = Number(this.read(LAST_REDIRECT_KEY));
    return Number.isFinite(at) && at > 0 && now - at < LOOP_WINDOW_MS;
  }

  accessToken(): string | null { return this.oauth.hasValidAccessToken() ? this.oauth.getAccessToken() : null; }

  /** True while the access token has comfortably more than {@link RENEW_BEFORE_EXPIRY_MS} left. */
  private accessTokenFresh(now = Date.now()): boolean {
    if (!this.oauth.getAccessToken()) return false;
    const expiresAt = this.oauth.getAccessTokenExpiration(); // ms since epoch, from the browser clock + expires_in
    return !expiresAt || expiresAt - now > RENEW_BEFORE_EXPIRY_MS;
  }

  private refreshing: Promise<boolean> | null = null;

  /**
   * Makes sure there is an access token worth sending: renews it with the refresh token shortly before it expires.
   * Resolves false if a new sign-in is needed. Calls made at the same moment share one renewal, because providers that
   * rotate refresh tokens refuse a second use of the same token.
   */
  refreshIfPossible(): Promise<boolean> {
    if (this.accessTokenFresh()) return Promise.resolve(true);
    if (!this.oauth.getRefreshToken()) return Promise.resolve(this.oauth.hasValidAccessToken()); // nothing to renew with: use it while it is valid
    if (!this.refreshing) {
      this.refreshing = this.oauth.refreshToken()
        .then(() => this.oauth.hasValidAccessToken())
        .catch(() => false)
        .finally(() => { this.refreshing = null; });
    }
    return this.refreshing;
  }

  /** Loads (once) who the backend says we are. Rejects with status 403 if we have no role here. */
  async loadMe(force = false): Promise<Me> {
    const cached = this.me();
    if (cached && !force) return cached;
    const me = await firstValueFrom(this.http.get<Me>('/api/web/me'));
    this.me.set(me);
    return me;
  }

  /** Ends the provider session as well when it supports it; otherwise just forgets the tokens. */
  logout(): void {
    this.me.set(null);
    const federated = !!this.oauth.logoutUrl;
    this.oauth.logOut(!federated);
    if (!federated) void this.router.navigate(['/signed-out']);
  }

  /** Same-origin absolute paths only, so a crafted state value cannot send the user to another site. */
  static safePath(p: string | null | undefined): string | null {
    return p && /^\/(?!\/)[^\s\\]*$/.test(p) ? p : null;
  }

  private read(key: string): string | null {
    try { return sessionStorage.getItem(key); } catch { return null; }
  }

  private write(key: string, value: string): void {
    try { sessionStorage.setItem(key, value); } catch { /* the loop guard degrades gracefully without storage */ }
  }
}
