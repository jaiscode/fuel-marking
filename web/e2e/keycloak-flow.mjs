// Drives the real web app through Keycloak's real login page. Run by scripts/e2e-keycloak.sh.
// Environment: APP_URL, KC_URL, CHROME, TOKENS_OUT, USERS (JSON), REFRESH_WAIT_SECONDS
import puppeteer from 'puppeteer-core';
import { writeFileSync } from 'node:fs';

const APP = process.env.APP_URL ?? 'http://localhost:8080';
const KC = process.env.KC_URL ?? 'http://localhost:18081';
const REALM = `${KC}/realms/fuelmarking`;
const USERS = JSON.parse(process.env.USERS);
const WAIT = Number(process.env.REFRESH_WAIT_SECONDS ?? 45);
const sleep = ms => new Promise(r => setTimeout(r, ms));
let passed = 0;
const ok = msg => { passed++; console.log('   ok  ' + msg); };
const check = (cond, msg) => { if (!cond) { console.error('\n!! FAILED: ' + msg); process.exitCode = 1; throw new Error(msg); } ok(msg); };

const browser = await puppeteer.launch({ executablePath: process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: 'new', args: ['--no-sandbox'] });
const decode = jwt => JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString());
const text = (page, sel) => page.$eval(sel, e => e.textContent.replace(/\s+/g, ' ').trim()).catch(() => '');
const nav = page => page.$$eval('nav a', a => a.map(x => x.textContent.trim()));
const newPage = async () => { const c = await browser.createBrowserContext(); const p = await c.newPage(); await p.setViewport({ width: 1360, height: 900 }); return p; };

async function fillKeycloak(page, username, password) {
  await page.waitForSelector('#username', { timeout: 15000 });
  await page.type('#username', username);
  await page.type('#password', password);
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click('#kc-login')]);
}
const tokens = async page => page.evaluate(() => ({ access: sessionStorage.getItem('access_token'), refresh: sessionStorage.getItem('refresh_token'), id: sessionStorage.getItem('id_token') }));

try {
  // ------------------------------------------------------------------ alice (administrator)
  console.log('\n== Administrator signs in');
  const page = await newPage();
  const requests = [];
  const consoleErrors = [];
  page.on('request', r => requests.push({ url: r.url(), method: r.method(), body: r.postData() ?? '' }));
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  await page.goto(APP + '/markings', { waitUntil: 'networkidle0' });
  const auth = new URL(page.url());
  check(page.url().startsWith(`${REALM}/protocol/openid-connect/auth`), 'a signed-out visitor is sent to Keycloak');
  check(auth.searchParams.get('response_type') === 'code' && auth.searchParams.get('code_challenge_method') === 'S256' && !!auth.searchParams.get('code_challenge'),
    'authorization code flow with an S256 PKCE challenge (no client secret, no implicit flow)');
  check(auth.searchParams.get('redirect_uri') === APP + '/' && auth.searchParams.get('scope').includes('openid'), 'redirect URI is the app root and the openid scope is requested');

  await page.type('#username', USERS.admin.username); await page.type('#password', 'definitely-wrong');
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click('#kc-login')]);
  check(page.url().startsWith(REALM) && (await page.$('#input-error, .kc-feedback-text, [class*="error"]')) !== null, 'a wrong password stays on Keycloak and shows an error');
  await page.$eval('#password', e => (e.value = ''));
  await page.type('#password', USERS.admin.password);
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.click('#kc-login')]);
  await sleep(1500);
  check(new URL(page.url()).pathname === '/markings' && !/[?&](code|state|session_state)=/.test(page.url()), 'back in the app on the page that was asked for, with no code or state left in the URL');
  const links = await nav(page);
  check(['Dashboard', 'Markings', 'Sites', 'Oil companies', 'Users'].every(l => links.includes(l)), 'the administrator sees the Administration menu: ' + links.join(' | '));
  check((await text(page, '.who')).includes('Alice Admin') && (await text(page, '.who .role')) === 'Administrator', 'the header shows the person\'s name and role');

  const t = await tokens(page);
  const claims = decode(t.access);
  check(claims.iss === REALM && claims.azp === 'fuelmarking-web', 'the access token comes from the realm for the web client');
  check(claims.exp - claims.iat === 60, `the access token lives 60 s as configured for this test (it is ${claims.exp - claims.iat} s)`);
  check([].concat(claims.aud).includes('fuelmarking-api'), 'the access token is issued for the API audience (aud contains fuelmarking-api)');
  check(claims.roles?.includes('fuelmarking-admin') && claims.realm_access?.roles?.includes('fuelmarking-admin'), 'roles are present both as a flat "roles" claim and as realm_access.roles');
  check(!!t.refresh && (await page.evaluate(() => Object.keys(localStorage).length)) === 0, 'a refresh token was issued; nothing is kept in localStorage');

  await page.goto(APP + '/admin/users', { waitUntil: 'networkidle0' });
  check((await text(page, 'h1')) === 'Users' && (await page.$$eval('button', b => b.some(x => x.textContent.includes('New user')))) && (await text(page, '.toast')) === '',
    'the administrator can open the Users page (heading and "New user" button, no error)');
  await page.reload({ waitUntil: 'networkidle0' });
  check(new URL(page.url()).pathname === '/admin/users', 'a page reload keeps the session');

  // ------------------------------------------------------------------ token refresh
  console.log(`\n== Token refresh (waiting ${WAIT}s: the app treats a 60 s token as expired 30 s early, so it renews before the server would refuse it)`);
  const before = (await tokens(page)).access;
  await sleep(WAIT * 1000);
  const mark = requests.length;
  await page.evaluate(() => { history.pushState({}, '', '/markings'); dispatchEvent(new PopStateEvent('popstate')); });
  await sleep(4000);
  const refreshCalls = requests.slice(mark).filter(r => r.url === `${REALM}/protocol/openid-connect/token` && r.body.includes('grant_type=refresh_token'));
  if (refreshCalls.length === 0) {
    console.log('       requests after the wait:', JSON.stringify(requests.slice(mark).map(r => `${r.method} ${r.url.replace(APP, '').replace(KC, 'KC')}`)));
    console.log('       page is at:', page.url().replace(APP, '').slice(0, 120), '| console errors:', JSON.stringify(consoleErrors.slice(-3)));
  }
  check(refreshCalls.length >= 1, 'the expired access token was renewed silently with the refresh token');
  check(!page.url().startsWith(REALM) && (await page.$$eval('tbody tr', r => r.length)) > 0 || (await text(page, 'main')).length > 0, 'the page kept working without sending the person back to the login page');
  const after = (await tokens(page)).access;
  check(after && after !== before, 'a new access token replaced the old one');
  const apiAfter = await page.evaluate(async tok => (await fetch('/api/web/overview', { headers: { Authorization: 'Bearer ' + tok } })).status, after);
  check(apiAfter === 200, 'the renewed token is accepted by the API');

  // ------------------------------------------------------------------ sign out
  console.log('\n== Sign out');
  writeFileSync(process.env.TOKENS_OUT, JSON.stringify({ admin: after }));
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle0' }), page.$$eval('button', b => b.find(x => x.textContent.includes('Sign out')).click())]);
  await sleep(1000);
  check(new URL(page.url()).pathname === '/signed-out' && (await text(page, 'h1')) === 'Signed out', 'signing out ends the Keycloak session and returns to the signed-out page');
  check(Object.keys(await page.evaluate(() => ({ ...sessionStorage }))).filter(k => k.includes('token')).length === 0, 'no tokens are left in the browser');
  await page.goto(APP + '/', { waitUntil: 'networkidle0' });
  check(page.url().startsWith(REALM) && (await page.$('#username')) !== null, 'visiting the app again asks for credentials (the single sign-on session really ended)');

  // ------------------------------------------------------------------ victor (viewer)
  console.log('\n== Viewer signs in');
  const v = await newPage();
  await v.goto(APP + '/', { waitUntil: 'networkidle0' });
  await fillKeycloak(v, USERS.viewer.username, USERS.viewer.password);
  await sleep(1500);
  const vlinks = await nav(v);
  check(vlinks.includes('Markings') && !vlinks.includes('Users') && !vlinks.includes('Sites'), 'the viewer sees reports but no Administration menu: ' + vlinks.join(' | '));
  check((await text(v, '.who .role')) === 'Viewer', 'the header says Viewer');
  await v.goto(APP + '/markings', { waitUntil: 'networkidle0' });
  check(!(await text(v, '.toast')).includes('permission') && (await v.$$eval('#s option', o => o.length)) >= 1, 'report pages load for a viewer without permission errors');
  await v.goto(APP + '/admin/users', { waitUntil: 'networkidle0' });
  await sleep(800);
  check(new URL(v.url()).pathname === '/' && (await text(v, '.toast')).includes('administrator'), 'opening an admin page as a viewer bounces back with an explanation');
  const vt = await tokens(v);
  const direct = await v.evaluate(async tok => ({
    admin: (await fetch('/api/admin/users', { headers: { Authorization: 'Bearer ' + tok } })).status,
    pin: (await fetch('/api/admin/users/00000000-0000-0000-0000-000000000000/pin', { method: 'PUT', headers: { Authorization: 'Bearer ' + tok, 'Content-Type': 'application/json' }, body: '{"pin":"246810"}' })).status,
    read: (await fetch('/api/web/overview', { headers: { Authorization: 'Bearer ' + tok } })).status
  }), vt.access);
  check(direct.admin === 403 && direct.pin === 403 && direct.read === 200, `a viewer calling the API directly: admin list ${direct.admin}, set PIN ${direct.pin}, reports ${direct.read}`);
  const all = JSON.parse((await import('node:fs')).readFileSync(process.env.TOKENS_OUT, 'utf8'));
  all.viewer = vt.access;
  writeFileSync(process.env.TOKENS_OUT, JSON.stringify(all));

  // ------------------------------------------------------------------ nina (no role)
  console.log('\n== A person with no role signs in');
  const n = await newPage();
  await n.goto(APP + '/', { waitUntil: 'networkidle0' });
  await fillKeycloak(n, USERS.norole.username, USERS.norole.password);
  await sleep(1500);
  check(new URL(n.url()).pathname === '/no-access' && (await text(n, 'h1')) === 'No access', 'signed in but without a role: the "No access" page, not an empty app');
  const nt = await tokens(n);
  const noRoleApi = await n.evaluate(async tok => (await fetch('/api/web/overview', { headers: { Authorization: 'Bearer ' + tok } })).status, nt.access);
  check(noRoleApi === 403, 'and the API refuses them (403)');
  console.log(`\n${passed} browser checks passed`);
} finally {
  await browser.close();
}
