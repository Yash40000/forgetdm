import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.DISC007_UI_BASE_URL?.trim() || 'http://127.0.0.1:3000';

export default defineConfig({
  testDir: './e2e/disc-007',
  outputDir: './test-results/disc-007/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 120_000,
  expect: { timeout: 30_000 },
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/disc-007/results.json' }],
    ['junit', { outputFile: 'test-results/disc-007/junit.xml' }]
  ],
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
