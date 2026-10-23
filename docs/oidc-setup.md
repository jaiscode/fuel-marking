# Signing in to the web app (OpenID Connect)

The web app signs people in with **your** identity provider using the authorization-code flow with PKCE. There is no
password in this application any more. The backend only checks the tokens the provider issues.

Marking sites do **not** use this: they authenticate with a per-site API key (`/api/sync/**`) and users sign in on the
desktop with a PIN.

## 1. Configure the provider

Create one **public** client (no secret) for the web app:

| Setting | Value |
|---|---|
| Flow | Authorization code + PKCE (S256) |
| Redirect URI | `https://<your-host>/` (the site root, with the trailing slash) |
| Post-logout redirect URI | `https://<your-host>/signed-out` |
| Web origin / CORS | `https://<your-host>` |
| Scopes | `openid profile email` |

Create two roles and give them to people (or map groups to them):

* `fuelmarking-viewer` - can read all reports.
* `fuelmarking-admin` - can also manage sites, companies, users, PINs and site API keys.

A person with neither role can sign in but sees a "No access" page.

## Keycloak (the provider this was verified with)

`docs/keycloak/fuelmarking-realm.json` is a ready-made realm: the public client `fuelmarking-web` (authorization code with
PKCE **required**), the roles `fuelmarking-admin` and `fuelmarking-viewer`, the API audience `fuelmarking-api` added to
access tokens, and a flat `roles` claim. It contains **no users or passwords**. It was verified against Keycloak 26.8.1 by
`scripts/e2e-keycloak.sh` (below).

1. **Import it.** Admin console -> *Create realm* -> upload the file (or, for a trial: copy it to `data/import/` and run
   `bin/kc.sh start-dev --import-realm`).
2. **Set your address.** In *Clients -> fuelmarking-web* replace every `CHANGE-ME.example.com` in *Valid redirect URIs*
   (`https://<host>/`), *Valid post logout redirect URIs* (`https://<host>/signed-out`) and *Web origins*, and **delete the
   `localhost` entries** in production.
3. **Add people** (*Users*): give each an email, first and last name, a password, and under *Role mapping* assign
   `fuelmarking-viewer` (read reports) or `fuelmarking-admin` (also administer). Someone with neither role can sign in but
   sees "No access". Prefer assigning the roles to **groups** and putting people in groups.
4. **Backend settings:**
   ```
   OIDC_ISSUER_URI=https://<keycloak-host>/realms/fuelmarking
   OIDC_AUDIENCE=fuelmarking-api
   ```
   Nothing else is needed: the realm already emits the flat `roles` claim, which is the default. (Keycloak's own
   `realm_access.roles` claim also works: set `OIDC_ROLES_CLAIM=realm_access.roles`. Both are tested.)

Before production: serve Keycloak over HTTPS (the realm uses `sslRequired: external`), enable MFA for administrators
(*Authentication -> Required actions / flows*), and review the realm's session lifetimes (access token 5 minutes, SSO
session idle 30 minutes, maximum 10 hours; brute-force protection is on). The web app renews access tokens silently about
30 seconds before they expire and signs out of Keycloak too when the person signs out.

### Testing it yourself
```bash
# download and unpack a Keycloak distribution (https://github.com/keycloak/keycloak/releases), then:
KEYCLOAK_HOME=/path/to/keycloak-26.x ./scripts/e2e-keycloak.sh
```
It runs Keycloak from a temporary copy, creates an administrator, a viewer and a person with no role, and drives Chrome
through the real login page: PKCE parameters, wrong password, the right page after sign-in, the roles in the token,
silent renewal of an expiring token, sign-out, the viewer's limits, the no-role page, and the API refusing a valid token
issued for a different application. Allow about five minutes.

## 2. Configure the backend (environment variables)

| Variable | Meaning |
|---|---|
| `OIDC_ISSUER_URI` | **Required.** Issuer URL; `<issuer>/.well-known/openid-configuration` must be reachable from the server. |
| `OIDC_AUDIENCE` | Audience the **access token** is issued for. Strongly recommended; see below. |
| `OIDC_ROLES_CLAIM` | Claim holding the roles (default `roles`). Dotted paths reach nested claims. |
| `OIDC_ADMIN_ROLE` / `OIDC_VIEWER_ROLE` | Role names (defaults above). |
| `OIDC_CLIENT_ID` | Client id of the web client (default `fuelmarking-web`). |

The backend refuses to start without `OIDC_ISSUER_URI`. There is no fallback login.

### Provider notes

| Provider | `OIDC_ISSUER_URI` | `OIDC_ROLES_CLAIM` | Audience |
|---|---|---|---|
| Keycloak | `https://<host>/realms/<realm>` | `roles` (the supplied realm file adds it; or `realm_access.roles`) | `fuelmarking-api` (the supplied realm file adds it) |
| Microsoft Entra ID | `https://login.microsoftonline.com/<tenant>/v2.0` | `roles` (define *App roles* on the app registration) | Expose an API; `OIDC_AUDIENCE=api://<app-id>` or the app id |
| Okta | `https://<org>.okta.com/oauth2/<server>` | `groups` (add a groups claim) | Authorization server audience |
| Auth0 | `https://<tenant>.auth0.com/` | a namespaced claim added by an Action | The API identifier; the app sends it as the `audience` parameter automatically |

### The audience pitfall

The **access token's** `aud` is not always the client id. If the backend answers `401` right after a successful
sign-in, the cause is almost always `OIDC_AUDIENCE` not matching the access token's `aud`. Decode the access token
(for example at jwt.io - use a test token) and set `OIDC_AUDIENCE` to what it contains. The web app then stops on a
"No access" page with an explanation instead of looping between the app and the provider.

## 3. Things to know

* Tokens are kept in `sessionStorage` (gone when the tab closes). Keep third-party scripts off the page. If your
  risk assessment requires tokens never to reach the browser, put a backend-for-frontend in front instead.
* Role changes take effect when the current access token expires (typically minutes).
* Every master-data change (sites, companies, users, PIN resets, API keys, re-send) is written to `audit_log` with
  the signed-in person's name and subject; secrets are never recorded. Admins can read it at `GET /api/admin/audit`.
* Use HTTPS in production. The OIDC library allows plain HTTP only for `localhost`.
