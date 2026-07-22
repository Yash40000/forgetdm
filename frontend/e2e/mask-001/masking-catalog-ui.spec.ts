import { expect, test } from '@playwright/test';

const username = process.env.MASK001_USER?.trim() || 'admin';
const password = process.env.MASK001_PASSWORD || 'admin123';

test('catalog, valid preview, and invalid-parameter feedback work in Masking Studio', async ({ page }) => {
  const login = await page.request.post('/api/auth/login', { data: { username, password } });
  expect(login.ok()).toBe(true);
  const session = login.headers()['set-cookie']?.match(/FORGETDM_SESSION=([^;]+)/)?.[1];
  expect(session).toBeTruthy();
  await page.context().addCookies([{
    name: 'FORGETDM_SESSION',
    value: session!,
    url: 'http://127.0.0.1:3000',
    httpOnly: true,
    sameSite: 'Lax'
  }]);
  const identity = await page.request.get('/api/auth/me');
  expect((await identity.json()).authenticated).toBe(true);
  await page.goto('/masking-studio');
  await expect(page.getByRole('heading', { name: 'Masking Studio' })).toBeVisible();
  await expect(page.locator('.masking-function-card')).toHaveCount(43);

  await page.getByRole('button', { name: /^SSN\s/ }).click();
  await page.getByRole('button', { name: 'Preview mask' }).click();
  await expect(page.locator('.masking-preview-result')).toBeVisible();
  await expect(page.locator('.masking-preview-result')).toContainText('123-45-6789');

  await page.getByRole('button', { name: 'Close function preview' }).click();
  await page.locator('.masking-function-card').filter({ hasText: 'NUMERIC_NOISE' }).click();
  await page.getByLabel('Noise amount').fill('PERCENT:ten');
  await page.getByRole('button', { name: 'Preview mask' }).click();
  await expect(page.getByText('Preview failed', { exact: true })).toBeVisible();
  await expect(page.getByText(/NUMERIC_NOISE param1 must be PERCENT:n or ABS:n/)).toBeVisible();
});
