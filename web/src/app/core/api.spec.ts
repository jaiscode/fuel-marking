import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, RouterStateSnapshot, ActivatedRouteSnapshot, UrlTree, provideRouter } from '@angular/router';
import { OAuthService } from 'angular-oauth2-oidc';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { adminGuard, authGuard } from './auth.guard';
import { authInterceptor } from './auth.interceptor';
import { Me } from './models';
import { ToastService } from './toast.service';

/** Stands in for the OIDC client library so tests can control token state. */
class FakeOAuth {
  valid = true;
  token = 'access-1';
  expiresAt = Date.now() + 3_600_000;   // ms since epoch
  slowRefresh = false;
  refresh: string | null = null;
  refreshed = 0;
  logins: string[] = [];
  configured: Record<string, unknown> | null = null;
  state: string | null = null;
  logoutUrl = '';
  hasValidAccessToken() { return this.valid; }
  getAccessToken() { return this.token; }
  getRefreshToken() { return this.refresh; }
  getAccessTokenExpiration() { return this.valid ? this.expiresAt : Date.now() - 1_000; }  // an invalid token is an expired one
  async refreshToken() {
    this.refreshed++;
    if (this.slowRefresh) await new Promise(r => setTimeout(r, 30));
    this.valid = true; this.token = 'access-2'; this.expiresAt = Date.now() + 3_600_000;
  }
  initCodeFlow(state?: string) { this.logins.push(state ?? ''); }
  configure(c: Record<string, unknown>) { this.configured = c; }
  loadDiscoveryDocumentAndTryLogin() { return Promise.resolve(true); }
  logOut() { /* noop */ }
}

const me = (admin: boolean): Me => ({ subject: 's', name: 'Test User', email: null, admin, roles: [admin ? 'admin' : 'viewer'] });

describe('OIDC client', () => {
  let http: HttpTestingController;
  let api: ApiService;
  let oauth: FakeOAuth;
  let auth: AuthService;
  let toast: ToastService;
  let router: Router;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'no-access', children: [] }, { path: 'signed-out', children: [] }, { path: 'markings', children: [] }]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: OAuthService, useClass: FakeOAuth }
      ]
    });
    http = TestBed.inject(HttpTestingController);
    api = TestBed.inject(ApiService);
    oauth = TestBed.inject(OAuthService) as unknown as FakeOAuth;
    auth = TestBed.inject(AuthService);
    toast = TestBed.inject(ToastService);
    router = TestBed.inject(Router);
  });

  afterEach(() => http.verify());

  const tick = () => new Promise(r => setTimeout(r));

  it('drops empty filters but keeps zero', async () => {
    api.markings({ q: '', siteId: null, fuel: undefined, status: 'DOSED', page: 0, size: 25 }).subscribe();
    await tick();
    const req = http.expectOne(r => r.url === '/api/web/markings');
    expect(req.request.params.keys().sort()).toEqual(['page', 'size', 'status']);
    expect(req.request.params.get('page')).toBe('0');
    req.flush({ items: [], total: 0, page: 0, size: 25 });
  });

  it('sends the bearer token to the API but not to the public discovery endpoint or other URLs', async () => {
    api.siteStatus().subscribe();
    await tick();
    expect(http.expectOne('/api/web/sites/status').request.headers.get('Authorization')).toBe('Bearer access-1');

    api.authConfig().subscribe();
    expect(http.expectOne('/api/public/auth-config').request.headers.has('Authorization')).toBeFalse();

    TestBed.inject(HttpClient).get('/assets/x.json').subscribe();
    expect(http.expectOne('/assets/x.json').request.headers.has('Authorization')).toBeFalse();
  });

  it('refreshes an expired access token before calling the API', async () => {
    oauth.valid = false;
    oauth.refresh = 'refresh-1';
    api.siteStatus().subscribe();
    await tick();
    expect(oauth.refreshed).toBe(1);
    expect(http.expectOne('/api/web/sites/status').request.headers.get('Authorization')).toBe('Bearer access-2');
  });

  it('renews a token that is about to expire, but leaves a healthy one alone', async () => {
    oauth.refresh = 'refresh-1';
    api.siteStatus().subscribe();
    await tick();
    expect(oauth.refreshed).toBe(0);
    expect(http.expectOne('/api/web/sites/status').request.headers.get('Authorization')).toBe('Bearer access-1');

    oauth.expiresAt = Date.now() + 20_000;                // still valid, but under the 30 s renewal margin
    api.siteStatus().subscribe();
    await tick();
    expect(oauth.refreshed).toBe(1);
    expect(http.expectOne('/api/web/sites/status').request.headers.get('Authorization')).toBe('Bearer access-2');
  });

  it('shares one renewal between calls made at the same moment', async () => {
    oauth.refresh = 'refresh-1';
    oauth.expiresAt = Date.now() + 5_000;
    oauth.slowRefresh = true;
    for (let i = 0; i < 4; i++) api.siteStatus().subscribe();      // e.g. a dashboard firing four requests at once
    await new Promise(r => setTimeout(r, 120));
    expect(oauth.refreshed).toBe(1);
    const sent = http.match('/api/web/sites/status');
    expect(sent.length).toBe(4);
    sent.forEach(r => expect(r.request.headers.get('Authorization')).toBe('Bearer access-2'));
  });

  it('uses a token that is near expiry when there is no refresh token to renew it with', async () => {
    oauth.refresh = null;
    oauth.expiresAt = Date.now() + 5_000;
    api.siteStatus().subscribe();
    await tick();
    expect(oauth.refreshed).toBe(0);
    expect(http.expectOne('/api/web/sites/status').request.headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('goes back to the provider, without calling the API, when the session cannot be renewed', async () => {
    oauth.valid = false;
    spyOnProperty(router, 'url', 'get').and.returnValue('/markings?q=1');
    let failed = false;
    api.siteStatus().subscribe({ error: () => (failed = true) });
    await tick();
    http.expectNone('/api/web/sites/status');
    expect(failed).toBeTrue();
    expect(oauth.logins).toEqual(['/markings?q=1']);
  });

  it('re-authenticates when the API answers 401 for an old session', async () => {
    spyOnProperty(router, 'url', 'get').and.returnValue('/markings');
    api.overview({}).subscribe({ error: () => undefined });
    await tick();
    http.expectOne(r => r.url === '/api/web/overview').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(oauth.logins).toEqual(['/markings']);
  });

  it('does not loop between the app and the provider when a fresh token is rejected', async () => {
    auth.login('/'); // records "we just went to the provider"
    oauth.logins.length = 0;
    api.overview({}).subscribe({ error: () => undefined });
    await tick();
    http.expectOne(r => r.url === '/api/web/overview').flush(null, { status: 401, statusText: 'Unauthorized' });
    await tick();
    expect(oauth.logins).toEqual([]);
    expect(toast.current()?.text).toContain('server rejected your token');
    expect(router.url).toBe('/no-access');
  });

  it('explains permission and other failures', async () => {
    api.saveSite({ code: 'X', name: 'X' }).subscribe({ error: () => undefined });
    await tick();
    http.expectOne('/api/admin/sites').flush({ error: 'Code or username already exists' }, { status: 409, statusText: 'Conflict' });
    expect(toast.current()?.text).toBe('Code or username already exists');

    api.saveSite({ code: 'Y', name: 'Y' }).subscribe({ error: () => undefined });
    await tick();
    http.expectOne('/api/admin/sites').flush(null, { status: 403, statusText: 'Forbidden' });
    expect(toast.current()?.text).toBe('You do not have permission to do that.');

    api.siteStatus().subscribe({ error: () => undefined });
    await tick();
    http.expectOne('/api/web/sites/status').error(new ProgressEvent('error'));
    expect(toast.current()?.text).toBe('Cannot reach the server.');
  });

  describe('sign-in set-up', () => {
    it('configures the code flow from the server-published settings', async () => {
      const done = auth.init();
      http.expectOne('/api/public/auth-config').flush({ issuer: 'https://idp.example/realms/fm', clientId: 'web', scope: 'openid profile', audience: 'fm-api' });
      await done;
      expect(auth.error()).toBeNull();
      expect(oauth.configured).toEqual(jasmine.objectContaining({
        issuer: 'https://idp.example/realms/fm', clientId: 'web', responseType: 'code', scope: 'openid profile',
        clockSkewInSec: 60,
        customQueryParams: { audience: 'fm-api' }
      }));
      expect(String(oauth.configured?.['redirectUri'])).toBe(window.location.origin + '/');
    });

    it('reports an error page state when no provider is configured or the server is down', async () => {
      let done = auth.init();
      http.expectOne('/api/public/auth-config').flush({ issuer: '', clientId: 'web', scope: 'openid' });
      await done;
      expect(auth.error()).toContain('no OIDC provider');

      done = auth.init();
      http.expectOne('/api/public/auth-config').error(new ProgressEvent('error'));
      await done;
      expect(auth.error()).toBeTruthy();
    });

    it('only resumes same-site paths after sign-in (no open redirect)', () => {
      expect(AuthService.safePath('/markings?q=1')).toBe('/markings?q=1');
      for (const bad of ['//evil.example', 'https://evil.example', '/\\evil.example', 'javascript:alert(1)', '', null, undefined, '/a b'])
        expect(AuthService.safePath(bad as string | null)).toBeNull();
    });
  });

  describe('route guards', () => {
    const state = (url: string) => ({ url }) as RouterStateSnapshot;
    const route = {} as ActivatedRouteSnapshot;
    const run = <T>(f: () => T) => TestBed.runInInjectionContext(f);

    it('sends a signed-out visitor to the provider and remembers where they were going', async () => {
      oauth.valid = false;
      expect(await run(() => authGuard(route, state('/transfers')))).toBeFalse();
      expect(oauth.logins).toEqual(['/transfers']);
    });

    it('lets a signed-in user with a role through', async () => {
      const result = run(() => authGuard(route, state('/')));
      await tick();
      http.expectOne('/api/web/me').flush(me(false));
      expect(await result).toBeTrue();
    });

    it('shows the no-access page to a signed-in user without a role', async () => {
      const result = run(() => authGuard(route, state('/')));
      await tick();
      http.expectOne('/api/web/me').flush(null, { status: 403, statusText: 'Forbidden' });
      expect(((await result) as UrlTree).toString()).toBe('/no-access');
    });

    it('shows the error page if sign-in could not be set up', async () => {
      auth.error.set('boom');
      expect(((await run(() => authGuard(route, state('/')))) as UrlTree).toString()).toBe('/auth-error');
    });

    it('keeps non-admins out of administration', () => {
      auth.me.set(me(false));
      expect(run(() => adminGuard(route, state('/admin/users'))) instanceof UrlTree).toBeTrue();
      expect(toast.current()?.text).toContain('administrator role');
      auth.me.set(me(true));
      expect(run(() => adminGuard(route, state('/admin/users')))).toBeTrue();
    });
  });
});
