import { expect, test, type Page, type Route } from '@playwright/test';

type ApiState = {
  authenticated: boolean;
  expireAllProtected: boolean;
  expirePath: string | null;
  failedLoginMessage: string | null;
  expiredRequests: string[];
  loginRequests: number;
  concurrentBarrier: ConcurrentBarrier | null;
};

type ConcurrentBarrier = {
  target: number;
  arrived: number;
  promise: Promise<void>;
  release: () => void;
};

test.describe('AUTH-003 expired-session browser contract', () => {
  test('AUTH-003-01: one expired action redirects once and preserves path plus query', async ({ page }) => {
    const state = newApiState();
    await installApiContract(page, state);
    await installNotificationCounter(page);

    const loginRequests = trackLoginDocumentRequests(page);
    const originalPath = '/synthetic?view=build&request=auth003-single';
    await page.goto(originalPath);
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toBeVisible();

    state.expirePath = '/api/synthetic/generators';
    await page.reload();
    await page.waitForURL(/\/login\?next=/);

    expect(new URL(page.url()).searchParams.get('next')).toBe(originalPath);
    expect(loginRequests).toHaveLength(1);
    expect(state.expiredRequests).toEqual(['/api/synthetic/generators']);
  });

  test('AUTH-003-02: five concurrent 401 responses cause one redirect and zero notification storm', async ({ page }) => {
    const state = newApiState();
    await installApiContract(page, state);
    await installNotificationCounter(page);

    const originalPath = '/synthetic?view=build&request=auth003';
    await page.goto(originalPath);
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toBeVisible();

    const loginRequests = trackLoginDocumentRequests(page);
    state.concurrentBarrier = createBarrier(5);
    state.expireAllProtected = true;
    await page.reload();
    await page.waitForURL(/\/login\?next=/);

    expect(state.expiredRequests.length).toBeGreaterThanOrEqual(5);
    expect(new Set(state.expiredRequests).size).toBeGreaterThanOrEqual(5);
    expect(loginRequests).toHaveLength(1);
    expect(new URL(page.url()).searchParams.get('next')).toBe(originalPath);
    expect(await maxNotificationCount(page)).toBe(0);
  });

  test('AUTH-003-03: successful sign-in returns to the exact same-origin path and query', async ({ page }) => {
    const state = newApiState();
    await installApiContract(page, state);

    const originalPath = '/synthetic?view=saved&request=customer-360';
    await page.goto(originalPath);
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toBeVisible();

    state.expirePath = '/api/synthetic/generators';
    await page.reload();
    await page.waitForURL(/\/login\?next=/);
    expect(new URL(page.url()).searchParams.get('next')).toBe(originalPath);

    await signIn(page);
    await page.waitForURL((url) => url.pathname + url.search === originalPath);
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toBeVisible();
    expect(state.loginRequests).toBe(1);
    expect(new URL(page.url()).pathname + new URL(page.url()).search).toBe(originalPath);
  });

  test('AUTH-003-04: unsafe next targets always land on the application default route', async ({ page }) => {
    test.slow();
    const state = newApiState();
    state.authenticated = false;
    await installApiContract(page, state);

    const unsafeTargets = [
      'https://evil.example/steal',
      '//evil.example/steal',
      '/\\evil.example/steal',
      'javascript:alert(1)',
      'data:text/html,unsafe'
    ];

    for (const unsafeTarget of unsafeTargets) {
      state.authenticated = false;
      await page.goto(`/login?next=${encodeURIComponent(unsafeTarget)}`);
      await submitLogin(page);
      await page.waitForURL((url) => url.pathname === '/datascope');
      await expect(page.getByRole('heading', { name: 'DataScope', exact: true })).toBeVisible();
      expect(new URL(page.url()).origin).toBe('http://127.0.0.1:3103');
      expect(new URL(page.url()).pathname).toBe('/datascope');
    }
  });

  test('AUTH-003-05: an expiry redirect cannot silently discard a dirty synthetic draft', async ({ page }) => {
    const state = newApiState();
    await installApiContract(page, state);

    await page.goto('/synthetic?view=build&request=auth003-draft');
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toBeVisible();
    const dataset = page.getByLabel('Dataset');
    await dataset.fill('auth003-unsaved-dataset');

    let dialogType = '';
    const dialogObserved = new Promise<void>((resolve) => {
      page.once('dialog', async (dialog) => {
        dialogType = dialog.type();
        await dialog.dismiss();
        resolve();
      });
    });

    state.expireAllProtected = true;
    await page.getByRole('button', { name: 'Refresh synthetic workspace' }).click();
    await dialogObserved;

    expect(dialogType).toBe('beforeunload');
    expect(new URL(page.url()).pathname).toBe('/synthetic');
    await expect(dataset).toHaveValue('auth003-unsaved-dataset');

    state.expireAllProtected = false;
    await page.waitForTimeout(1_700);
    let retryDialogType = '';
    const retryDialogObserved = new Promise<void>((resolve) => {
      page.once('dialog', async (dialog) => {
        retryDialogType = dialog.type();
        await dialog.dismiss();
        resolve();
      });
    });
    state.expireAllProtected = true;
    await page.getByRole('button', { name: 'Refresh synthetic workspace' }).click();
    await retryDialogObserved;

    expect(retryDialogType).toBe('beforeunload');
    expect(new URL(page.url()).pathname).toBe('/synthetic');
    await expect(dataset).toHaveValue('auth003-unsaved-dataset');
  });

  test('AUTH-003-06: login 401 shows one actionable error and refresh never recurses', async ({ page }) => {
    const state = newApiState();
    state.authenticated = false;
    state.failedLoginMessage = 'Invalid username or password. Check your credentials and try again.';
    await installApiContract(page, state);

    const loginDocumentRequests: string[] = [];
    page.on('request', (request) => {
      if (request.isNavigationRequest() && new URL(request.url()).pathname === '/login') {
        loginDocumentRequests.push(request.url());
      }
    });

    await page.goto('/login?next=%2Fsynthetic%3Fview%3Dsaved');
    await submitLogin(page);
    await expect(page.getByText(state.failedLoginMessage, { exact: true })).toHaveCount(1);
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeEnabled();
    expect(new URL(page.url()).pathname).toBe('/login');

    await page.reload();
    await expect(page.getByRole('heading', { name: 'Sign in to ForgeTDM' })).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/login');
    await submitLogin(page);
    await expect(page.getByText(state.failedLoginMessage, { exact: true })).toHaveCount(1);

    expect(state.loginRequests).toBe(2);
    expect(loginDocumentRequests).toHaveLength(2);
    expect(loginDocumentRequests.every((url) => new URL(url).pathname === '/login')).toBe(true);
  });

  test('AUTH-003-08: Back cannot reveal protected content after the session becomes logged out', async ({ page }) => {
    const state = newApiState();
    await installApiContract(page, state);
    await installProtectedContentExposureGuard(page, 'Synthetic Data Generation');

    const protectedPath = '/synthetic?view=history&request=auth003-back';
    await page.goto(protectedPath);
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toBeVisible();

    state.expirePath = '/api/synthetic/generators';
    await page.reload();
    await page.waitForURL(/\/login\?next=/);
    await signIn(page);
    await page.waitForURL((url) => url.pathname + url.search === protectedPath);
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toBeVisible();

    await page.getByRole('button', { name: 'AUTH-003 Browser User' }).click();
    await page.getByRole('menuitem', { name: 'Sign out' }).click();
    await page.waitForURL((url) => url.pathname === '/login');
    await page.evaluate(() => {
      sessionStorage.setItem('auth003.protectedExposureArmed', '1');
      sessionStorage.setItem('auth003.protectedExposureObserved', '0');
    });
    await page.goBack({ waitUntil: 'domcontentloaded' });
    await expect
      .poll(() => new URL(page.url()).pathname, {
        timeout: 10_000,
        message: 'Back restored a protected page after logout instead of returning to login'
      })
      .toBe('/login');

    await expect(page.getByRole('heading', { name: 'Sign in to ForgeTDM' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Synthetic Data Generation' })).toHaveCount(0);
    expect(await page.evaluate(() => sessionStorage.getItem('auth003.protectedExposureObserved'))).toBe('0');
  });
});

function newApiState(): ApiState {
  return {
    authenticated: true,
    expireAllProtected: false,
    expirePath: null,
    failedLoginMessage: null,
    expiredRequests: [],
    loginRequests: 0,
    concurrentBarrier: null
  };
}

async function installApiContract(page: Page, state: ApiState) {
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;

    if (path === '/api/auth/login') {
      state.loginRequests += 1;
      if (state.failedLoginMessage) {
        await json(route, 401, { error: state.failedLoginMessage, correlationId: 'auth003-login-failed' });
        return;
      }
      state.authenticated = true;
      state.expireAllProtected = false;
      state.expirePath = null;
      await json(route, 200, { authenticated: true });
      return;
    }

    if (path === '/api/auth/me') {
      await json(route, 200, authPayload(state.authenticated));
      return;
    }

    if (path === '/api/auth/logout') {
      state.authenticated = false;
      await json(route, 200, { authenticated: false });
      return;
    }

    if (state.expireAllProtected || state.expirePath === path) {
      state.expiredRequests.push(path);
      if (state.concurrentBarrier) await arriveAtBarrier(state.concurrentBarrier);
      await json(route, 401, { error: 'Session expired', correlationId: `auth003-${state.expiredRequests.length}` });
      return;
    }


    await json(route, 200, []);
  });
}

function authPayload(authenticated: boolean) {
  if (!authenticated) return { authenticated: false };
  return {
    authenticated: true,
    user: {
      username: 'auth003-user',
      displayName: 'AUTH-003 Browser User',
      roles: ['ADMIN'],
      permissions: ['admin.all'],
      groups: []
    }
  };
}

async function json(route: Route, status: number, body: unknown) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'cache-control': 'no-store' },
    body: JSON.stringify(body)
  });
}

function createBarrier(target: number): ConcurrentBarrier {
  let release: () => void = () => {};
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { target, arrived: 0, promise, release };
}

async function arriveAtBarrier(barrier: ConcurrentBarrier) {
  barrier.arrived += 1;
  if (barrier.arrived >= barrier.target) barrier.release();
  await Promise.race([barrier.promise, new Promise<void>((resolve) => setTimeout(resolve, 5_000))]);
}

function trackLoginDocumentRequests(page: Page) {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (request.isNavigationRequest() && new URL(request.url()).pathname === '/login') requests.push(request.url());
  });
  return requests;
}

async function installNotificationCounter(page: Page) {
  await page.addInitScript(() => {
    const key = 'auth003.maxNotifications';
    if (sessionStorage.getItem(key) === null) sessionStorage.setItem(key, '0');
    const record = () => {
      const count = document.querySelectorAll('.mantine-Notification-root, [data-notification-id]').length;
      const maximum = Number(sessionStorage.getItem(key) || '0');
      if (count > maximum) sessionStorage.setItem(key, String(count));
    };
    addEventListener('DOMContentLoaded', () => {
      record();
      new MutationObserver(record).observe(document.documentElement, { childList: true, subtree: true });
    });
  });
}

async function installProtectedContentExposureGuard(page: Page, protectedText: string) {
  await page.addInitScript((text) => {
    const armedKey = 'auth003.protectedExposureArmed';
    const observedKey = 'auth003.protectedExposureObserved';
    const record = () => {
      if (sessionStorage.getItem(armedKey) !== '1') return;
      if (document.body?.innerText.includes(text)) sessionStorage.setItem(observedKey, '1');
    };
    addEventListener('DOMContentLoaded', record);
    addEventListener('pageshow', record);
    new MutationObserver(record).observe(document.documentElement, { childList: true, subtree: true });
  }, protectedText);
}

async function maxNotificationCount(page: Page) {
  return page.evaluate(() => Number(sessionStorage.getItem('auth003.maxNotifications') || '0'));
}

async function signIn(page: Page) {
  await submitLogin(page);
}

async function submitLogin(page: Page) {
  await page.getByLabel('Username').fill('auth003-user');
  await page.getByLabel('Password').fill('auth003-password');
  await page.getByRole('button', { name: 'Sign in' }).click();
}
