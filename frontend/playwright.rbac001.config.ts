import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.RBAC001_BASE_URL?.trim() || 'http://127.0.0.1:3000';
const evidenceSuffix = process.env.RBAC001_EVIDENCE_SUFFIX?.trim().replace(/[^a-zA-Z0-9_-]/g, '-') || 'verified-2026-07-19';

export default defineConfig({
  testDir: './e2e/rbac-001',
  outputDir: `./test-results/rbac-001/artifacts-${evidenceSuffix}`,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 75_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['json', { outputFile: `test-results/rbac-001/results-${evidenceSuffix}.json` }],
    ['junit', { outputFile: `test-results/rbac-001/junit-${evidenceSuffix}.xml` }]
  ],
  webServer: {
    command: 'npm.cmd run start -- --hostname 127.0.0.1 --port 3000',
    url: baseURL,
    reuseExistingServer: true,
    timeout: 240_000,
    env: {
      ...process.env,
      FORGETDM_API_BASE: process.env.FORGETDM_API_BASE?.trim() || 'http://127.0.0.1:8099'
    }
  },
  use: {
    ...devices['Desktop Edge'],
    baseURL,
    channel: 'msedge',
    headless: true,
    serviceWorkers: 'block',
    trace: 'on',
    screenshot: 'on',
    video: 'retain-on-failure'
  }
});
