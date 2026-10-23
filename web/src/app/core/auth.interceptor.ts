import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';

/** API calls that need the signed-in user's token (everything under /api/ except the public discovery endpoint). */
const needsToken = (url: string) => url.startsWith('/api/') && !url.startsWith('/api/public/');

/** Attaches the bearer token, recovers from expiry, and turns failures into messages. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!needsToken(req.url)) return next(req);
  const auth = inject(AuthService);
  const toast = inject(ToastService);
  const router = inject(Router);

  return from(auth.refreshIfPossible()).pipe(
    switchMap(ok => {
      const token = ok ? auth.accessToken() : null;
      if (!token) {
        // session over: go back to the provider, keeping the page the user was on
        auth.login(router.url);
        return throwError(() => new HttpErrorResponse({ status: 401, url: req.url, statusText: 'Session expired' }));
      }
      return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
    }),
    catchError((e: HttpErrorResponse) => {
      if (e.status === 401 && e.statusText !== 'Session expired') {
        if (auth.justSignedIn()) {
          // we have a fresh token and the API still says no: a redirect would loop forever
          toast.error('You signed in, but the server rejected your token. Ask an administrator to check the OIDC issuer and audience settings.');
          void router.navigate(['/no-access']);
        } else {
          auth.login(router.url);
        }
      } else if (e.status === 403) {
        toast.error('You do not have permission to do that.');
      } else if (e.status !== 401) {
        toast.error(describe(e));
      }
      return throwError(() => e);
    })
  );
};

function describe(e: HttpErrorResponse): string {
  if (e.status === 0) return 'Cannot reach the server.';
  const body = e.error;
  if (body && typeof body === 'object' && typeof body.error === 'string') return body.error;
  if (e.status === 404) return 'Not found.';
  return `Request failed (${e.status}).`;
}
