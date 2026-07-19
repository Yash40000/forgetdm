import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

import { expect, test } from '@playwright/test';

const username = process.env.AUTH001_USER?.trim();
const password = process.env.AUTH001_PASSWORD;
const evidenceDirectory = process.env.AUTH001_LANE_EVIDENCE_DIR?.trim();
const commitIdentity = process.env.AUTH001_COMMIT_IDENTITY?.trim();
const sourceIdentity = process.env.AUTH001_SOURCE_IDENTITY?.trim();
const nextBuildId = process.env.AUTH001_NEXT_BUILD_ID?.trim();
const serverMode = process.env.AUTH001_SERVER_MODE?.trim();

const MAX_NETWORK_EVENTS = 250;
const MAX_RESPONSE_BODIES = 250;
const MAX_RESPONSE_BODY_CHARS = 12_000_000;
const MAX_SINGLE_RESPONSE_CHARS = 4_000_000;

type Lane = 'HTTP' | 'HTTPS';

type SanitizedNetworkEvent = {
  method: string;
  path: string;
  status: number;
  lane: Lane;
  buildIdentity: string;
};

type SanitizedBrowserEvidence = {
  schemaVersion: '1.0';
  caseId: 'AUTH-001-01';
  lane: Lane;
  executedAtUtc: string;
  baseOrigin: string;
  browserChannel: 'msedge';
  serverMode: string;
  buildIdentity: {
    commit: string;
    sourceTreeSha256: string;
    nextBuildId: string;
  };
  protectedRouteRedirected: boolean;
  returnedToRequestedRoute: boolean;
  responseBodyInspection: {
    inspectedCount: number;
    unreadableCount: number;
    boundedCaptureExceeded: boolean;
    actualUsernameChecked: boolean;
    expectedPrincipalUsernameObserved: boolean;
    unexpectedUsernameExposureDetected: boolean;
    actualPasswordChecked: boolean;
    passwordExposureDetected: boolean;
    actualSessionCookieChecked: boolean;
    sessionCookieExposureDetected: boolean;
  };
  browserExposure: {
    inspectedUrlCount: number;
    inspectedConsoleMessageCount: number;
    inspectedStorageEntryCount: number;
    actualUsernameChecked: boolean;
    actualPasswordChecked: boolean;
    actualSessionCookieChecked: boolean;
    urlExposureDetected: boolean;
    storageSensitiveKeyDetected: boolean;
    storageValueExposureDetected: boolean;
    consoleExposureDetected: boolean;
  };
  sessionCookie: {
    present: boolean;
    httpOnly: boolean;
    secureMatchesLane: boolean;
    pathIsRoot: boolean;
    sameSiteIsLax: boolean;
    positiveFiniteLifetime: boolean;
  };
  retainedEvidenceSecretScan: {
    actualUsernameChecked: boolean;
    actualPasswordChecked: boolean;
    actualSessionCookieChecked: boolean;
    exposureDetected: boolean;
  };
  networkEvents: SanitizedNetworkEvent[];
  screenshot: string;
  productionTrustGate: string;
};

type TransientResponseBody = {
  path: string;
  body: string;
};

test.describe('AUTH-001 real browser login evidence', () => {
  test('AUTH-001-01: protected route returns after UI sign-in without client credential exposure', async ({ page, context }, testInfo) => {
    test.skip(!username || !password, 'AUTH001_USER and AUTH001_PASSWORD are required; this suite never provides credential defaults.');

    expect(evidenceDirectory, 'The bounded browser harness must provide AUTH001_LANE_EVIDENCE_DIR.').toBeTruthy();
    expect(commitIdentity, 'The browser evidence must be bound to a Git commit.').toBeTruthy();
    expect(sourceIdentity, 'The browser evidence must be bound to a source-tree digest.').toBeTruthy();
    expect(nextBuildId, 'The browser evidence must be bound to an optimized Next build ID.').toBeTruthy();
    expect(serverMode, 'The browser evidence must identify the optimized server mode.').toBe('next-start-optimized');

    const lane: Lane = testInfo.project.name.includes('https') ? 'HTTPS' : 'HTTP';
    const configuredBaseUrl = String(testInfo.project.use.baseURL || '');
    const baseOrigin = new URL(configuredBaseUrl).origin;
    expect(new URL(baseOrigin).protocol).toBe(lane === 'HTTPS' ? 'https:' : 'http:');

    const requestedPath = '/datascope';
    const consoleMessages: string[] = [];
    const transientUrls: string[] = [];
    const transientResponseBodies: TransientResponseBody[] = [];
    const responseInspections: Promise<void>[] = [];
    const networkEvents: SanitizedNetworkEvent[] = [];
    let totalResponseBodyChars = 0;
    let responseBodyLimitExceeded = false;
    let networkEventLimitExceeded = false;
    let unreadableResponseBodies = 0;
    let responseInspectionsScheduled = 0;

    page.on('console', (message) => consoleMessages.push(message.text()));
    page.on('request', (request) => transientUrls.push(request.url()));
    page.on('response', (response) => {
      const request = response.request();
      transientUrls.push(response.url());

      if (networkEvents.length >= MAX_NETWORK_EVENTS) {
        networkEventLimitExceeded = true;
      } else {
        const responseUrl = new URL(response.url());
        networkEvents.push({
          method: request.method(),
          path: responseUrl.pathname,
          status: response.status(),
          lane,
          buildIdentity: `${commitIdentity}:${sourceIdentity}:${nextBuildId}`
        });
      }

      const resourceType = request.resourceType();
      if (!['document', 'xhr', 'fetch'].includes(resourceType)) return;
      if (responseInspectionsScheduled >= MAX_RESPONSE_BODIES) {
        responseBodyLimitExceeded = true;
        return;
      }
      responseInspectionsScheduled += 1;

      responseInspections.push((async () => {
        try {
          const contentType = (await response.headerValue('content-type') || '').toLowerCase();
          if (!/json|text|javascript|xml|html/.test(contentType)) return;
          const body = await response.text();
          if (body.length > MAX_SINGLE_RESPONSE_CHARS || totalResponseBodyChars + body.length > MAX_RESPONSE_BODY_CHARS) {
            responseBodyLimitExceeded = true;
            return;
          }
          totalResponseBodyChars += body.length;
          transientResponseBodies.push({ path: new URL(response.url()).pathname, body });
        } catch {
          // Redirects and cancelled navigations can have no readable body. Count them rather than retaining errors.
          unreadableResponseBodies += 1;
        }
      })());
    });

    await page.goto(requestedPath);
    await page.waitForURL(/\/login\?next=/);
    expect(new URL(page.url()).searchParams.get('next')).toBe(requestedPath);

    const loginResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === '/api/auth/login' && response.request().method() === 'POST';
    });

    await page.getByLabel('Username').fill(username!);
    await page.getByLabel('Password').fill(password!);
    await page.getByRole('button', { name: 'Sign in' }).click();

    const loginResponse = await loginResponsePromise;
    expect(loginResponse.status()).toBe(200);

    await page.waitForURL((url) => url.pathname === requestedPath);
    expect(new URL(page.url()).pathname).toBe(requestedPath);
    await expect(page.getByRole('heading', { name: 'DataScope', exact: true })).toBeVisible();
    await expect(page.getByText('Loading DataScope workspace...', { exact: true })).toBeHidden();

    // Let bounded post-login API activity settle, then freeze the transient inspection set.
    await page.waitForTimeout(750);
    await Promise.all(responseInspections);
    transientUrls.push(page.url());

    expect(networkEventLimitExceeded, 'The sanitized network-event bound was exceeded.').toBe(false);
    expect(responseBodyLimitExceeded, 'The textual response-body inspection bound was exceeded.').toBe(false);

    const sessionCookie = (await context.cookies()).find((cookie) => cookie.name === 'FORGETDM_SESSION');
    expect(Boolean(sessionCookie), 'A successful login must issue the named session cookie.').toBe(true);
    const sessionCookieValue = sessionCookie?.value || '';
    expect(sessionCookieValue.length > 0, 'The issued session cookie must have a non-empty value.').toBe(true);

    const usernameVariants = sensitiveValueVariants(username!);
    const passwordVariants = sensitiveValueVariants(password!);
    const sessionVariants = sensitiveValueVariants(sessionCookieValue);

    let expectedPrincipalUsernameObserved = false;
    let unexpectedUsernameExposureDetected = false;
    let passwordResponseExposureDetected = false;
    let sessionResponseExposureDetected = false;

    for (const response of transientResponseBodies) {
      const inspectedBody = redactExpectedPrincipalUsername(response.path, response.body, username!, () => {
        expectedPrincipalUsernameObserved = true;
      });
      unexpectedUsernameExposureDetected ||= containsAny(inspectedBody, usernameVariants);
      passwordResponseExposureDetected ||= containsAny(inspectedBody, passwordVariants);
      sessionResponseExposureDetected ||= containsAny(inspectedBody, sessionVariants);
    }

    expect(expectedPrincipalUsernameObserved, 'The login response must expose the username only as the authenticated principal identity.').toBe(true);
    expect(unexpectedUsernameExposureDetected, 'The entered username appeared outside the expected structured principal field.').toBe(false);
    expect(passwordResponseExposureDetected, 'The entered password appeared in a textual response body.').toBe(false);
    expect(sessionResponseExposureDetected, 'The issued session cookie value appeared in a textual response body.').toBe(false);

    const urlExposureDetected = transientUrls.some((url) =>
      containsAny(url, usernameVariants) || containsAny(url, passwordVariants) || containsAny(url, sessionVariants));
    expect(urlExposureDetected, 'An actual authentication value appeared in a browser request, response, or navigation URL.').toBe(false);

    const storage = await page.evaluate(() => {
      const collect = (store: Storage) => Array.from({ length: store.length }, (_, index) => {
        const key = store.key(index) || '';
        return { key, value: store.getItem(key) || '' };
      });
      return [...collect(localStorage), ...collect(sessionStorage)];
    });
    const sensitiveStorageKey = /password|token|session|authorization|bearer|secret/i;
    const storageSensitiveKeyDetected = storage.some((entry) => sensitiveStorageKey.test(entry.key));
    const storageValueExposureDetected = storage.some((entry) =>
      containsAny(entry.key, usernameVariants) || containsAny(entry.key, passwordVariants) || containsAny(entry.key, sessionVariants)
      || containsAny(entry.value, usernameVariants) || containsAny(entry.value, passwordVariants) || containsAny(entry.value, sessionVariants));
    expect(storageSensitiveKeyDetected, 'Sensitive authentication material must not be persisted in browser storage keys.').toBe(false);
    expect(storageValueExposureDetected, 'An actual authentication value appeared in browser storage.').toBe(false);

    const sensitiveConsoleLabel = /password|forgetdm_session|authorization:\s*bearer|\bftdm_/i;
    const consoleExposureDetected = consoleMessages.some((message) =>
      sensitiveConsoleLabel.test(message)
      || containsAny(message, usernameVariants)
      || containsAny(message, passwordVariants)
      || containsAny(message, sessionVariants));
    expect(consoleExposureDetected, 'An actual authentication value or credential label appeared in the browser console.').toBe(false);

    expect(sessionCookie?.httpOnly).toBe(true);
    expect(sessionCookie?.secure).toBe(lane === 'HTTPS');
    expect(sessionCookie?.path).toBe('/');
    expect(sessionCookie?.sameSite).toBe('Lax');
    expect(sessionCookie?.expires ?? -1).toBeGreaterThan(Date.now() / 1000);

    const screenshot = testInfo.outputPath(`AUTH-001-01-${lane.toLowerCase()}-post-login.png`);
    await page.screenshot({ path: screenshot, fullPage: false });

    const evidence: SanitizedBrowserEvidence = {
      schemaVersion: '1.0',
      caseId: 'AUTH-001-01',
      lane,
      executedAtUtc: new Date().toISOString(),
      baseOrigin,
      browserChannel: 'msedge',
      serverMode: serverMode!,
      buildIdentity: {
        commit: commitIdentity!,
        sourceTreeSha256: sourceIdentity!,
        nextBuildId: nextBuildId!
      },
      protectedRouteRedirected: true,
      returnedToRequestedRoute: true,
      responseBodyInspection: {
        inspectedCount: transientResponseBodies.length,
        unreadableCount: unreadableResponseBodies,
        boundedCaptureExceeded: false,
        actualUsernameChecked: true,
        expectedPrincipalUsernameObserved: true,
        unexpectedUsernameExposureDetected: false,
        actualPasswordChecked: true,
        passwordExposureDetected: false,
        actualSessionCookieChecked: true,
        sessionCookieExposureDetected: false
      },
      browserExposure: {
        inspectedUrlCount: transientUrls.length,
        inspectedConsoleMessageCount: consoleMessages.length,
        inspectedStorageEntryCount: storage.length,
        actualUsernameChecked: true,
        actualPasswordChecked: true,
        actualSessionCookieChecked: true,
        urlExposureDetected: false,
        storageSensitiveKeyDetected: false,
        storageValueExposureDetected: false,
        consoleExposureDetected: false
      },
      sessionCookie: {
        present: true,
        httpOnly: true,
        secureMatchesLane: true,
        pathIsRoot: true,
        sameSiteIsLax: true,
        positiveFiniteLifetime: true
      },
      retainedEvidenceSecretScan: {
        actualUsernameChecked: true,
        actualPasswordChecked: true,
        actualSessionCookieChecked: true,
        exposureDetected: false
      },
      networkEvents,
      screenshot: `AUTH-001-01-${lane.toLowerCase()}-post-login.png`,
      productionTrustGate: 'Local HTTPS proves physical TLS termination with an ephemeral self-signed certificate. Production CA chain, ingress policy, and HSTS remain deployment evidence gates.'
    };

    const serializedEvidence = JSON.stringify(evidence, null, 2);
    const retainedEvidenceExposureDetected = containsAny(serializedEvidence, usernameVariants)
      || containsAny(serializedEvidence, passwordVariants)
      || containsAny(serializedEvidence, sessionVariants);
    expect(retainedEvidenceExposureDetected, 'Sanitized retained evidence contained an actual authentication value.').toBe(false);

    await mkdir(evidenceDirectory!, { recursive: true });
    const laneEvidencePath = path.join(evidenceDirectory!, `AUTH-001-01-${lane.toLowerCase()}.json`);
    await writeFile(laneEvidencePath, serializedEvidence, { encoding: 'utf8', flag: 'w' });
    await testInfo.attach(`AUTH-001-01 ${lane} sanitized browser evidence`, {
      body: Buffer.from(serializedEvidence),
      contentType: 'application/json'
    });

    // These values are deliberately never written to output, attachments, traces, videos, or screenshots.
    transientResponseBodies.length = 0;
    transientUrls.length = 0;
    consoleMessages.length = 0;
    storage.length = 0;
  });
});

function sensitiveValueVariants(value: string): string[] {
  const variants = new Set<string>();
  if (!value) return [];
  variants.add(value);
  variants.add(encodeURIComponent(value));
  variants.add(encodeURI(value));
  variants.add(JSON.stringify(value).slice(1, -1));
  variants.add(Buffer.from(value, 'utf8').toString('base64'));
  return [...variants].filter(Boolean);
}

function containsAny(text: string, variants: string[]): boolean {
  return variants.some((variant) => text.includes(variant));
}

function redactExpectedPrincipalUsername(
  responsePath: string,
  body: string,
  actualUsername: string,
  onObserved: () => void
): string {
  if (responsePath !== '/api/auth/login' && responsePath !== '/api/auth/me') return body;
  try {
    const parsed = JSON.parse(body) as { authenticated?: unknown; user?: { username?: unknown } };
    if (parsed.authenticated === true && parsed.user?.username === actualUsername) {
      onObserved();
      parsed.user.username = '[EXPECTED_AUTHENTICATED_PRINCIPAL]';
      return JSON.stringify(parsed);
    }
  } catch {
    // Non-JSON responses receive the strict scan with no expected-field allowance.
  }
  return body;
}
