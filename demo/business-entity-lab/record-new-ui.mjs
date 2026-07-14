import { copyFileSync, mkdirSync, unlinkSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require('../../frontend/node_modules/playwright');

const root = path.resolve('..', 'demo', 'business-entity-lab');
const artifactDir = path.join(root, 'ui-evidence');
mkdirSync(artifactDir, { recursive: true });

const browser = await chromium.launch({ channel: 'msedge', headless: true });
const context = await browser.newContext({
  viewport: { width: 1600, height: 940 },
  recordVideo: { dir: artifactDir, size: { width: 1600, height: 940 } }
});
const page = await context.newPage();
const evidence = [];

async function shot(name, fullPage = true) {
  const file = path.join(artifactDir, `${name}.png`);
  await page.screenshot({ path: file, fullPage, animations: 'disabled' });
  evidence.push({ name, file, url: page.url(), title: await page.title() });
}

async function settle(ms = 900) {
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(ms);
}

try {
  const login = await context.request.post('http://localhost:3000/api/auth/login', {
    data: { username: 'admin', password: 'admin123' }
  });
  if (!login.ok()) {
    throw new Error(`UI session setup failed (${login.status()}): ${await login.text()}`);
  }

  await page.goto('http://localhost:3000/business-entities', { waitUntil: 'domcontentloaded', timeout: 45_000 });
  await page.getByText('Business Entity Management').waitFor({ timeout: 45_000 });
  await settle(1_500);
  await page.getByRole('option', { name: /BE Lab - Customer 360/ }).click();
  await page.getByText('43 member tables').waitFor({ timeout: 30_000 });
  await settle();
  await shot('01-business-entity-model');

  for (const [tab, file] of [
    ['Identity', '02-business-entity-identity'],
    ['Freshness', '03-business-entity-freshness'],
    ['Snapshots & reservations', '04-business-entity-snapshot'],
    ['Micro-DB', '05-business-entity-microdb'],
    ['Deliver', '05-business-entity-deliver'],
    ['Govern', '08-business-entity-governance']
  ]) {
    await page.getByRole('tab', { name: tab, exact: true }).click();
    await settle();
    if (tab === 'Micro-DB') {
      await page.getByText('customer_no=CUST-000042', { exact: true }).click();
      await page.getByText(/43 fragments/).first().waitFor({ timeout: 30_000 });
      await settle();
    }
    if (tab === 'Deliver') {
      await page.getByRole('tab', { name: 'Flow studio', exact: true }).click();
      await settle();
      await shot('06-business-entity-flow');
      await page.getByRole('tab', { name: 'Run & packages', exact: true }).click();
      await settle();
      await shot('07-business-entity-saved-jobs');
      continue;
    }
    await shot(file);
  }

  await page.goto('http://localhost:3000/datasources', { waitUntil: 'domcontentloaded', timeout: 45_000 });
  await page.getByText('BE Lab - Core Banking PostgreSQL', { exact: true }).waitFor({ timeout: 30_000 });
  await settle();
  await shot('09-data-sources-three-applications', false);

  await page.goto('http://localhost:3000/datascope', { waitUntil: 'domcontentloaded', timeout: 45_000 });
  await page.getByText('BE Lab - Core Banking Scope', { exact: true }).click();
  await page.getByRole('tab', { name: 'Table profiles', exact: true }).click();
  await page.getByText('Table Profile Setup', { exact: true }).waitFor({ timeout: 30_000 });
  await settle(3_000);
  await shot('10-datascope-blueprints', false);
} catch (error) {
  await page.screenshot({ path: path.join(artifactDir, 'recording-error.png'), fullPage: true }).catch(() => {});
  throw error;
} finally {
  const video = page.video();
  await context.close();
  if (video) {
    const generated = await video.path();
    const finalVideo = path.join(root, 'business-entity-new-ui-walkthrough.webm');
    copyFileSync(generated, finalVideo);
    if (generated !== finalVideo) unlinkSync(generated);
  }
  writeFileSync(path.join(artifactDir, 'manifest.json'), JSON.stringify(evidence, null, 2));
  await browser.close();
}

console.log(JSON.stringify({ screenshots: evidence.length, video: path.join(root, 'business-entity-new-ui-walkthrough.webm') }));
