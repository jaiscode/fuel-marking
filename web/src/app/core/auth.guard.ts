import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';

/** Signed in (else off to the provider) and holding at least a viewer role. */
export const authGuard: CanActivateFn = async (_route, state): Promise<boolean | UrlTree> => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.error()) return router.createUrlTree(['/auth-error']);

  if (!auth.isSignedIn() && !(await auth.refreshIfPossible())) {
    auth.login(state.url);
    return false; // the browser is leaving for the provider
  }
  const resume = auth.takeReturnUrl();
  if (resume) return router.parseUrl(resume);

  try {
    await auth.loadMe();
    return true;
  } catch (e) {
    if (e instanceof HttpErrorResponse && e.status === 403) return router.createUrlTree(['/no-access']);
    if (e instanceof HttpErrorResponse && e.status === 401) return router.createUrlTree(['/no-access']); // handled by the interceptor
    return router.createUrlTree(['/auth-error']);
  }
};

/** Administration pages: admins only (the API enforces this too; this just avoids showing dead ends). */
export const adminGuard: CanActivateFn = (): boolean | UrlTree => {
  const auth = inject(AuthService);
  if (auth.me()?.admin) return true;
  inject(ToastService).error('Administration needs the administrator role.');
  return inject(Router).createUrlTree(['/']);
};
