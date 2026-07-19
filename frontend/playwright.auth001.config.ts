import path from 'node:path';

import { defineConfig, devices } from '@playwright/test';

const httpBaseUrl = process.env.AUTH001_HTTP_UI_BASE_URL?.trim() || 'http://127.0.0.1:3101';
const httpsBaseUrl = process.env.AUTH001_HTTPS_UI_BASE_URL?.trim() || 'https://127.0.0.1:3443';
const outputDir = process.env.AUTH001_PLAYWRIGHT_OUTPUT_DIR?.trim()
  || path.join(process.cwd(), 'test-results', 'auth-001', 'artifacts');
const reportDir = process.env.AUTH001_PLAYWRIGHT_REPORT_DIR?.trim()
  || path.join(process.cwd(), 'test-results', 'auth-001', 'reports');

export default defineConfig({
  testDir: './e2e/auth-001',
  outputDir,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 75_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['json', { outputFile: path.join(reportDir, 'AUTH-001-BROWSER-TWO-LANE.playwright.json') }],
    ['junit', { outputFile: path.join(reportDir, 'AUTH-001-BROWSER-TWO-LANE.junit.xml') }]
  ],
  use: {
    ...devices['Desktop Edge'],
    channel: 'msedge',
    headless: true,
    serviceWorkers: 'block',
    trace: 'off',
    screenshot: 'off',
    video: 'off'
  },
  projects: [
    {
      name: 'auth001-http-edge',
      use: {
        baseURL: httpBaseUrl,
        ignoreHTTPSErrors: false
      }
    },
    {
      name: 'auth001-https-edge',
      use: {
        baseURL: httpsBaseUrl,
        ignoreHTTPSErrors: true
      }
    }
  ]
});
