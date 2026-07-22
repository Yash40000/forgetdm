import { readFile } from 'node:fs/promises';

import { expect, test } from '@playwright/test';

const username = process.env.DISC007_USER?.trim() || 'admin';
const password = process.env.DISC007_PASSWORD || 'admin123';
const sourceName = process.env.DISC007_SOURCE_NAME?.trim() || 'BE Lab - Core Banking PostgreSQL';
const dataSourceId = Number(process.env.DISC007_SOURCE_ID || '10');
const schema = process.env.DISC007_SCHEMA?.trim() || 'disc007_acceptance';

test('findings workspace and CSV agree with the current rescan results', async ({ page }) => {
  await page.goto('/pii-discovery');
  await expect(page).toHaveURL(/\/login\?/);
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'PII Discovery' })).toBeVisible();

  const sourceInput = page.getByRole('textbox', { name: 'Data source', exact: true });
  const schemaInput = page.getByRole('textbox', { name: 'Schema', exact: true });
  await sourceInput.fill(sourceName);
  await sourceInput.blur();
  await expect(schemaInput).toBeEnabled();
  const tablesLoaded = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return response.request().method() === 'GET'
      && url.pathname === `/api/datasources/${dataSourceId}/tables`
      && url.searchParams.get('schema') === schema;
  });
  await schemaInput.fill(schema);
  await schemaInput.blur();
  expect((await tablesLoaded).ok()).toBe(true);
  const scanStarted = page.waitForResponse((response) => response.request().method() === 'POST'
    && new URL(response.url()).pathname === `/api/discovery/scan-jobs/${dataSourceId}`);
  await page.getByRole('button', { name: 'Start scan' }).click();
  expect((await scanStarted).ok()).toBe(true);
  await expect(page.getByText('Scan complete', { exact: true })).toBeVisible({ timeout: 90_000 });

  const apiRows = await page.evaluate(async ({ id, schemaName }) => {
    const response = await fetch(`/api/discovery/results/${id}?schema=${encodeURIComponent(schemaName)}`);
    if (!response.ok) throw new Error(`Results API returned ${response.status}`);
    return response.json() as Promise<Array<{ tableName: string; columnName: string }>>;
  }, { id: dataSourceId, schemaName: schema });
  expect(apiRows.length).toBeGreaterThan(0);

  await page.getByRole('button', { name: /^Findings/ }).click();
  await expect(page.getByRole('dialog', { name: 'Findings review workspace' })).toBeVisible();
  await expect(page.locator('.pii-review-list-row')).toHaveCount(apiRows.length);
  for (const row of apiRows) {
    const renderedRow = page.locator('.pii-review-list-row').filter({ hasText: row.columnName });
    await expect(renderedRow).toHaveCount(1);
    await expect(renderedRow).toContainText(row.tableName);
    await expect(renderedRow).toContainText(row.columnName);
  }

  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download CSV' }).click();
  const download = await downloadPromise;
  const downloadPath = await download.path();
  expect(downloadPath).toBeTruthy();
  const csv = await readFile(downloadPath!, 'utf8');
  const lines = csv.trim().split(/\r?\n/);
  expect(lines[0]).toContain('"Table"');
  expect(lines.length - 1).toBe(apiRows.length);
  for (const row of apiRows) {
    expect(csv).toContain(row.tableName);
    expect(csv).toContain(row.columnName);
  }
});
